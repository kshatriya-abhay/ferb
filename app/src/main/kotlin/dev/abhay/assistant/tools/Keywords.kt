package dev.abhay.assistant.tools

/** A tool's keywords ([Tool.keywords], or the user's own) as one matcher for requests. */
object Keywords {
    /** Longer than this, a "keyword" is a sentence, which a request would rarely contain. */
    const val MAX_LENGTH = 40

    /**
     * A keyword as stored: lower case, single spaces, trimmed; null if it's empty or too long.
     * People type "Wi-Fi " or "flash  light"; both should mean the same word as the default.
     */
    fun clean(word: String): String? =
        word.trim().lowercase().replace(Regex("""\s+"""), " ").takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH }

    /**
     * Matches a request containing any of [words] as whole words, ignoring case, also with an "s"
     * on the end ("alarm" finds "alarms"); a phrase's words may be split by any spacing. Null when
     * there are no words: such a tool is mentioned by no request. Written so Python's `re` reads
     * the same pattern (tool-check's router and scorer), so no `\Q…\E` quoting.
     */
    fun regex(words: List<String>, ruledOutBy: String? = null): Regex? {
        val alternatives = words.mapNotNull(::clean).distinct().ifEmpty { return null }
            .joinToString("|") { word -> word.split(' ').joinToString("""\s+""") { part -> part.replace(SPECIAL) { """\${it.value}""" } } }
        val words = """(?<!$WORD)(?:$alternatives)s?(?!$WORD)"""
        // Ruled out: [ruledOutBy] tried at the request's start, so a request it matches has no match.
        return Regex(if (ruledOutBy == null) words else """^(?!$ruledOutBy)[\s\S]*?$words""", RegexOption.IGNORE_CASE)
    }

    /**
     * What continues a word, spelled out: each engine's `\w` differs (ASCII on the JVM, Unicode in
     * Python, which still ends a word at a Devanagari vowel sign). Letters and digits in ASCII and
     * Latin-1 to Latin Extended-B, the underscore, and Devanagari's letters and signs (not its danda).
     */
    private const val WORD = """[0-9A-Za-z_\u00C0-\u024F\u0900-\u0963\u0966-\u097F]"""

    /** ASCII punctuation, quoted with a backslash, which both Java and Python read as the character. */
    private val SPECIAL = Regex("""[!-/:-@\[-`{-~]""")
}
