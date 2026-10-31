package app.vitaflow.com.max

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class ParsedWhen(
    val dueAt: Long?,
    val daily: Boolean,
    /** The sentence with the date and time words taken out (what is left is the task itself). */
    val cleaned: String,
    /** true if the sentence mentions any day or time at all */
    val hinted: Boolean
)

/** Understands Bengali and English dates and times without any AI: "কাল সকাল সাতটায়", "রাত ১০:৩০", "প্রতিদিন". */
object TimeParser {
    fun ascii(s: String): String = s.map { if (it in '\u09E6'..'\u09EF') '0' + (it - '\u09E6') else it }.joinToString("")

    private val numberWords = listOf(
        "এগারো" to 11, "বারো" to 12, "তেরো" to 13, "চৌদ্দ" to 14, "পনেরো" to 15, "পনের" to 15, "ষোল" to 16,
        "সতেরো" to 17, "আঠারো" to 18, "উনিশ" to 19, "বিশ" to 20, "পঁচিশ" to 25, "ত্রিশ" to 30, "চল্লিশ" to 40,
        "পঞ্চাশ" to 50, "ষাট" to 60,
        "দুইটি" to 2, "দুইটা" to 2, "দুই" to 2, "দুটি" to 2, "দুটো" to 2, "দুটা" to 2,
        "এক" to 1, "তিন" to 3, "চার" to 4, "পাঁচ" to 5, "ছয়" to 6, "সাত" to 7, "আট" to 8, "নয়" to 9, "দশ" to 10
    )
    private val suffix =
        "(?=(?:টা|টায়|টার|টি|টো|শো|শ|\\s*(?:মিলি|মিলিলিটার|ml|কেজি|কাপ|বাটি|গ্লাস|প্লেট|পিস|টুকরা|টুকরো|মিনিট|ঘণ্টা|ঘন্টা|সেকেন্ড|rep|set|সেট|বার|রেপ)))"

    /** Bengali digits and number words become ASCII digits ("পাঁচশো" -> 500, "সাতটায়" -> 7টায়). */
    fun normalize(raw: String): String {
        var t = ascii(raw)
        t = t.replace(Regex("আড়াই\\s*(?:শো|শ)"), "250")
        t = t.replace(Regex("দেড়\\s*(?:শো|শ)"), "150")
        t = t.replace(Regex("আড়াই"), "2.5").replace(Regex("দেড়"), "1.5")
        t = t.replace(Regex("(এক|দুই|তিন|চার|পাঁচ|ছয়|সাত|আট|নয়|দশ)\\s*(?:শো|শ)(?!\\p{L})")) { m ->
            (numberWords.first { it.first == m.groupValues[1] }.second * 100).toString()
        }
        t = t.replace(Regex("এক\\s*হাজার"), "1000")
        for ((w, n) in numberWords) {
            t = t.replace(Regex("(?<!\\p{L})$w$suffix"), n.toString())
        }
        return t
    }

    private val weekdays = mapOf(
        "সোমবার" to DayOfWeek.MONDAY, "মঙ্গলবার" to DayOfWeek.TUESDAY, "বুধবার" to DayOfWeek.WEDNESDAY,
        "বৃহস্পতিবার" to DayOfWeek.THURSDAY, "শুক্রবার" to DayOfWeek.FRIDAY, "শনিবার" to DayOfWeek.SATURDAY,
        "রবিবার" to DayOfWeek.SUNDAY, "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "sunday" to DayOfWeek.SUNDAY
    )

    private val removal = Regex(
        "(প্রতিদিন|প্রতি দিন|রোজ|every ?day|daily|আগামীকাল|আগামী|কাল|আজকে|আজ|পরশু|tomorrow|today|" +
            "সকালে|সকাল|দুপুরে|দুপুর|বিকালে|বিকাল|বিকেলে|বিকেল|সন্ধ্যায়|সন্ধ্যা|রাতে|রাত|" +
            "সোমবার|মঙ্গলবার|বুধবার|বৃহস্পতিবার|শুক্রবার|শনিবার|রবিবার|monday|tuesday|wednesday|thursday|friday|saturday|sunday|" +
            "(?:সাড়ে|সওয়া|পৌনে)\\s*\\d{1,2}\\s*(?:টা|টায়|টার)?|" +
            "\\d{1,2}(?::|\\.)\\d{2}\\s*(?:am|pm)?|\\d{1,2}\\s*(?:টায়|টার|টা|am|pm)|\\bat\\b)",
        RegexOption.IGNORE_CASE
    )

