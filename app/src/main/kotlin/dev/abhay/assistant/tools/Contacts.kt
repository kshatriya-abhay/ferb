package dev.abhay.assistant.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.icu.text.Transliterator
import android.provider.ContactsContract.CommonDataKinds.Phone

/**
 * A contact with a phone number. [latin] is the name in Latin letters (a name saved as "माँ"
 * becomes "ma"), for matching what was typed; [kind] is the number's label (Mobile, Work…).
 */
data class Contact(val name: String, val number: String, val kind: String? = null, val starred: Boolean = false, val latin: String = name)

/**
 * The phone's contacts, read on every lookup (so new ones are found), or null without the
 * contacts permission. Matching happens here, not in the model, as for apps ([InstalledApps]):
 * Needle 3 passes the name as the request says it.
 */
class ContactBook(private val load: () -> List<Contact>?) {
    /** The contacts that best match [query], best first, at most [limit]; null without the permission. */
    fun find(query: String, limit: Int = 3): List<Contact>? = load()?.let { ContactMatch.rank(query, it, limit) }

    companion object {
        val NONE = ContactBook { null }

        fun granted(context: Context) = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

        fun from(context: Context): ContactBook {
            val latin by lazy { Transliterator.getInstance("Any-Latin; Latin-ASCII") }
            return ContactBook {
                if (!granted(context)) return@ContactBook null
                val columns = arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.TYPE, Phone.LABEL, Phone.STARRED, Phone.IS_SUPER_PRIMARY)
                val rows = mutableListOf<Pair<Long, Triple<Contact, Int, Boolean>>>()
                context.contentResolver.query(Phone.CONTENT_URI, columns, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        val number = c.getString(2) ?: continue
                        val type = c.getInt(3)
                        val kind = Phone.getTypeLabel(context.resources, type, c.getString(4)).toString()
                        rows += c.getLong(0) to Triple(Contact(name, number, kind, c.getInt(5) == 1, latin.transliterate(name)), type, c.getInt(6) == 1)
                    }
                }
                // One number per contact: the one set as default, else a mobile, else the first.
                rows.groupBy({ it.first }, { it.second }).values.map { numbers ->
                    (numbers.firstOrNull { it.third } ?: numbers.firstOrNull { it.second == Phone.TYPE_MOBILE } ?: numbers.first()).first
                }
            }
        }
    }
}

/**
 * Finds contacts by a typed name: by spelling, by sound (Indian names are spelt many ways:
 * "Shreyansh", "Shriyans"), and by relation ("mum" finds "Maa" or "Mummy").
 */
object ContactMatch {
    /** Below this, a contact isn't offered at all. */
    const val MIN_SCORE = 0.55

    fun rank(query: String, contacts: List<Contact>, limit: Int = 3): List<Contact> {
        val words = words(query).filter { it !in FILLER }
        if (words.isEmpty()) return emptyList()
        return contacts.map { it to score(words, words(it.latin) + words(it.name)) }
            .filter { it.second >= MIN_SCORE }
            .sortedWith(compareByDescending<Pair<Contact, Double>> { it.second }.thenByDescending { it.first.starred }.thenBy { it.first.name.length })
            .distinctBy { it.first.name to it.first.number }
            .take(limit)
            .map { it.first }
    }

    /** How well the [query] words name a contact called [name] (its words), 0..1. */
    fun score(query: List<String>, name: List<String>): Double {
        if (name.isEmpty()) return 0.0
        // "mum" against "Maa", "Mummy", "Mom"…
        val relation = RELATIONS.firstOrNull { group -> query.any { it in group } }
        if (relation != null && name.any { it in relation }) return 1.0
        val perWord = query.map { q -> name.maxOf { word(q, it) } }.average()
        // "shrey ansh" against "Shreyansh", or the other way round.
        val joined = word(query.joinToString(""), name.joinToString(""))
        return maxOf(perWord, joined * 0.95)
    }

    /**
     * How alike two words are, 0..1: equal, a prefix of 3+ letters, or close in spelling or in sound.
     * Names typed or heard by ear keep their first sound ("Shriyans" for Shreyansh), so words that
     * start with different sounds count by spelling only, and for less: "shriansh" sounds 0.8 like
     * "priyansh" and offered Priyansh, the wrong person, on the audio bench (2026-10-12); offering
     * no one beats offering a stranger.
     */
    fun word(a: String, b: String): Double = when {
        a == b -> 1.0
        a.length >= 3 && b.startsWith(a) -> 0.9
        else -> {
            val (sa, sb) = sound(a) to sound(b)
            if (sa.firstOrNull() == sb.firstOrNull()) maxOf(similarity(a, b), 0.95 * similarity(sa, sb)) else 0.8 * similarity(a, b)
        }
    }

    /**
     * A rough sound of a word, for names typed by ear: aspirates lose their h ("bh" → "b", "sh" →
     * "s"), long vowels shorten ("ee" → "i"), w/v and z/j merge, doubled letters collapse.
     */
    fun sound(word: String): String {
        var s = word
        for ((from, to) in SOUNDS) s = s.replace(from, to)
        s = s.first() + s.drop(1).replace("h", "")
        return s.fold(StringBuilder()) { out, c -> if (out.lastOrNull() != c) out.append(c); out }.toString()
    }

    private fun similarity(a: String, b: String): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0 else 1.0 - distance(a, b).toDouble() / maxOf(a.length, b.length)

    /** Levenshtein distance. */
    private fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            previous = current
        }
        return previous[b.length]
    }

    private fun words(text: String) = text.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }

    // Needle 3 sometimes keeps the verb: "phone dad" came back as the name "phone dad".
    private val FILLER = setOf("my", "the", "to", "a", "an", "please", "call", "phone", "dial", "ring", "text", "message")

    private val SOUNDS = listOf(
        "ph" to "f", "bh" to "b", "dh" to "d", "th" to "t", "kh" to "k", "gh" to "g", "sh" to "s", "ch" to "c", "jh" to "j",
        "ck" to "k", "q" to "k", "x" to "ks", "z" to "j", "w" to "v", "y" to "i", "ee" to "i", "oo" to "u", "aa" to "a",
    )

    /** Words for the same person, in English and Indian languages. */
    private val RELATIONS = listOf(
        setOf("mum", "mom", "mummy", "mommy", "mumma", "mamma", "mother", "maa", "ma", "amma", "aai", "ammi"),
        setOf("dad", "daddy", "papa", "pappa", "father", "pitaji", "baba", "appa", "abba", "abbu"),
        setOf("brother", "bro", "bhai", "bhaiya", "bhaiyya"),
        setOf("sister", "sis", "didi", "behen", "akka"),
    )
}
