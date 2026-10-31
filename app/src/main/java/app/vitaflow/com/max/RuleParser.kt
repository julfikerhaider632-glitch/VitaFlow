package app.vitaflow.com.max

import java.time.LocalDateTime

/**
 * Fast, predictable parser for everyday Bengali / English commands. It runs before the language model,
 * so most commands finish instantly, even when no model is installed.
 */
object RuleParser {
    fun yesNo(text: String): Boolean? {
        val t = text.trim().lowercase().trim('।', '.', '!', '?', ' ')
        return when (t) {
            "হ্যাঁ", "হ্যা", "হাঁ", "হা", "জি", "জি হ্যাঁ", "ঠিক আছে", "yes", "y", "ok", "okay", "ha", "hya", "haan" -> true
            "না", "নাহ", "না না", "no", "n", "na", "cancel", "বাতিল" -> false
            else -> null
        }
    }

    private val negations = setOf("না", "নাই", "নেই", "নয়", "নাহ", "কিন্তু", "but", "not", "no", "never", "dont", "don't", "didn't", "didnt", "haven't")
    private val joiners = setOf("আর", "এবং", "তারপর", "তারপরে", "আবার", "and", "then", "also", "plus")

    /**
     * True only for a short, single, positive command. Anything with "না/নি", "কিন্তু", "আর ..." or a long
     * sentence is left to the language model, because simple patterns can misread those.
     */
    fun isSimple(raw: String): Boolean {
        val tokens = raw.lowercase().split(Regex("[\\s,।.!?]+")).filter { it.isNotEmpty() }
        if (tokens.size > 9) return false
        if (tokens.any { it in negations || it in joiners }) return false
        if (tokens.any { it.length >= 4 && (it.endsWith("ইনি") || it.endsWith("য়নি") || it.endsWith("েনি")) }) return false
        return true
    }

    private val taskWord = "(?:task|টাস্ক|কাজ)"
    private val workoutWord = "(?:workout|ওয়ার্কআউট|ওয়ার্ক আউট|ব্যায়াম|exercise|এক্সারসাইজ)"

