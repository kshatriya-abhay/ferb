package dev.abhay.assistant.tools

/**
 * The content fix's mapping (0.2, arm H2, frozen 2026-11-06 in build/content/frozen-2/content_map.py):
 * the words a tool writes down or sends (a message, a note, a reminder's or an event's subject,
 * list items) are taken from the words as said, not from the rewrite, which translates and rewords
 * them. The rewrite quotes the content in its command; Needle 3 fills the content argument from
 * that command; both are mapped back to the span of the words as said that they match best, across
 * scripts (Hindi in Devanagari or in Latin letters), and that span is the argument. When neither
 * maps, Ferb asks first ([contentOf]'s mapped is false).
 *
 * Matching is on consonant skeletons ([skeleton]) of ICU's Any-Latin transliteration, which is used
 * only to compare: the argument keeps the words in the script they were said in. [latin] is ICU's
 * "Any-Latin; Latin-ASCII" (android.icu on the phone; ICU4J in tests, ICU 78 as the laptop's uconv
 * that the frozen code used).
 */
class ContentMap(private val latin: (String) -> String) {
    /**
     * Spans already written in Latin letters: [spanOf] tries each of a request's n(n+1)/2 spans, and
     * one request reads many twice. Bounded, least recently used out first, so a view model's flow
     * doesn't keep every span of every request it ever answered (review P2-28).
     */
    private val latinCache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > LATIN_CACHE_SIZE
    }

    /** [s] in Latin letters, ASCII, as uconv -x "Any-Latin; Latin-ASCII" writes it, trimmed. */
    fun latinOf(s: String): String = synchronized(latinCache) { latinCache.getOrPut(s) { latin(s).trim() } }

    /**
     * The consonant skeleton of [s] in Latin letters: lower case, letters and digits only; ph is f,
     * aspiration dropped (ch/c, kh/k, th/t), vowels dropped (spellings differ most there), w is v,
     * z is j, m is n (ICU writes anusvara and chandrabindu as m), no spaces.
     */
    fun skeleton(s: String): String {
        var t = latinOf(s).lowercase().replace(Regex("[^a-z0-9 ]"), "")
        t = t.replace("ph", "f")
        t = t.replace(Regex("([bcdgjklmnprstvz])h"), "$1")
        t = t.replace(Regex("[aeiouy]"), "")
        t = t.replace("w", "v").replace("z", "j").replace("m", "n")
        return t.replace(" ", "")
    }

    /** A span of the words as said: words [from] until [until], matched with [score] (0 to 1). */
    data class Span(val from: Int, val until: Int, val score: Double)

    /**
     * The words of [said] that [text] matches best, across scripts. Of equal matches, the one with
     * as many words as [text], then the shortest: a word with no consonants ("I", "a") adds nothing
     * to a skeleton, so without the first rule "I am late" came back as "am late" (2026-11-15).
     */
    fun spanOf(text: String, said: String): Span? {
        val words = words(said)
        val ts = skeleton(text)
        if (ts.isEmpty()) return null
        val n = words(text).size
        fun off(s: Span) = kotlin.math.abs(s.until - s.from - n)
        var best: Span? = null
        for (i in words.indices) {
            for (j in i + 1..words.size) {
                val r = Similarity.ratio(ts, skeleton(words.subList(i, j).joinToString(" ")))
                val b = best?.score ?: 0.0
                val s = Span(i, j, r)
                val tie = best != null && kotlin.math.abs(r - b) < 1e-9 &&
                    (off(s) < off(best) || (off(s) == off(best) && j - i < best.until - best.from))
                if (r > b + 1e-9 || tie) best = s
            }
        }
        return best
    }

    /** How much of the shorter of [a] and [b] the other covers (their skeletons). */
    fun overlap(a: String, b: String): Double {
        val sa = skeleton(a)
        val sb = skeleton(b)
        if (sa.isEmpty() || sb.isEmpty()) return 0.0
        return Similarity.matches(sa, sb).toDouble() / minOf(sa.length, sb.length)
    }

    /** The content, as said ([text]), and whether it was found there ([mapped]). */
    data class Content(val text: String?, val mapped: Boolean)

    /**
     * The content as said: Needle 3's content argument [arg] and the command's [quotes], mapped
     * back to [said]. Quotes that are another argument ([others]: the recipient, the list) are
     * skipped; the quote that overlaps [arg] most is chosen; when both map and sit within two words,
     * their union is taken (Needle 3 trimmed the quote, or the quote is part of the argument).
     */
    fun contentOf(arg: String?, quotes: List<String>, others: Map<String, Any?>, said: String): Content {
        val a = (arg ?: "").replace('"', ' ').trim()
        val other = others.values.filter { truthy(it) }.map { it.toString() }
        val kept = quotes.filter { q -> other.none { o -> overlap(q, o) >= 0.8 && skeleton(q).length <= skeleton(o).length + 2 } }
        // The first of the best, as Python's max.
        var q: String? = null
        var qBest = Double.NEGATIVE_INFINITY
        for (k in kept) {
            val key = if (a.isNotEmpty()) overlap(k, a) else k.length.toDouble()
            if (key > qBest) { q = k; qBest = key }
        }
        val words = words(said)
        val spans = listOfNotNull(if (a.isNotEmpty()) spanOf(a, said) else null, q?.let { spanOf(it, said) }).filter { it.score >= OK }
        if (spans.isEmpty()) return Content(q ?: a.ifEmpty { null }, false)
        fun text(from: Int, until: Int) = edges(words.subList(from, until).joinToString(" "))
        if (spans.size == 2) {
            val (s1, s2) = spans
            if (maxOf(s1.from, s2.from) <= minOf(s1.until, s2.until) + 2) return Content(text(minOf(s1.from, s2.from), maxOf(s1.until, s2.until)), true)
        }
        val best = spans.reduce { x, y -> if (y.score > x.score) y else x }
        return Content(text(best.from, best.until), true)
    }

    /** [command] as Needle 3 sees it: its quoted parts in Latin letters (Needle 3 is an English model). */
    fun forNeedle(command: String): String = QUOTE.replace(command) { m -> quote(m) { latinOf(it) } }

    companion object {
        /** Enough for the spans of a long request (60 words: 1,830). */
        private const val LATIN_CACHE_SIZE = 2048

        /** A span maps when it matches at least this well. */
        const val OK = 0.75

        /** A sentence's punctuation at the kept words' ends: English's and Hindi's (danda, double danda). */
        private val STRIP = charArrayOf(' ', ',', '.', ';', ':', '!', '?', '।', '॥')

        /**
         * The kept words without sentence punctuation at their ends, and without a quote mark at an end
         * that nothing in them closes: "say \"hi\"" keeps both, "\"buy milk" loses its lone one. (The
         * frozen H2 map trimmed ASCII only, one mark at a time: review P2-26.)
         */
        private fun edges(text: String): String {
            var t = text.trim(*STRIP)
            for (q in listOf('"', '\'')) if (t.count { it == q } % 2 == 1) t = (if (t.startsWith(q)) t.drop(1) else if (t.endsWith(q)) t.dropLast(1) else t).trim(*STRIP)
            if (t.startsWith('“') && '”' !in t) t = t.drop(1).trim(*STRIP)
            if (t.endsWith('”') && '“' !in t) t = t.dropLast(1).trim(*STRIP)
            return t
        }

        /**
         * The argument each tool writes down or sends, the content kept as said. Other tools carry no
         * content (their arguments are times, names, places and apps).
         */
        val CONTENT = mapOf(
            SendText.name to "message", CreateNote.name to "text", AddReminder.name to "text",
            AddToList.name to "items", AddEvent.name to "title", CheckOff.name to "item",
        )

        /** A quoted part of a command, "…" or “…”: the one rule [quoted], [masked] and [forNeedle] read. */
        private val QUOTE = Regex("\"([^\"]*)\"|“([^”]*)”")

        /** [m]'s quote, its text changed by [change], in the quote marks it came in. */
        private fun quote(m: MatchResult, change: (String) -> String): String =
            if (m.groups[1] != null) "\"" + change(m.groupValues[1]) + "\"" else "“" + change(m.groupValues[2]) + "”"

        /** The command's quoted parts ("…" or “…”). */
        fun quoted(command: String?): List<String> =
            QUOTE.findAll(command.orEmpty()).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.filter { it.isNotEmpty() }.toList()

        /** [command] with its quoted parts emptied, for the tool router: the content's words name no tool. */
        fun masked(command: String): String = QUOTE.replace(command) { m -> quote(m) { "" } }

        /** The words, split on white space as Python's str.split(). */
        fun words(s: String): List<String> = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

        /** Python's truthiness, for argument values: null, "", 0, false and empty lists are none. */
        private fun truthy(v: Any?): Boolean = when (v) {
            null -> false
            is String -> v.isNotEmpty()
            is Boolean -> v
            is Number -> v.toDouble() != 0.0
            is Collection<*> -> v.isNotEmpty()
            is Map<*, *> -> v.isNotEmpty()
            else -> true
        }

        /** On the phone: Android's ICU. */
        fun android(): ContentMap {
            val t by lazy { android.icu.text.Transliterator.getInstance("Any-Latin; Latin-ASCII") }
            return ContentMap { t.transliterate(it) }
        }
    }
}

