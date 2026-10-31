package app.vitaflow.com.ui.chat

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vitaflow.com.AppContainer
import app.vitaflow.com.ai.Attachment
import app.vitaflow.com.ai.AttachmentJson
import app.vitaflow.com.data.db.AccountEntity
import app.vitaflow.com.data.db.ChatMessageEntity
import app.vitaflow.com.data.db.ConversationEntity
import app.vitaflow.com.max.MaxReply
import app.vitaflow.com.max.PendingAction
import app.vitaflow.com.max.WakeWord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class LiveState { OFF, STANDBY, LISTENING, THINKING, SPEAKING }

class ChatViewModel(private val c: AppContainer, private val account: AccountEntity) : ViewModel() {
    private val convId = MutableStateFlow(0L)
    val conversationId: StateFlow<Long> = convId

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<ChatMessageEntity>> = convId
        .flatMapLatest { id -> if (id == 0L) flowOf(emptyList()) else c.chatRepo.messages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val conversations: StateFlow<List<ConversationEntity>> =
        c.chatRepo.conversations(account.id).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val voiceLang: StateFlow<String> =
        c.settingsStore.voiceLang.stateIn(viewModelScope, SharingStarted.Eagerly, "bn")
    val speakReplies: StateFlow<Boolean> =
        c.settingsStore.speakReplies.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val wakeStandby: StateFlow<Boolean> =
        c.settingsStore.wakeStandby.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    var input by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var recording by mutableStateOf(false)
        private set
    var speaking by mutableStateOf(false)
        private set
    var streaming by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var liveState by mutableStateOf(LiveState.OFF)
        private set
    var importing by mutableStateOf(false)
        private set

    /** Photos and files chosen but not sent yet. */
    val attachments = mutableStateListOf<Attachment>()

    private var pending: PendingAction? = null
    private var liveJob: Job? = null
    private var foldJob: Job? = null
    private var ready = false

    init {
        viewModelScope.launch {
            val last = c.settingsStore.lastConversationId.first()
            val ok = last != 0L && c.chatRepo.conversation(last)?.accountId == account.id
            setCurrent(if (ok) last else c.chatRepo.newConversation(account.id))
            ready = true
        }
        // if the current chat disappears (deleted, or all history cleared) start a fresh one
        viewModelScope.launch {
            conversations.collect { list ->
                if (ready && convId.value != 0L && list.none { it.id == convId.value }) startFresh()
            }
        }
    }

    private suspend fun setCurrent(id: Long) {
        convId.value = id
        c.settingsStore.setLastConversationId(id)
    }

    private suspend fun startFresh() {
        pending = null
        setCurrent(c.chatRepo.newConversation(account.id))
    }

    fun setStatus(s: String?) {
        status = s
    }

    fun setLang(lang: String) {
        viewModelScope.launch { c.settingsStore.setVoiceLang(lang) }
    }

    fun setSpeakReplies(v: Boolean) {
        viewModelScope.launch { c.settingsStore.setSpeakReplies(v) }
    }

    private fun hasBengali(s: String) = s.any { it in '\u0980'..'\u09FF' }

    // ---------------------------------------------------------------- chats

    /** When a chat is left, Max brings its summary, one-line digest and learned facts up to date. */
    private fun finalizeChat(id: Long) {
        if (id == 0L) return
        c.appScope.launch { c.memory.foldIfNeeded(account.id, id, force = true) }
    }

    fun onLeaveChat() {
        finalizeChat(convId.value)
    }

    fun newChat() {
        if (busy || messages.value.isEmpty()) return
        finalizeChat(convId.value)
        attachments.clear()
        viewModelScope.launch { startFresh() }
    }

    fun openChat(id: Long) {
        if (busy || id == convId.value) return
        finalizeChat(convId.value)
        attachments.clear()
        pending = null
        viewModelScope.launch { setCurrent(id) }
    }

    fun deleteChat(id: Long) {
        viewModelScope.launch { c.chatRepo.deleteConversation(id) }
    }

    // ---------------------------------------------------------------- photos and files

    fun attach(uri: Uri) {
        viewModelScope.launch {
            importing = true
            status = "ফাইল তৈরি হচ্ছে..."
            c.attachments.import(uri)
                .onSuccess { list ->
                    list.forEach { if (attachments.size < 4) attachments.add(it) }
                    status = null
                }
                .onFailure { status = it.message ?: "ফাইলটা নেওয়া যায়নি" }
            importing = false
        }
    }

    fun removeAttachment(a: Attachment) {
        attachments.remove(a)
    }

    // ---------------------------------------------------------------- one turn (typed, mic or live)

    /** [fast] = true skips the AI for the quick buttons, so they stay instant. */
    fun send(raw: String, fast: Boolean = false) {
        val text = raw.trim()
        if ((text.isEmpty() && attachments.isEmpty()) || busy || importing) return
        val files = attachments.toList()
        attachments.clear()
        input = ""
        viewModelScope.launch { runTurn(text, live = false, fast = fast, files = files) }
    }

    /**
     * Handles one message end to end. While a slow answer is being prepared and the reply will be spoken,
     * Max keeps talking ("okay, working on it") so there is never a long silence.
     */
    private suspend fun runTurn(text: String, live: Boolean, fast: Boolean = false, files: List<Attachment> = emptyList()): MaxReply {
        busy = true
        status = null
        foldJob?.cancel()
        c.tts.stop()
        if (convId.value == 0L) startFresh()
        val cid = convId.value
        c.chatRepo.add(account.id, cid, "USER", text, attachmentsJson = AttachmentJson.encode(files))

        val speakIt = live || speakReplies.value
        val filler: Job? =
            if (speakIt && (files.isNotEmpty() || c.max.needsThinking(text, pending, fast))) viewModelScope.launch { fillers(text) } else null
        if (live) liveState = LiveState.THINKING

        val reply: MaxReply = try {
            if (files.isEmpty()) {
                c.max.handle(account.id, cid, account.name, text, pending, fast) { streaming = it }
            } else {
                c.max.handleAttachments(account.id, cid, account.name, text, files) { streaming = it }
            }
        } catch (e: Exception) {
            MaxReply("সমস্যা হয়েছে: ${e.message ?: "অজানা"}")
        }
        filler?.cancel()

        pending = reply.pending
        streaming = null
        val sources = if (reply.sources.isEmpty()) null else JSONArray().also { arr ->
            reply.sources.forEach { arr.put(JSONObject().put("title", it.title).put("url", it.url)) }
        }.toString()
        c.chatRepo.add(account.id, cid, "MAX", reply.text, reply.changeIds, sources)
        busy = false
        scheduleFold()

        if (speakIt) {
            if (live) liveState = LiveState.SPEAKING
            speakReply(reply, text)
            if (live) delay(300) // let the room go quiet before listening again
        }
        return reply
    }

    /** A little later, when nothing else is going on, older messages are folded into the chat's summary. */
    private fun scheduleFold() {
        foldJob?.cancel()
        foldJob = viewModelScope.launch {
            delay(20_000)
            if (liveState == LiveState.OFF && !busy) c.memory.foldIfNeeded(account.id, convId.value)
        }
    }

    private suspend fun fillers(userText: String) {
        val phrases = if (hasBengali(userText) || userText.isBlank()) {
            listOf("আচ্ছা, ঠিক আছে, করছি।", "একটু অপেক্ষা করুন, কাজটা করছি।", "আর একটু সময় লাগবে।", "প্রায় হয়ে এসেছে।")
        } else {
            listOf("Okay, working on it.", "Just a moment, I am doing it.", "Almost there.")
        }
        var i = 0
        while (currentCoroutineContext().isActive) {
            try {
                c.tts.speak(phrases[minOf(i, phrases.size - 1)], cache = true)
            } catch (e: Exception) {
                return
            }
            i++
            delay(6000)
        }
    }

    /** After a change the spoken answer ends with "you can check it now"; other answers are read out as they are. */
    private suspend fun speakReply(reply: MaxReply, userText: String) {
        val bn = hasBengali(userText) || userText.none { it.isLetter() }
        val spoken = when {
            reply.changeIds.isEmpty() -> reply.text
            bn -> "ওকে, ${reply.text} চাইলে এখন দেখতে পারেন।"
            else -> "Okay, your work is done. You can check it now if you like."
        }
        speaking = true
        try {
            c.tts.speak(spoken)
        } catch (e: Exception) {
            status = "voice চালানো যায়নি: ${e.message ?: "অজানা"}"
        }
        speaking = false
    }

    // ---------------------------------------------------------------- tap-to-talk (one sentence)

    fun toggleMic() {
        if (liveState != LiveState.OFF) return
        if (!recording) {
            c.tts.stop()
            if (!c.recorder.start()) {
                status = "মাইক্রোফোন চালু করা যায়নি"
                return
            }
            recording = true
            viewModelScope.launch { c.llm.warmUp() }
            status = "শুনছি... আবার চাপলে থামবে"
        } else {
            recording = false
            val samples = c.recorder.stop()
            if (samples.size < 4800) {
                status = "কিছু শুনতে পাইনি"
                return
            }
            status = "বুঝছি..."
            viewModelScope.launch {
                try {
                    val text = c.asr.transcribe(samples, voiceLang.value)
                    if (text.isBlank()) {
                        status = "কিছু বুঝতে পারিনি, আবার বলুন"
                    } else {
                        status = null
                        send(text)
                    }
                } catch (e: Exception) {
                    status = e.message ?: "শোনা যায়নি"
                }
            }
        }
    }

    // ---------------------------------------------------------------- Live mode (hands-free)

    fun startLive() {
        if (liveJob?.isActive == true) return
        if (recording) {
            recording = false
            c.recorder.cancel()
        }
        viewModelScope.launch { c.llm.warmUp() }
        liveJob = viewModelScope.launch {
            try {
                liveLoop(startActive = !wakeStandby.value)
            } finally {
                liveState = LiveState.OFF
                status = null
            }
        }
    }

    fun stopLive() {
        liveJob?.cancel()
        liveJob = null
        c.tts.stop()
        liveState = LiveState.OFF
    }

    private suspend fun say(text: String) {
        liveState = LiveState.SPEAKING
        try {
            c.tts.speak(text, cache = true)
        } catch (e: Exception) {
            // no voice model yet: stay silent
        }
        delay(300)
    }

    private suspend fun liveLoop(startActive: Boolean) {
        val bn = voiceLang.value == "bn"
        var active = startActive
        var silentTurns = 0
        if (active) say(if (bn) "জি, বলুন।" else "Yes, go ahead.")

        while (currentCoroutineContext().isActive) {
            liveState = if (active) LiveState.LISTENING else LiveState.STANDBY
            status = if (active) "শুনছি..." else "\"Hey Max\" বললে আমি জাগব..."
            val audio = c.listener.listen(
                waitMs = if (active) 12_000 else 8_000,
                maxSpeechMs = 15_000,
                silenceMs = if (active) 1_300 else 800
            )
            if (audio == null) {
                if (active && ++silentTurns >= 3) {
                    if (wakeStandby.value) {
                        active = false
                        silentTurns = 0
                    } else {
                        say(if (bn) "কিছু শুনতে পাচ্ছি না, তাই live বন্ধ করছি।" else "I can't hear anything, so I am stopping live mode.")
                        return
                    }
                }
                continue
            }

            liveState = LiveState.THINKING
            status = "বুঝছি..."
            val heard = try {
                c.asr.transcribe(audio, voiceLang.value)
            } catch (e: Exception) {
                status = e.message ?: "শোনা যায়নি"
                return
            }
            if (heard.isBlank()) continue
            silentTurns = 0

            var utterance = heard
            if (!active) {
                val rest = WakeWord.match(heard) ?: continue
                active = true
                if (rest.isEmpty()) {
                    say(if (bn) "জি, বলুন।" else "Yes, go ahead.")
                    continue
                }
                utterance = rest
            }

            val reply = runTurn(utterance, live = true)
            if (reply.endLive) {
                if (wakeStandby.value) {
                    active = false
                    silentTurns = 0
                } else {
                    return
                }
            }
        }
    }

    // ----------------------------------------------------------------

    fun speak(text: String) {
        viewModelScope.launch {
            speaking = true
            try {
                c.tts.speak(text)
            } catch (e: Exception) {
                status = "voice চালানো যায়নি: ${e.message ?: "অজানা"}"
            }
            speaking = false
        }
    }

    fun stopSpeaking() {
        c.tts.stop()
    }

    override fun onCleared() {
        liveJob?.cancel()
        if (recording) c.recorder.cancel()
        c.tts.stop()
    }
}
