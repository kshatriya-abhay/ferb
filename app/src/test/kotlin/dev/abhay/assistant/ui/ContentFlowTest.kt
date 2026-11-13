package dev.abhay.assistant.ui

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.abhay.assistant.engine.Decision
import dev.abhay.assistant.engine.NeedleReply
import dev.abhay.assistant.engine.ToolCall
import dev.abhay.assistant.link.JevReply
import dev.abhay.assistant.link.LinkCalls
import dev.abhay.assistant.link.LinkStatus
import dev.abhay.assistant.link.RewriteReply
import dev.abhay.assistant.pipeline.AudioStep
import dev.abhay.assistant.pipeline.Pipeline
import dev.abhay.assistant.pipeline.RewriteStep
import dev.abhay.assistant.pipeline.ToolStep
import dev.abhay.assistant.tools.ContentMapTest
import dev.abhay.assistant.tools.Tool
import dev.abhay.assistant.tools.ToolConfig
import dev.abhay.assistant.tools.Tools
import dev.abhay.assistant.voice.ContentRewrite
import dev.abhay.assistant.voice.Rewritten
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test

/**
 * The content fix in [AnswerFlow] (0.2, H2): the words to keep come from the words as said; Needle 3
 * sees the quotes in Latin letters and the router doesn't see them; three guards ask first. The
 * golden cases (dev.txt and dev2.txt, never the blind sets) replay the frozen code's runs end to end.
 */
class ContentFlowTest {
    private val enabled = Tools.all
    private val configs = enabled.associate { it.name to ToolConfig.defaults(it) }
    /** content-rules.md's now: Tuesday 6 October 2026, 10:00. */
    private val now = ZonedDateTime.of(2026, 10, 6, 10, 0, 0, 0, ZoneId.of("Asia/Kolkata"))

    private val link = object : LinkCalls {
        override fun status() = LinkStatus.NotSetUp
        override suspend fun rewrite(request: String, instructions: String, maxTokens: Int, timeoutMs: Int, jsonSchema: String?) = RewriteReply.failed("unused", "")
        override suspend fun decide(request: String, toolsJson: String, timeoutMs: Int) = JevReply.failed("unused", "")
    }

    private fun reply(confidence: Double, vararg calls: ToolCall) = NeedleReply(
        calls = calls.toList(), suppressed = emptyList(), reasoning = "", confidence = confidence, ungrounded = emptyList(),
        negation = false, prefillTps = 0.0, decodeTps = 0.0, peakRamMb = 0.0, error = null,
    )

    private val asked = mutableListOf<Pair<String, List<String>>>()

    private fun answer(said: String, rewritten: Rewritten?, needle: NeedleReply, rewrite: RewriteStep = RewriteStep.Gemini): Answer = runBlocking {
        AnswerFlow(link, needle = { request, tools: List<Tool> -> asked += request to tools.map { it.name }; needle },
            gemini = { rewritten ?: error("unused") }, now = { now }, content = ContentMapTest.icu())
            .answer(said, enabled, configs, Pipeline(AudioStep.Whistle, rewrite, ToolStep.Off), null, geminiUsable = true)
    }

    private fun typed(said: String, command: String, content: String) = ContentRewrite.merged(said, ContentRewrite.Typed(command, content))

    @Test
    fun aMessageInHindiIsSentAsSaid() {
        val said = "भैया को मैसेज करो मैं देर से आऊँगा"
        val got = answer(said, typed(said, "Send a text to भैया", "मैं देर से आऊँगा"),
            reply(0.95, ToolCall("send_text", mapOf("name" to "भैया", "message" to "maim dera se a'umga"))))
        // Needle 3 read the quote in Latin letters; the message is the words as said, in Devanagari.
        assertThat(asked.single().first).isEqualTo("Send a text to भैया: \"maim dera se a'umga\"")
        assertThat(got.shown.calls.single().arguments["message"]).isEqualTo("मैं देर से आऊँगा")
        assertThat(got.asks).isEmpty()
        // A text always asks first (its default), but not because of anything unsaid.
        assertThat(got.verdict.alwaysConfirm).isTrue()
        assertThat(got.verdict.ungrounded).isFalse()
    }

    @Test
    fun theRouterDoesntReadTheWordsToKeep() {
        val said = "note: book the alarm system service"
        answer(said, typed(said, "Make a note", "book the alarm system service"),
            reply(0.9, ToolCall("create_note", mapOf("text" to "book the alarm system service"))))
        // "alarm" is only inside the quote: the router gives Needle 3 the notes, not the alarms.
        assertThat(asked.single().second).doesNotContain("set_alarm")
        assertThat(asked.single().second).contains("create_note")
    }