/**
 * Python's difflib.SequenceMatcher on two strings, without junk (the frozen code's matching; autojunk
 * only starts at 200 characters, longer than any skeleton here): the matching blocks, found as
 * difflib finds them, and the ratio 2M / T.
 */
object Similarity {
    fun ratio(a: String, b: String): Double {
        val t = a.length + b.length
        return if (t == 0) 1.0 else 2.0 * matches(a, b) / t
    }

    /** The characters in matching blocks. */
    fun matches(a: String, b: String): Int {
        val b2j = HashMap<Char, MutableList<Int>>()
        b.forEachIndexed { j, c -> b2j.getOrPut(c) { mutableListOf() }.add(j) }
        var total = 0
        val queue = ArrayDeque<IntArray>()
        queue.add(intArrayOf(0, a.length, 0, b.length))
        while (queue.isNotEmpty()) {
            val (alo, ahi, blo, bhi) = queue.removeLast()
            val (i, j, k) = longest(a, b2j, alo, ahi, blo, bhi)
            if (k > 0) {
                total += k
                if (alo < i && blo < j) queue.add(intArrayOf(alo, i, blo, j))
                if (i + k < ahi && j + k < bhi) queue.add(intArrayOf(i + k, ahi, j + k, bhi))
            }
        }
        return total
    }

    /** find_longest_match: the longest block, the earliest in [a], then in b. */
    private fun longest(a: String, b2j: Map<Char, List<Int>>, alo: Int, ahi: Int, blo: Int, bhi: Int): Triple<Int, Int, Int> {
        var besti = alo
        var bestj = blo
        var bestsize = 0
        var j2len = HashMap<Int, Int>()
        for (i in alo until ahi) {
            val next = HashMap<Int, Int>()
            for (j in b2j[a[i]].orEmpty()) {
                if (j < blo) continue
                if (j >= bhi) break
                val k = (j2len[j - 1] ?: 0) + 1
                next[j] = k
                if (k > bestsize) { besti = i - k + 1; bestj = j - k + 1; bestsize = k }
            }
            j2len = next
        }
        return Triple(besti, bestj, bestsize)
    }
}
