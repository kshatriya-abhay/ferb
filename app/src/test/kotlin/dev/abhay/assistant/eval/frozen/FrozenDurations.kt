package dev.abhay.assistant.eval.frozen

/**
 * Durations.parse as it was at 2cbceb9 (A1's freeze; the same at b542df1, A0's), with SaidNumbers'
 * number words as they were then: the frozen readers' timer lengths. Frozen: never edit. The live
 * Durations changed after the freeze (b6139ed, "a quarter of an hour", "1 hour 30"), and the frozen
 * readers, which called it, no longer read as they did in the blind round (review P2-169).
 */
object FrozenDurations {
    private val UNITS = mapOf(
        "h" to 3600, "hr" to 3600, "hrs" to 3600, "hour" to 3600, "hours" to 3600,
        "m" to 60, "min" to 60, "mins" to 60, "minute" to 60, "minutes" to 60,
        "s" to 1, "sec" to 1, "secs" to 1, "second" to 1, "seconds" to 1,
    )
    private val PART = Regex("""(\d+(?:\.\d+)?)\s*([a-z]+)""")
    private val WORDS = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20,
        "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "ninety" to 90,
    )
    private val WORD = Regex("""\b(${WORDS.keys.joinToString("|")})\b""")

    fun parse(text: String): Int? {
        val t = text.trim().lowercase()
            .replace(Regex("""\band a half\b"""), " andahalf ")
            .replace(Regex("""\bhalf an? (hour|minute|second)\b""")) { "0.5 ${it.groupValues[1]}" }
            .replace(" and ", " ")
            .replace(WORD) { WORDS.getValue(it.value).toString() }
            .replace(Regex("""(\d+(?:\.\d+)?)\s+andahalf\s+([a-z]+)""")) { "${it.groupValues[1].toDouble() + 0.5} ${it.groupValues[2]}" }
            .replace(Regex("""(\d+(?:\.\d+)?)\s*([a-z]+)\s+andahalf""")) { "${it.groupValues[1].toDouble() + 0.5} ${it.groupValues[2]}" }
            .trim()
        Regex("""(\d+):(\d{2})""").matchEntire(t)?.let { return it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        val parts = PART.findAll(t).toList()
        if (parts.isEmpty() || parts.any { it.groupValues[2] !in UNITS }) return null
        return parts.sumOf { it.groupValues[1].toDouble() * UNITS.getValue(it.groupValues[2]) }.let { Math.round(it).toInt() }
    }
}