    @Test
    fun wordsNotInTheRequestAsk() {
        val said = "remind me to water the plants"
        val got = answer(said, typed(said, "Remind me", "pay the electricity bill"),
            reply(0.95, ToolCall("add_reminder", mapOf("text" to "pay the electricity bill"))))
        assertThat(got.asks).containsExactly(AnswerFlow.Guard.NotSaid.why)
        assertThat(got.verdict.decision).isEqualTo(Decision.Confirm)
        assertThat(Step.chain(got.path)).endsWith("Asks first (the words to keep aren't in what you said)")
    }

    @Test
    fun aRequestThatNamesAnotherToolAsks() {
        // The words as said ask for a note; the rewrite made it a call.
        val said = "make a note: the plumber comes on friday"
        val got = answer(said, Rewritten(said, "Call the plumber on Friday"), reply(0.95, ToolCall("make_call", mapOf("name" to "the plumber"))))
        assertThat(got.asks).containsExactly(AnswerFlow.Guard.OtherTool.why)
        assertThat(got.verdict.decision).isEqualTo(Decision.Confirm)
    }

    @Test
    fun aTimeTheCommandDroppedAsks() {
        val said = "text Asha at 6 pm that the gate is open"
        val got = answer(said, typed(said, "Send a text to Asha", "the gate is open"),
            reply(0.95, ToolCall("send_text", mapOf("name" to "Asha", "message" to "the gate is open"))))
        assertThat(got.asks).containsExactly(AnswerFlow.Guard.TimeDropped.why)
        assertThat(got.verdict.decision).isEqualTo(Decision.Confirm)
    }

    @Test
    fun withoutTheRewriteNothingChanges() {
        // Whistle and no rewrite: Needle 3 reads the words as they are, and its arguments stand.
        val said = "text Asha the gate is open"
        val got = answer(said, null, reply(0.95, ToolCall("send_text", mapOf("name" to "Asha", "message" to "gate is open"))), rewrite = RewriteStep.Off)
        assertThat(asked.single().first).isEqualTo(said)
        assertThat(got.shown.calls.single().arguments["message"]).isEqualTo("gate is open")
        assertThat(got.asks).isEmpty()
    }

    @Test
    fun theGoldenCasesRunAsTheFrozenCodeRanThem() {
        val cases = ContentMapTest.golden().getJSONArray("cases")
        val guards = mapOf(
            "content not found in the words as said" to AnswerFlow.Guard.NotSaid,
            "the words as said give a time, the command none" to AnswerFlow.Guard.TimeDropped,
        )
        var n = 0
        for (k in 0 until cases.length()) {
            val c = cases.getJSONObject(k)
            if (!c.has("needle")) continue
            val said = c.getString("said")
            val name = "${c.getString("set")}:${c.getInt("row")} ${c.getString("source")} ${c.getString("mode")}"
            val rewritten = if (c.getString("mode") == "typed") ContentRewrite.parse(said, JSONObject(c.getString("raw"))) else ContentRewrite.parseLine(said, c.getString("raw"))
            assertWithMessage(name).that(rewritten.command).isEqualTo(c.getString("command"))
            val needle = c.getJSONObject("needle").put("success", true)
            asked.clear()
            val got = answer(said, rewritten, NeedleReply.parse(needle.toString()))
            assertWithMessage(name).that(asked.single().first).isEqualTo(c.getString("seen"))
            val tools = c.getJSONArray("tools").let { a -> (0 until a.length()).map { a.getString(it) } }
            assertWithMessage(name).that(asked.single().second).containsExactlyElementsIn(tools)
            val want = c.getJSONArray("calls")
            assertWithMessage(name).that(got.shown.calls.map { it.name }).containsExactlyElementsIn((0 until want.length()).map { want.getJSONObject(it).getString("name") }).inOrder()
            got.shown.calls.forEachIndexed { i, call ->
                val field = dev.abhay.assistant.tools.ContentMap.CONTENT[call.name] ?: return@forEachIndexed
                val expected = want.getJSONObject(i).getJSONObject("arguments").opt(field).takeUnless { it == JSONObject.NULL }
                assertWithMessage("$name: $field").that(call.arguments[field]).isEqualTo(expected)
            }
            val wantAsks = c.getJSONArray("asks").let { a -> (0 until a.length()).map { a.getString(it) } }.map { why ->
                guards[why] ?: AnswerFlow.Guard.OtherTool.also { assertWithMessage(name).that(why).startsWith("the words as said name ") }
            }
            assertWithMessage(name).that(got.asks).containsExactlyElementsIn(wantAsks.map { it.why })
            n++
        }
        assertThat(n).isGreaterThan(120)
    }
}
