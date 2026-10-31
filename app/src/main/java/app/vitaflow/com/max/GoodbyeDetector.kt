package app.vitaflow.com.max

/**
 * Fast check for "bye" / "এখন থামো" style sentences. The language model also understands other ways of
 * saying goodbye (route BYE), this just makes the common ones instant.
 */
object GoodbyeDetector {
    private val strong = Regex(
        "(\\bbye\\b|goodbye|good bye|see you|see ya|take care|talk to you later|that'?s all|that is all|" +
            "বিদায়|বাই|পরে কথা হবে|পরে দেখা হবে|আজকের মতো এটুকুই|আজ এটুকুই|আর কিছু লাগবে না|আর কিছু না)",
        RegexOption.IGNORE_CASE
    )
    private val weak = Regex(
        "(\\bstop\\b|stop listening|go to sleep|sleep now|turn off|shut ?down|be quiet|\\bquiet\\b|" +
            "থামো|থামুন|থাম\\b|থেমে যাও|বন্ধ করো|বন্ধ কর|বন্ধ হও|চুপ|ঘুমাও|ঘুমিয়ে পড়ো|অফ করো|আসি)",
        RegexOption.IGNORE_CASE
    )
    private val blockers = Regex("(task|টাস্ক|workout|ওয়ার্কআউট|timer|টাইমার|meal|পানি|water)", RegexOption.IGNORE_CASE)

    fun isGoodbye(text: String): Boolean {
        val t = text.lowercase().replace(Regex("[,।.!?]"), " ").trim()
        val words = t.split(Regex("\\s+")).count { it.isNotEmpty() }
        if (words == 0) return false
        if (strong.containsMatchIn(t) && words <= 8) return true
        return weak.containsMatchIn(t) && words <= 4 && !blockers.containsMatchIn(t)
    }
}
