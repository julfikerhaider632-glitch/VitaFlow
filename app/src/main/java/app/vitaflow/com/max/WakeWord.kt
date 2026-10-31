package app.vitaflow.com.max

/**
 * "Hey Max" detection on top of the speech model's text. While the app is open and Live standby is on,
 * every spoken sentence is transcribed and checked here. Returns what came after the wake word.
 */
object WakeWord {
    private val name = Regex(
        "(?:\\b(?:max|macs|mex|mac)\\b|ম্যাক্স|ম্যাক্‌স|ম্যাকস|ম্যাক|মেক্স|মাক্স)",
        RegexOption.IGNORE_CASE
    )

    /** null = no wake word; "" = just the wake word; otherwise the rest of the sentence (a command). */
    fun match(text: String): String? {
        val t = text.trim()
        val m = name.find(t) ?: return null
        if (m.range.first > 25) return null
        return t.substring(m.range.last + 1).trim().trim(',', '।', '.', '!', '?', ' ').trim()
    }
}
