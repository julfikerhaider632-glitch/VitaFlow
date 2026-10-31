package app.vitaflow.com.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The assistant's brain: Gemma running on the phone through LiteRT-LM (CPU).
 * The model is loaded only when needed and unloaded after a few idle minutes to save RAM.
 * It can also look at photos (the vision part is loaded only when a photo is sent).
 */
class LlmEngine(private val context: Context, private val models: ModelManager) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: Engine? = null
    private var engineHasVision = false
    private var idleJob: Job? = null
    private var session: Conversation? = null
    private var sessionKey = ""
    private var sessionTurns = 0

    fun isReady(): Boolean = models.llmFile() != null

    private fun closeSession() {
        runCatching { session?.close() }
        session = null
        sessionKey = ""
        sessionTurns = 0
    }

    private fun ensureEngine(vision: Boolean = false): Engine {
        engine?.let {
            if (!vision || engineHasVision) return it
            // a photo needs the vision part: reload once with it
            closeSession()
            runCatching { it.close() }
            engine = null
        }
        val file = models.llmFile() ?: error("Gemma model যোগ করা নেই")
        val config = if (vision) {
            EngineConfig(
                modelPath = file.absolutePath, backend = Backend.CPU(),
                visionBackend = Backend.CPU(), cacheDir = context.cacheDir.path
            )
        } else {
            EngineConfig(modelPath = file.absolutePath, backend = Backend.CPU(), cacheDir = context.cacheDir.path)
        }
        val e = Engine(config)
        e.initialize()
        engine = e
        engineHasVision = vision
        return e
    }

    /** Loads the model in the background before it is needed, so the first answer is not slower. */
    suspend fun warmUp() {
        if (!isReady()) return
        idleJob?.cancel()
        mutex.withLock {
            runCatching { withContext(Dispatchers.Default) { ensureEngine() } }
        }
        scheduleUnload()
    }

    private fun scheduleUnload() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(8 * 60 * 1000L)
            mutex.withLock {
                closeSession()
                runCatching { engine?.close() }
                engine = null
                engineHasVision = false
            }
        }
    }

    private fun conversationConfig(system: String, temperature: Double) = ConversationConfig(
        systemInstruction = Contents.of(system),
        samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = temperature)
    )

    /** One question, one full answer. [temperature] low (0.1-0.3) for JSON, higher for chat. */
    suspend fun complete(system: String, user: String, temperature: Double = 0.2): String {
        idleJob?.cancel()
        return mutex.withLock {
            try {
                withContext(Dispatchers.Default) {
                    val e = ensureEngine()
                    e.createConversation(conversationConfig(system, temperature)).use { c ->
                        c.sendMessage(user).toString()
                    }
                }
            } finally {
                scheduleUnload()
            }
        }
    }

    /**
     * For many short requests that share one long instruction: the instruction (and the chat so far) is processed
     * once and kept, so each new request only pays for its own few words. [key] identifies the chat; when the chat
     * changes, or after [maxTurns] requests, a fresh conversation is started with the newest memory in [system].
     */
    suspend fun completeSession(key: String, system: String, user: String, temperature: Double = 0.1, maxTurns: Int = 6): String {
        idleJob?.cancel()
        return mutex.withLock {
            try {
                withContext(Dispatchers.Default) {
                    val e = ensureEngine()
                    if (session == null || sessionKey != key || sessionTurns >= maxTurns) {
                        closeSession()
                        session = e.createConversation(conversationConfig(system, temperature))
                        sessionKey = key
                        sessionTurns = 0
                    }
                    sessionTurns++
                    try {
                        session!!.sendMessage(user).toString()
                    } catch (ex: Exception) {
                        closeSession()
                        throw ex
                    }
                }
            } finally {
                scheduleUnload()
            }
        }
    }

    /** Same as [complete] but calls [onText] with the answer so far, so the screen can show it growing. */
    suspend fun stream(system: String, user: String, temperature: Double = 0.7, onText: (String) -> Unit): String {
        idleJob?.cancel()
        return mutex.withLock {
            try {
                withContext(Dispatchers.Default) {
                    val e = ensureEngine()
                    val sb = StringBuilder()
                    e.createConversation(conversationConfig(system, temperature)).use { c ->
                        c.sendMessageAsync(user).collect { part ->
                            sb.append(part.toString())
                            onText(sb.toString())
                        }
                    }
                    sb.toString()
                }
            } finally {
                scheduleUnload()
            }
        }
    }

    /** Asks about photos (and text). Photos are slow on this phone: expect a minute or more. */
    suspend fun askMultimodal(
        system: String,
        text: String,
        imagePaths: List<String>,
        temperature: Double = 0.3,
        onText: (String) -> Unit
    ): String {
        idleJob?.cancel()
        return mutex.withLock {
            try {
                withContext(Dispatchers.Default) {
                    val e = ensureEngine(vision = imagePaths.isNotEmpty())
                    val sb = StringBuilder()
                    val parts: List<Content> = imagePaths.map { Content.ImageFile(it) } + Content.Text(text)
                    e.createConversation(conversationConfig(system, temperature)).use { c ->
                        c.sendMessageAsync(Contents.of(*parts.toTypedArray())).collect { part ->
                            sb.append(part.toString())
                            onText(sb.toString())
                        }
                    }
                    sb.toString()
                }
            } finally {
                scheduleUnload()
            }
        }
    }

    suspend fun release() {
        idleJob?.cancel()
        mutex.withLock {
            closeSession()
            runCatching { engine?.close() }
            engine = null
            engineHasVision = false
        }
    }
}
