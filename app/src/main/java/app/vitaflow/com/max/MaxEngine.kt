package app.vitaflow.com.max

import app.vitaflow.com.ai.Attachment
import app.vitaflow.com.ai.LlmEngine
import app.vitaflow.com.ai.TavilyClient
import app.vitaflow.com.ai.WebResult
import app.vitaflow.com.data.ProfileRepository
import app.vitaflow.com.data.SettingsStore
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.LocalDateTime

/**
 * Max's brain loop for one message. The person speaks naturally; Max works out what they mean:
 * goodbye and yes/no are caught instantly, the AI reads everything else (several requests in one sentence
 * are fine), the validator checks the result, and the Action Engine does the work.
 * In "QUICK" mode, short simple commands are read by fixed rules first because that is faster.
 */
class MaxEngine(
    private val llm: LlmEngine,
    private val router: LlmRouter,
    private val actions: ActionEngine,
    private val web: TavilyClient,
    private val profiles: ProfileRepository,
    private val settings: SettingsStore,
    private val memory: MemoryManager
) {
    private val unknownText = "আমি command-টি পুরোপুরি বুঝতে পারিনি। আপনি কি আবার বলতে পারবেন?"

    /** Should the fixed rules read this message first? */
    private suspend fun rulesFirst(text: String, fast: Boolean): Boolean {
        if (fast || !llm.isReady()) return true
        return settings.understandMode.first() == "QUICK" && RuleParser.isSimple(text)
    }

    /** True when answering will need the language model (slow on this phone), so the caller can say "working on it". */
    suspend fun needsThinking(text: String, pending: PendingAction?, fast: Boolean = false): Boolean {
        if (GoodbyeDetector.isGoodbye(text)) return false
        if (pending != null && RuleParser.yesNo(text) != null) return false
        if (!llm.isReady()) return false
        return !rulesFirst(text, fast) || RuleParser.parse(text) == null
    }

    private fun hasBengali(s: String) = s.any { it in '\u0980'..'\u09FF' }

    private fun byeReply(text: String) = MaxReply(
        if (hasBengali(text)) "ঠিক আছে, পরে কথা হবে। বিদায়!" else "Okay, talk to you later. Bye!",
        endLive = true
    )

    suspend fun handle(
        accountId: Long,
        conversationId: Long,
        userName: String,
        text: String,
        pending: PendingAction?,
        fast: Boolean = false,
        onStream: (String) -> Unit
    ): MaxReply {
        if (GoodbyeDetector.isGoodbye(text)) return byeReply(text)
        if (pending != null) {
            when (RuleParser.yesNo(text)) {
                true -> return actions.confirm(accountId, pending)
                false -> return MaxReply("ঠিক আছে, বাতিল করেছি।")
                null -> Unit // a new request, the old question is dropped
            }
        }

        val now = LocalDateTime.now()
        var intents: List<MaxIntent> = emptyList()
        if (rulesFirst(text, fast)) {
            RuleParser.parse(text, now)?.let { intents = listOf(it) }
        }
        if (intents.isEmpty()) {
            if (!llm.isReady()) {
                return MaxReply(unknownText + "\n(কথা বুঝতে সেটিংস থেকে Gemma model যোগ করুন।)")
            }
            intents = try {
                val retrieved = memory.retrieve(conversationId, text)
                router.route(
                    text = if (retrieved.isBlank()) text else "$retrieved\n$text",
                    now = now, userName = userName,
                    profileLine = profiles.summaryLine(accountId),
                    detailed = settings.replyStyle.first() == "DETAILED",
                    memory = memory.contextFor(accountId, conversationId, skipLastUser = true),
                    chatKey = conversationId.toString()
                )
            } catch (e: Exception) {
                return MaxReply("Max-এর brain চালু করা যায়নি: ${e.message ?: "অজানা সমস্যা"}")
            }
        }

        // a plan request keeps the person's own words, so the AI can follow them ("রাতে ভাত না")
        val ready = intents.map { if (it is MaxIntent.CreatePlan && it.request.isBlank()) it.copy(request = text) else it }
        if (ready.size == 1) return runOne(accountId, userName, text, ready[0], onStream)

        val replies = ready.map { runOne(accountId, userName, text, it, onStream) }
        return MaxReply(
            text = replies.joinToString("\n") { it.text },
            changeIds = replies.flatMap { it.changeIds },
            sources = replies.flatMap { it.sources },
            pending = replies.firstNotNullOfOrNull { it.pending },
            endLive = replies.any { it.endLive }
        )
    }

    /** A message with photos or files: the AI looks at them together with what the person wrote. */
    suspend fun handleAttachments(
        accountId: Long,
        conversationId: Long,
        userName: String,
        text: String,
        attachments: List<Attachment>,
        onStream: (String) -> Unit
    ): MaxReply {
        if (!llm.isReady()) return MaxReply("ছবি বা ফাইল বুঝতে সেটিংস থেকে Gemma model যোগ করুন।")
        val images = attachments.filter { it.kind == "IMAGE" }.map { it.path }
        val docs = attachments.filter { it.kind == "TEXT" }.joinToString("\n\n") { a ->
            "File ${a.name}:\n" + runCatching { File(a.path).readText().take(6000) }.getOrDefault("")
        }
        val ctx = memory.contextFor(accountId, conversationId, skipLastUser = true).take(1200)
        val foodRule = if (images.isEmpty()) "" else
            "If the photo shows food or a meal, reply with JSON only: {\"type\":\"FOOD\",\"say\":\"what you see, one or two sentences\",\"items\":[{\"food\":\"<key>\",\"servings\":1}]} " +
                "using only these food keys: ${FoodDb.foods.joinToString(", ") { it.key }}. Otherwise answer in plain text. "
        val system = "You are Max, a friendly fitness assistant in the Vita Flow app. The user's name is $userName. " +
            "Reply in the user's language (Bengali, English or mixed), briefly, under 90 words. " + foodRule +
            "For photos of the body, injuries or medical reports give general information only and suggest seeing a professional. " +
            "Use the memory below only if it helps.\n$ctx"
        val prompt = buildString {
            if (docs.isNotBlank()) append(docs).append("\n\n")
            append(text.ifBlank { if (images.isNotEmpty()) "এই ছবিতে কী আছে বলো।" else "এই ফাইলটা সংক্ষেপে বলো।" })
        }
        val answer = try {
            llm.askMultimodal(system, prompt, images, 0.3, onStream).trim()
        } catch (e: Exception) {
            return MaxReply("ছবি বা ফাইল বোঝা যায়নি: ${e.message ?: "অজানা সমস্যা"}")
        }
        val o = IntentValidator.extractJson(answer)
        if (o != null && o.optString("type").uppercase() == "FOOD") {
            val arr = o.optJSONArray("items")
            val items = if (arr == null) emptyList() else (0 until minOf(arr.length(), 8)).mapNotNull { arr.optJSONObject(it) }.mapNotNull { obj ->
                val key = obj.optString("food").trim().lowercase()
                if (FoodDb.byKey(key) == null) null else key to obj.optDouble("servings", 1.0).let { s -> if (s.isNaN()) 1.0 else s }
            }
            val say = o.optString("say").trim()
            val draft = FoodDb.draftFor(items, LocalDateTime.now())
            if (draft != null) {
                val q = (if (say.isNotEmpty()) "$say\n" else "") +
                    "আমার আন্দাজ: ${draft.name} (প্রায় ${draft.kcal} kcal, আনুমানিক)। এটা কি meal হিসেবে লগ করব? (হ্যাঁ / না)"
                return MaxReply(q, pending = PendingAction(MaxIntent.AddMeal(draft), null, q))
            }
            return MaxReply(say.ifBlank { "ছবিটা দেখেছি, কিন্তু খাবারটা চিনতে পারিনি।" })
        }
        return MaxReply(answer.ifEmpty { "ছবি দেখে কিছু বলতে পারিনি।" })
    }

    private suspend fun runOne(accountId: Long, userName: String, text: String, intent: MaxIntent, onStream: (String) -> Unit): MaxReply =
        when (intent) {
            is MaxIntent.Bye -> byeReply(text)
            is MaxIntent.Unknown -> MaxReply(unknownText)
            is MaxIntent.Chat -> intent.reply?.let { MaxReply(it.trim()) } ?: chat(userName, text, onStream)
            is MaxIntent.WebSearch -> webAnswer(text, intent.query, onStream)
            else -> try {
                actions.execute(accountId, intent)
            } catch (e: Exception) {
                MaxReply("কাজটা করতে পারিনি: ${e.message ?: "অজানা সমস্যা"}")
            }
        }

    private suspend fun chat(userName: String, text: String, onStream: (String) -> Unit): MaxReply {
        if (!llm.isReady()) return MaxReply("গল্প করার জন্য সেটিংস থেকে Gemma model যোগ করুন।")
        val system = "You are Max, a friendly fitness assistant inside the Vita Flow app. The user's name is $userName. " +
            "Reply in the same language as the user (Bengali, English or mixed). Keep answers short and practical, under 80 words. " +
            "You are not a doctor: for medical problems tell the user to see a professional. Never say you changed something in the app."
        return try {
            val answer = llm.stream(system, text.take(800), temperature = 0.7, onText = onStream).trim()
            MaxReply(answer.ifEmpty { "আমি এর উত্তর দিতে পারিনি।" })
        } catch (e: Exception) {
            MaxReply("Max-এর brain চালু করা যায়নি: ${e.message ?: "অজানা সমস্যা"}")
        }
    }

    private suspend fun webAnswer(question: String, query: String, onStream: (String) -> Unit): MaxReply {
        return when (val r = web.search(query)) {
            is WebResult.Err -> MaxReply(r.message)
            is WebResult.Ok -> {
                val sources = r.hits.map { MaxSource(it.title.ifBlank { it.url }, it.url) }
                if (r.hits.isEmpty()) return MaxReply("ওয়েবে কিছু পাইনি।")
                if (!llm.isReady()) {
                    val plain = r.hits.take(3).joinToString("\n\n") { "• ${it.title}\n${it.snippet.take(200)}" }
                    return MaxReply(plain, sources = sources)
                }
                val system = "Answer the question using only the search results. Reply in the user's language, under 90 words, " +
                    "and name the source number like [1]. If the results do not answer it, say so."
                val results = r.hits.take(3).mapIndexed { i, h -> "[${i + 1}] ${h.title}: ${h.snippet.take(350)}" }.joinToString("\n")
                try {
                    val answer = llm.stream(system, "Question: ${question.take(300)}\n\nResults:\n$results", 0.3, onStream).trim()
                    MaxReply(answer, sources = sources)
                } catch (e: Exception) {
                    MaxReply(r.hits.take(3).joinToString("\n\n") { "• ${it.title}\n${it.snippet.take(200)}" }, sources = sources)
                }
            }
        }
    }
}
