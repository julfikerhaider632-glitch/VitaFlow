package app.vitaflow.com.max

import app.vitaflow.com.ai.LlmEngine
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Reads what the person said, in their own words, and turns it into one or more commands (JSON).
 * The long instruction never changes, so the model keeps it ready between messages; the date, the user's
 * name and profile travel with each message instead.
 */
class LlmRouter(private val llm: LlmEngine) {
    suspend fun route(
        text: String,
        now: LocalDateTime,
        userName: String,
        profileLine: String,
        detailed: Boolean,
        memory: String,
        chatKey: String
    ): List<MaxIntent> {
        val day = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val words = if (detailed) "max 80 words" else "max 35 words"
        val profile = if (profileLine.isBlank()) "" else "; profile: $profileLine"
        val context = "[Today ${now.toLocalDate()} ($day) ${now.toLocalTime().withSecond(0).withNano(0)}; tomorrow ${now.toLocalDate().plusDays(1)}; " +
            "user: $userName$profile; chat reply length: $words]"
        val system = if (memory.isBlank()) SYSTEM else SYSTEM + "\n\nMemory (use it to understand what the user refers to and to talk naturally; never read it out):\n" + memory
        val raw = llm.completeSession("router-$chatKey", system, "$context\n${text.take(700)}", temperature = 0.1)
        return IntentValidator.parseMany(raw, now)
    }

    private companion object {
        val SYSTEM = """
You are Max, the assistant in the Vita Flow fitness app. The user speaks naturally in Bengali, English or both. Each message starts with a [context] line.
Understand the meaning (including negations, corrections and several requests in one sentence). Reply with ONE JSON object only.
"route": COMMAND (add, change, log or start something in the app), WEB (needs fresh internet info), BYE (goodbye or stop listening) or CHAT (anything else).
CHAT: add "reply", a short answer in the user's language, within the length in the context line. You are not a doctor: for medical problems say to see a professional.
COMMAND: add "intent", or "actions":[...] when there are several requests. Intents: CREATE_TASK, COMPLETE_TASK, DELETE_TASK, UPDATE_TASK, LOG_WATER, LOG_WEIGHT, ADD_MEAL, CREATE_WORKOUT, UPDATE_EXERCISE, START_WORKOUT, CREATE_PLAN, SHOW.
Fields: title, query, date (YYYY-MM-DD), time (HH:mm), repeat (DAILY), ml, kg, name, meal_type (BREAKFAST, LUNCH, DINNER, SNACK), kcal, protein, carbs, fat, planned, exercises [{name, sets, reps, seconds, rest, how_to}], sets, reps, seconds, weight_kg, what (TASKS, WATER, MEALS, WORKOUTS, PROGRESS; for CREATE_PLAN: WORKOUT or DIET), request, search.
Examples:
[Today 2026-01-01 (Thursday) 10:00; tomorrow 2026-01-02] কাল সকাল সাতটায় পানি খাওয়ার task যোগ করো -> {"route":"COMMAND","intent":"CREATE_TASK","title":"পানি খাওয়া","date":"2026-01-02","time":"07:00"}
আজ ৫০০ মিলি পানি খেয়েছি আর রাত দশটায় ঘুমের reminder দাও -> {"route":"COMMAND","actions":[{"intent":"LOG_WATER","ml":500},{"intent":"CREATE_TASK","title":"ঘুমানো","date":"2026-01-01","time":"22:00"}]}
আমার push-ups ২০টা করো -> {"route":"COMMAND","intent":"UPDATE_EXERCISE","name":"Push-ups","reps":20}
ভাত খেয়েছি কিন্তু ডাল খাইনি -> {"route":"COMMAND","intent":"ADD_MEAL","name":"ভাত","meal_type":"LUNCH","planned":false}
আজকের ডলারের রেট কত -> {"route":"WEB","search":"USD to BDT rate today"}
তুমি কেমন আছো -> {"route":"CHAT","reply":"আমি ভালো আছি! আপনার আজ কেমন যাচ্ছে?"}
এখন থামো, পরে কথা হবে -> {"route":"BYE"}
""".trimIndent()
    }
}
