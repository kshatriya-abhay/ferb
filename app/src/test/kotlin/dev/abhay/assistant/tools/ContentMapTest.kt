package dev.abhay.assistant.tools

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * [ContentMap], the content fix's mapping, against the frozen Python code it ports
 * (build/content/frozen-2/content_map.py): hand-made cases, and golden cases from dev.txt and
 * dev2.txt (golden-dev.json, written by build/content/golden.py from Gemma's and Gemini Nano's
 * real answers; never from the blind sets).
 */
class ContentMapTest {
    private val map = icu()

    @Test
    fun skeletonsMatchAcrossScriptsAndSpellings() {
        assertThat(map.skeleton("दाल")).isEqualTo(map.skeleton("daal"))
        assertThat(map.skeleton("bhaiya")).isEqualTo(map.skeleton("baiya"))
        assertThat(map.skeleton("phone")).isEqualTo(map.skeleton("fone"))
        assertThat(map.skeleton("main late hoon")).isEqualTo("nnlthn")
    }

    @Test
    fun similarityIsDifflibs() {
        // difflib.SequenceMatcher(None, a, b).ratio(), from Python 3.
        assertThat(Similarity.ratio("abcd", "bcde")).isWithin(1e-9).of(0.75)
        assertThat(Similarity.ratio("tskhnk", "tskn")).isWithin(1e-9).of(0.8)
        assertThat(Similarity.ratio("", "")).isEqualTo(1.0)
        assertThat(Similarity.ratio("abxcd", "abcd")).isWithin(1e-9).of(8.0 / 9)
        assertThat(Similarity.matches("qabxcd", "abycdf")).isEqualTo(4)
    }

    @Test
    fun theContentIsTheWordsAsSaid() {
        // The rewrite translated it; the words as said come back, in their own script.
        val said = "भैया को मैसेज करो मैं देर से आऊँगा"
        val got = map.contentOf("main der se aaunga", listOf("main der se aaunga"), mapOf("name" to "Bhaiya"), said)
        assertThat(got).isEqualTo(ContentMap.Content("मैं देर से आऊँगा", true))
    }

    @Test
    fun aWordWithNoConsonantsIsKeptWhenTheContentSaysIt() {
        // Smoke check, 2026-11-15: "I" has an empty skeleton, so the span without it scored the same and,
        // being shorter, won: the text read "am running ten minutes late".
        val said = "Text Priya that I am running ten minutes late"
        val name = mapOf("name" to "Priya")
        assertThat(map.contentOf("I am running ten minutes late", listOf("I am running ten minutes late"), name, said))
            .isEqualTo(ContentMap.Content("I am running ten minutes late", true))
        // Needle 3 dropped the "I" but the rewrite's quote kept it: the union keeps it.
        assertThat(map.contentOf("am running ten minutes late", listOf("I am running ten minutes late"), name, said))
            .isEqualTo(ContentMap.Content("I am running ten minutes late", true))
        assertThat(map.contentOf("I'll be there by 6", listOf("I'll be there by 6"), name, "Text Priya that I'll be there by 6").text)
            .isEqualTo("I'll be there by 6")
        assertThat(map.contentOf("I'm on the train", listOf("I'm on the train"), mapOf("name" to "Mum"), "Message Mum that I'm on the train").text)
            .isEqualTo("I'm on the train")
        // Inside the content, such words always were kept.
        assertThat(map.contentOf("buy a cake and I will pay", listOf("buy a cake and I will pay"), emptyMap(), "remind me to buy a cake and I will pay").text)
            .isEqualTo("buy a cake and I will pay")
        // Not wider than the content: a word it doesn't say stays out.
        assertThat(map.contentOf("bus is late", listOf("bus is late"), mapOf("name" to "Ria"), "text Ria a bus is late").text)
            .isEqualTo("bus is late")
    }

    @Test
    fun curlyQuotesMarkTheContentForEveryReader() {
        // Review P2-23: quoted() read “…”, forNeedle() and masked() didn't, so Needle 3 got Devanagari
        // and the router read the content's words ("alarm").
        val command = "Make a note: “book the alarm system service”"
        assertThat(ContentMap.quoted(command)).containsExactly("book the alarm system service")
        assertThat(ContentMap.masked(command)).isEqualTo("Make a note: “”")
        assertThat(map.forNeedle("Make a note: “दाल जल गई”")).isEqualTo("Make a note: “dala jala ga'i”")
        assertThat(map.forNeedle("Make a note: \"दाल जल गई\"")).isEqualTo("Make a note: \"dala jala ga'i\"")
    }

    @Test
    fun theKeptWordsLoseTheirSentencesPunctuationNotTheirQuotes() {
        // Review P2-26, a departure from the frozen H2 map: its ASCII-only trim kept Hindi's full stop,
        // and took one quote mark off each end on its own, halving a quote the user said.
        assertThat(map.contentOf("main der se aaunga", listOf("मैं देर से आऊँगा"), mapOf("name" to "मम्मी"), "मम्मी को मैसेज करो मैं देर से आऊँगा।").text)
            .isEqualTo("मैं देर से आऊँगा")
        assertThat(map.contentOf("\"Inception\" is worth a rewatch", listOf("\"Inception\" is worth a rewatch"), emptyMap(), "make a note \"Inception\" is worth a rewatch").text)
            .isEqualTo("\"Inception\" is worth a rewatch")
        assertThat(map.contentOf("say \"hi\"", emptyList(), mapOf("name" to "Mum"), "text Mum say \"hi\"").text).isEqualTo("say \"hi\"")
        // A lone quote mark at an end still goes, as does an English full stop.
        assertThat(map.contentOf("buy milk", listOf("buy milk"), emptyMap(), "note \"buy milk.").text).isEqualTo("buy milk")
    }