    private fun has(low: String, pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(low)

    private fun cleanTitle(s: String): String {
        var t = s
        t = t.replace(Regex("(remind me to|remind me|মনে করিয়ে দাও|মনে করিয়ে দিও|মনে করিয়ে দিন|মনে করিয়ে)", RegexOption.IGNORE_CASE), " ")
        t = t.replace(Regex("(task|টাস্ক|reminder|রিমাইন্ডার)", RegexOption.IGNORE_CASE), " ")
        t = t.replace(Regex("(যোগ|add|create|তৈরি|বানাও|বানাই|রাখো|set|করো|কর|দাও|দিন|দিও|করুন)(?!\\p{L})", RegexOption.IGNORE_CASE), " ")
        t = t.replace(Regex("(?<!\\p{L})(একটা|একটি|এর|a)(?!\\p{L})"), " ")
        t = t.replace(Regex("[,।.!?]"), " ").replace(Regex("\\s+"), " ").trim()
        t = t.replace(Regex("ওয়ার$"), "ওয়া").replace(Regex("োর$"), "ো")
        return t
    }

    fun parse(raw: String, now: LocalDateTime = LocalDateTime.now()): MaxIntent? {
        val text = TimeParser.normalize(raw.trim())
        if (text.isEmpty()) return null
        val low = text.lowercase()

        if (has(low, "ফিরিয়ে নাও|ফিরিয়ে নিন|ফিরিয়ে দাও|\\bundo\\b|আগেরটা বাতিল|আগের কাজটা বাতিল")) return MaxIntent.Undo

        // ---- workout session control
        if (has(low, "$workoutWord.*(plan|প্ল্যান|রুটিন|routine).*(বানাও|তৈরি|দাও|create|make|বানিয়ে)|(plan|প্ল্যান|রুটিন).*$workoutWord.*(বানাও|তৈরি|create|make)")) {
            return MaxIntent.CreatePlan("WORKOUT", raw)
        }
        if (has(low, "(diet|ডায়েট|খাবারের|meal).*(plan|প্ল্যান|চার্ট).*(বানাও|তৈরি|দাও|create|make|বানিয়ে)|(plan|প্ল্যান).*(diet|ডায়েট).*(বানাও|তৈরি|create|make)")) {
            return MaxIntent.CreatePlan("DIET", raw)
        }
        if (has(low, "$workoutWord.*(শেষ|finish|stop|end|complete)|(শেষ|finish).*$workoutWord")) return MaxIntent.FinishWorkout
        if (has(low, "$workoutWord.*(শুরু|start|begin)|(শুরু|start|begin).*$workoutWord")) return MaxIntent.StartWorkout(null)
        if (has(low, "(পরের|next)\\s*(exercise|এক্সারসাইজ|ব্যায়াম)|^(পরের|next)$")) return MaxIntent.NextExercise
        if (has(low, "(বিশ্রাম|rest)\\s*(বাদ|skip|শেষ)|skip rest")) return MaxIntent.SkipRest
        if (has(low, "(set|সেট)\\s*(শেষ|done|complete|কমপ্লিট|হয়েছে|হয়ে গেছে)|(শেষ|done)\\s*(set|সেট)")) {
            val reps = Regex("(\\d{1,3})\\s*(rep|বার|রেপ)").find(low)?.groupValues?.get(1)?.toIntOrNull()
            return MaxIntent.SetDone(reps)
        }

        // ---- water
        if (has(low, "পানি|water")) {
            Regex("(\\d{2,4})\\s*(?:ml|মিলিলিটার|মিলি|এমএল)").find(low)?.let {
                return MaxIntent.LogWater(it.groupValues[1].toInt())
            }
            Regex("(\\d{1,2}(?:\\.\\d)?)\\s*(?:গ্লাস|glass)").find(low)?.let {
                return MaxIntent.LogWater(((it.groupValues[1].toDoubleOrNull() ?: 1.0) * 250).toInt())
            }
        }

        // ---- weight
        Regex("(?:ওজন|weight)\\D{0,14}?(\\d{2,3}(?:\\.\\d+)?)\\s*(?:কেজি|kg)").find(low)?.let {
            val kg = it.groupValues[1].toDoubleOrNull()
            if (kg != null && kg in 20.0..300.0) return MaxIntent.LogWeight(kg)
        }

        // ---- show
        if (has(low, "$taskWord.*(দেখাও|দেখি|show|list|কী কী|কি কি)|(দেখাও|show).*$taskWord")) return MaxIntent.Show("TASKS")
        if (has(low, "(পানি|water).*(কত|how much)")) return MaxIntent.Show("WATER")
        if (has(low, "(progress|প্রগতি|অগ্রগতি).*(দেখাও|show|কেমন|কী)|এই সপ্তাহে কেমন")) return MaxIntent.Show("PROGRESS")
        if (has(low, "(protein|প্রোটিন|ক্যালরি|calorie|calories|হিসাব).*(কত|দেখাও|how much|show)")) return MaxIntent.Show("MEALS")
        if (has(low, "$workoutWord.*(দেখাও|show|list)")) return MaxIntent.Show("WORKOUTS")

        // ---- meal from the food table
        FoodDb.parseMeal(text, now)?.let { return MaxIntent.AddMeal(it) }

        // ---- add a task or reminder
        if (has(low, "(task|টাস্ক|reminder|রিমাইন্ডার)\\s*(যোগ|add|তৈরি|বানাও|বানাই|create|রাখো|set)|(যোগ|add|create).*(task|টাস্ক|reminder)|মনে করিয়ে (দাও|দিও|দিন)|remind me")) {
            val w = TimeParser.parse(text, now)
            val title = cleanTitle(w.cleaned)
            if (title.isBlank()) return null
            if (w.hinted && w.dueAt == null && !w.daily) return null // a time we could not read: let the language model try
            return MaxIntent.CreateTask(title, w.dueAt, if (w.daily) "DAILY" else null)
        }

        // ---- complete / delete a task
        Regex("^(.+?)\\s*(?:$taskWord)?\\s*(?:done|complete|সম্পন্ন|শেষ)\\s*(?:করো|কর|koro)?$", RegexOption.IGNORE_CASE).find(low)?.let {
            val q = it.groupValues[1].trim()
            if (q.isNotEmpty()) return MaxIntent.CompleteTask(q)
        }
        Regex("^(.+?)\\s*(?:$taskWord)?\\s*(?:delete|মুছে ফেলো|মুছো|মুছে দাও|ডিলিট)\\s*(?:করো|কর)?$", RegexOption.IGNORE_CASE).find(low)?.let {
            val q = it.groupValues[1].trim()
            if (q.isNotEmpty()) return MaxIntent.DeleteTask(q)
        }
        return null
    }
}
