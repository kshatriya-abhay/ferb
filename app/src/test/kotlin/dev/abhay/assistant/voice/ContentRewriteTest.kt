package dev.abhay.assistant.voice

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.tools.ContentMapTest
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Test

/** [ContentRewrite]: the frozen H2 texts, Gemini Nano's typed schema, and the merge into one command. */
class ContentRewriteTest {
    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun quotingTheContentLeavesTheRestOfTheCommandAlone() {
        // Review P2-24: a plain substring replace put quotes inside a name ("Ab"hi"shek").
        fun merged(command: String, content: String) = ContentRewrite.merged("said", ContentRewrite.Typed(command, content)).command
        assertThat(merged("Send a text to Abhishek: hi", "hi")).isEqualTo("Send a text to Abhishek: \"hi\"")
        assertThat(merged("Text Manoj no", "no")).isEqualTo("Text Manoj \"no\"")
        // Not there as whole words: added after a colon.
        assertThat(merged("Text Brooke", "ok")).isEqualTo("Text Brooke: \"ok\"")
    }

    @Test
    fun theTextsAreTheFrozenFilesByteForByte() {
        // build/content/frozen-2/SHA256SUMS, 2026-11-06.
        assertThat(sha256(ContentRewrite.PROMPT)).isEqualTo("52537a51c885de04c8bb42fc59bec6422bbefaec167183fac2bda71e88de30c5")
        assertThat(sha256(ContentRewrite.SCHEMA)).isEqualTo("d49ee71617ce7a48dada166d0aff2b9d7a6ac8242e15648b63e06c77fded9acd")
        assertThat(sha256(ContentRewrite.PROMPT_A)).isEqualTo("532bdd543ca2fabd0f61a331d801364e2818050d7bb370327030e0689a7aba7e")
        // A is R2 with the content quoted: R2's rules otherwise.
        assertThat(ContentRewrite.PROMPT_A).contains("inside double quotes")
        assertThat(ContentRewrite.PROMPT_A.substringBefore("Keep every name")).isEqualTo(GeminiRewrite.PROMPT_R2.substringBefore("Keep every name"))
        // Well under Ferb Link's limits: 16000 characters of instructions, 8000 of schema.
        assertThat(ContentRewrite.PROMPT.length).isLessThan(16_000)
        assertThat(ContentRewrite.SCHEMA.length).isLessThan(8_000)
    }

    @Test
    fun geminiNanosTypedSchemaIsTheSchemaGemmaGets() {
        val schema = JSONObject(ContentRewrite.SCHEMA)
        val detail = HCommand_GeneratedProvider.generableDetail
        val props = schema.getJSONObject("properties")
        assertThat(detail.guideDetails.map { it.name }).containsExactly("command", "content").inOrder()
        detail.guideDetails.forEach {
            assertThat(it.description).isEqualTo(props.getJSONObject(it.name).getString("description"))
            assertThat(it.type).isEqualTo(String::class)
            assertThat(it.nullable).isFalse()
        }
        assertThat(schema.getJSONArray("required").let { r -> (0 until r.length()).map { r.getString(it) } }).containsExactly("command", "content")
    }

    @Test
    fun theContentIsQuotedInTheCommand() {
        fun merged(command: String, content: String) = ContentRewrite.merged("said", ContentRewrite.Typed(command, content)).command
        assertThat(merged("Send a text to Rahul", "kal shaam ko milte hain")).isEqualTo("Send a text to Rahul: \"kal shaam ko milte hain\"")
        assertThat(merged("Make a note to buy milk", "buy milk")).isEqualTo("Make a note to \"buy milk\"")
        assertThat(merged("Make a note: \"buy milk\"", "buy milk")).isEqualTo("Make a note: \"buy milk\"")
        assertThat(merged("Turn on the flashlight", "")).isEqualTo("Turn on the flashlight")
        assertThat(merged("NONE", "")).isNull()
        assertThat(merged("none.", "x")).isNull()
        assertThat(merged("  ", "x")).isNull()
        assertThat(ContentRewrite.parse("said", JSONObject("""{"command": "Turn on the flashlight", "content": null}""")).command).isEqualTo("Turn on the flashlight")
    }

    @Test
    fun aLineAnswerKeepsItsQuotes() {
        assertThat(ContentRewrite.parseLine("s", "Send a text to meera: \"the door is open\"\nmore").command).isEqualTo("Send a text to meera: \"the door is open\"")
        assertThat(ContentRewrite.parseLine("s", "tell meera -> Send a text to meera: \"hi\"").command).isEqualTo("Send a text to meera: \"hi\"")
        assertThat(ContentRewrite.parseLine("s", "NONE.").command).isNull()
        assertThat(runCatching { ContentRewrite.parseLine("s", "  \n ") }.isFailure).isTrue()
        // The golden line answers, as the frozen code read them.
        val cases = ContentMapTest.golden().getJSONArray("cases")
        val lines = (0 until cases.length()).map { cases.getJSONObject(it) }.filter { it.getString("mode") == "line" && it.optString("raw").isNotBlank() }
        assertThat(lines.size).isGreaterThan(60)
        lines.forEach { assertThat(ContentRewrite.parseLine(it.getString("said"), it.getString("raw")).command).isEqualTo(it.optString("command").ifEmpty { null }) }
    }

    @Test
    fun theGoldenAnswersMergeAsTheFrozenCodeMergedThem() {
        val cases = ContentMapTest.golden().getJSONArray("cases")
        var n = 0
        for (k in 0 until cases.length()) {
            val c = cases.getJSONObject(k)
            if (c.getString("mode") != "typed") continue
            val want = c.optString("command").ifEmpty { null }
            // An answer that isn't JSON (an empty one) is no command, as Python's json.loads failing.
            val json = runCatching { JSONObject(c.optString("raw")) }.getOrNull()
            assertThat(json?.let { ContentRewrite.parse(c.getString("said"), it).command }).isEqualTo(want)
            n++
        }
        assertThat(n).isGreaterThan(60)
    }
}
