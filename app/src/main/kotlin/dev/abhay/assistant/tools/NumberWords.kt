package dev.abhay.assistant.tools

/**
 * Number words, once for the app (2026-11-15): the date reader, the timer's length, the numbers
 * a request says and the rewrite's check all read them from here, so a word added for one is
 * read by all.
 */
object NumberWords {
    /** English, as requests say them. */
    val ENGLISH = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40,
        "fifty" to 50, "sixty" to 60, "ninety" to 90,
    )

    /** Hindi in Latin letters, with the spellings people type (chaar and char, chhe and che). */
    val HINDI = mapOf(
        "ek" to 1, "do" to 2, "teen" to 3, "chaar" to 4, "char" to 4, "paanch" to 5, "panch" to 5, "chhe" to 6, "chhah" to 6, "che" to 6,
        "saat" to 7, "aath" to 8, "nau" to 9, "das" to 10, "gyarah" to 11, "barah" to 12, "pandrah" to 15, "bees" to 20,
        "tees" to 30, "chalis" to 40, "paintalis" to 45, "pachaas" to 50,
    )

    /** Hindi halves, as the numbers they say: dedh is one and a half (1 and 30), aadha half (30). */
    val HINDI_HALVES = mapOf("dedh" to listOf(1, 30), "dhai" to listOf(2, 30), "aadha" to listOf(30), "aadhe" to listOf(30))

    /** "twenty five", "forty-five": a ten and a unit, 21 to 59, which no single word above is. */
    private val COMPOUND = Regex("""\b(twenty|thirty|forty|fifty)[ -](one|two|three|four|five|six|seven|eight|nine)\b""", RegexOption.IGNORE_CASE)

    /** [text] with its compound number words as digits ("twenty five minutes" → "25 minutes"), for readers that take one word at a time. */
    fun compoundsAsDigits(text: String): String =
        text.replace(COMPOUND) { (ENGLISH.getValue(it.groupValues[1].lowercase()) + ENGLISH.getValue(it.groupValues[2].lowercase())).toString() }

    /** The words for 1 to 12, the hours of a clock. */
    val ENGLISH_HOURS = ENGLISH.filterValues { it in 1..12 }
    val HINDI_HOURS = HINDI.filterValues { it in 1..12 }
}