    private fun hourFor(h: Int, period: String?, ampm: String?): Int {
        if (ampm == "am") return if (h == 12) 0 else h
        if (ampm == "pm") return if (h < 12) h + 12 else h
        return when (period) {
            "morning" -> h
            "noon" -> if (h in 1..4) h + 12 else h
            "afternoon", "evening" -> if (h < 12) h + 12 else h
            "night" -> when {
                h == 12 -> 0
                h in 6..11 -> h + 12
                else -> h
            }
            else -> when (h) {
                in 1..4 -> h + 12
                else -> h
            }
        }
    }

    fun parse(raw: String, now: LocalDateTime): ParsedWhen {
        val t = normalize(raw)
        val low = t.lowercase()
        val daily = Regex("প্রতিদিন|প্রতি দিন|রোজ|every ?day|daily").containsMatchIn(low)

        var date: LocalDate? = null
        var fromWeekday = false
        val today = now.toLocalDate()
        when {
            Regex("পরশু").containsMatchIn(low) -> date = today.plusDays(2)
            Regex("আগামীকাল|কাল|tomorrow").containsMatchIn(low) -> date = today.plusDays(1)
            Regex("আজকে|আজ|today").containsMatchIn(low) -> date = today
            else -> weekdays.entries.firstOrNull { low.contains(it.key) }?.let {
                date = today.with(TemporalAdjusters.nextOrSame(it.value))
                fromWeekday = true
            }
        }

        val period = when {
            Regex("সকাল|morning").containsMatchIn(low) -> "morning"
            Regex("দুপুর|noon").containsMatchIn(low) -> "noon"
            Regex("বিকাল|বিকেল|afternoon").containsMatchIn(low) -> "afternoon"
            Regex("সন্ধ্যা|evening").containsMatchIn(low) -> "evening"
            Regex("রাত|night").containsMatchIn(low) -> "night"
            else -> null
        }

        var time: LocalTime? = null
        Regex("(\\d{1,2})[:.](\\d{2})\\s*(am|pm)?").find(low)?.let {
            val h = it.groupValues[1].toInt()
            val m = it.groupValues[2].toInt()
            if (h in 0..23 && m in 0..59) {
                val hh = if (it.groupValues[3].isNotEmpty() || h > 12) hourFor(h, null, it.groupValues[3].ifEmpty { null }) else hourFor(h, period, null)
                time = LocalTime.of(hh % 24, m)
            }
        }
        if (time == null) {
            Regex("(সাড়ে|সওয়া|পৌনে)\\s*(\\d{1,2})").find(low)?.let {
                val n = it.groupValues[2].toInt()
                if (n in 1..12) {
                    val (h, m) = when (it.groupValues[1]) {
                        "সাড়ে" -> n to 30
                        "সওয়া" -> n to 15
                        else -> (if (n == 1) 12 else n - 1) to 45
                    }
                    time = LocalTime.of(hourFor(h, period, null) % 24, m)
                }
            }
        }
        if (time == null) {
            Regex("(\\d{1,2})\\s*(টায়|টার|টা|am|pm)").find(low)?.let {
                val h = it.groupValues[1].toInt()
                val ampm = it.groupValues[2].takeIf { s -> s == "am" || s == "pm" }
                if (h in 0..23) time = LocalTime.of(hourFor(h, period, ampm) % 24, 0)
            }
        }

        val hinted = daily || date != null || time != null || period != null
        var due: Long? = null
        if (time != null || date != null || daily) {
            val base = date ?: today
            val at = time ?: LocalTime.of(9, 0)
            var dt = base.atTime(at)
            if (!dt.isAfter(now)) {
                dt = if (fromWeekday) dt.plusWeeks(1) else if (date == null || daily) dt.plusDays(1) else dt
            }
            if (dt.isAfter(now)) due = dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        val cleaned = t.replace(removal, " ").replace(Regex("\\s+"), " ").trim()
        return ParsedWhen(due, daily, cleaned, hinted)
    }
}
