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
        val words = """(?<!\w)(?:$alternatives)s?(?!\w)"""
        // Ruled out: [ruledOutBy] tried at the request's start, so a request it matches has no match.
        return Regex(if (ruledOutBy == null) words else """^(?!$ruledOutBy)[\s\S]*?$words""", RegexOption.IGNORE_CASE)
    }

    /** ASCII punctuation, quoted with a backslash, which both Java and Python read as the character. */
    private val SPECIAL = Regex("""[!-/:-@\[-`{-~]""")
}