    @Test
    fun aQuoteThatIsAnotherArgumentIsNotTheContent() {
        val said = "add milk and eggs to the groceries list"
        val got = map.contentOf("milk and eggs", listOf("milk and eggs", "groceries"), mapOf("list" to "groceries"), said)
        assertThat(got).isEqualTo(ContentMap.Content("milk and eggs", true))
    }

    @Test
    fun contentNotInTheWordsAsSaidIsNotMapped() {
        val got = map.contentOf("buy a new phone", listOf("buy a new phone"), emptyMap(), "remind me at 5 to call the plumber")
        assertThat(got.mapped).isFalse()
        assertThat(got.text).isEqualTo("buy a new phone")
        assertThat(map.contentOf(null, emptyList(), emptyMap(), "torch on karo")).isEqualTo(ContentMap.Content(null, false))
    }

    @Test
    fun needleSeesTheQuotesInLatinLettersAndTheRouterNone() {
        assertThat(map.forNeedle("Make a note: \"दाल जल गई\"")).isEqualTo("Make a note: \"dala jala ga'i\"")
        assertThat(ContentMap.masked("Make a note: \"set an alarm\" now")).isEqualTo("Make a note: \"\" now")
        assertThat(ContentMap.quoted("Add \"rice\" to my “groceries” list")).containsExactly("rice", "groceries").inOrder()
    }

    @Test
    fun icu4jWritesLatinAsTheLaptopsUconvDid() {
        val latin = golden().getJSONObject("latin")
        assertThat(latin.length()).isGreaterThan(50)
        latin.keys().forEach { assertThat(map.latinOf(it)).isEqualTo(latin.getString(it)) }
    }

    @Test
    fun theGoldenCasesMapAsTheFrozenCodeDid() {
        val cases = golden().getJSONArray("cases")
        var checked = 0
        for (k in 0 until cases.length()) {
            val c = cases.getJSONObject(k)
            val command = c.optString("command").ifEmpty { null } ?: continue
            assertThat(map.forNeedle(command)).isEqualTo(c.getString("seen"))
            val needle = c.getJSONObject("needle")
            val raw = needle.optJSONArray("function_calls")?.takeIf { it.length() > 0 }
                ?: c.getJSONArray("calls").takeIf { it.length() > 0 }?.let { held(needle, it) } ?: continue
            val want = c.getJSONArray("calls")
            assertThat(raw.length()).isEqualTo(want.length())
            var unmapped = false
            for (n in 0 until raw.length()) {
                val call = raw.getJSONObject(n)
                val field = ContentMap.CONTENT[call.getString("name")] ?: continue
                val args = map(call.getJSONObject("arguments"))
                val got = map.contentOf(args[field] as? String ?: args[field]?.toString(), ContentMap.quoted(command),
                    args.filterKeys { it != field && !it.startsWith("_") }, c.getString("said"))
                val expected = want.getJSONObject(n).getJSONObject("arguments").opt(field).takeUnless { it == JSONObject.NULL }
                com.google.common.truth.Truth.assertWithMessage("${c.getString("set")}:${c.getInt("row")} ${c.getString("source")} ${c.getString("mode")}").that(got.text).isEqualTo(expected)
                if (!got.mapped) unmapped = true
                checked++
            }
            val asks = c.getJSONArray("asks").let { a -> (0 until a.length()).map { a.getString(it) } }
            assertThat(unmapped).isEqualTo("content not found in the words as said" in asks)
        }
        assertThat(checked).isGreaterThan(80)
    }

    /** The held-back timed call the frozen code brought back, as Needle 3 held it. */
    private fun held(needle: JSONObject, calls: JSONArray): JSONArray? {
        val name = calls.getJSONObject(0).getString("name")
        val sup = needle.optJSONArray("suppressed_calls") ?: return null
        return (0 until sup.length()).map { sup.getJSONObject(it) }.firstOrNull { it.getString("name") == name }?.let { JSONArray().put(it) }
    }

    private fun map(o: JSONObject): Map<String, Any?> = o.keys().asSequence().associateWith { o.opt(it).takeUnless { v -> v == JSONObject.NULL } }

    companion object {
        /** The mapping with ICU4J, as Android's ICU (android.icu) is on the phone. */
        fun icu(): ContentMap {
            val t = com.ibm.icu.text.Transliterator.getInstance("Any-Latin; Latin-ASCII")
            return ContentMap { t.transliterate(it) }
        }

        fun golden(): JSONObject = JSONObject(File(ContentMapTest::class.java.classLoader!!.getResource("content/golden-dev.json")!!.toURI()).readText())
    }
}
