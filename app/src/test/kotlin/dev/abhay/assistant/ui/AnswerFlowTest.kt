package dev.abhay.assistant.ui

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.engine.Decision
import dev.abhay.assistant.engine.NeedleReply
import dev.abhay.assistant.engine.ToolCall
import dev.abhay.assistant.link.JevReply
import dev.abhay.assistant.link.LinkCalls
import dev.abhay.assistant.link.LinkStatus
import dev.abhay.assistant.link.RewriteReply
import dev.abhay.assistant.pipeline.AudioStep
import dev.abhay.assistant.pipeline.Fallback
import dev.abhay.assistant.pipeline.Pipeline
import dev.abhay.assistant.pipeline.RewriteStep
import dev.abhay.assistant.pipeline.ToolStep
import dev.abhay.assistant.tools.Flashlight
import dev.abhay.assistant.tools.SetAlarm
import dev.abhay.assistant.tools.Tool
import dev.abhay.assistant.tools.ToolConfig
import dev.abhay.assistant.voice.GeminiRewrite
import dev.abhay.assistant.voice.Rewritten
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** [AnswerFlow]: the rewrite, the tool selection and Needle 3, each step's fallback, and the path it records. */
class AnswerFlowTest {
    private val enabled = listOf(SetAlarm, Flashlight)
    private val configs = enabled.associate { it.name to ToolConfig.defaults(it) }

    private fun reply(confidence: Double?, vararg calls: ToolCall) = NeedleReply(
        calls = calls.toList(), suppressed = emptyList(), reasoning = "", confidence = confidence, ungrounded = emptyList(),
        negation = false, prefillTps = 0.0, decodeTps = 0.0, peakRamMb = 0.0, error = null,
    )

    private val alarm = ToolCall(SetAlarm.name, mapOf("time" to "7:15"))
    private fun jev(choice: String, p: Double) = JevReply(choice, mapOf(choice to p))

    /** Ferb Link, scripted: its status, the computer's rewrite, Jev's picks, and what was asked. */
    private inner class FakeLink(
        var status: LinkStatus = LinkStatus.Ready(model = "gemma-4-e2b", jev = true),
        val rewrite: RewriteReply = RewriteReply(listOf("Set an alarm for 7:15"), raw = "Set an alarm for 7:15", instructed = true, ms = 41),
        val picks: Map<String, JevReply> = emptyMap(),
    ) : LinkCalls {
        val asked = mutableListOf<String>()
        override fun status() = status
        override suspend fun rewrite(request: String, instructions: String, maxTokens: Int, timeoutMs: Int): RewriteReply {
            asked += "rewrite: $request"
            assertThat(instructions).isEqualTo(GeminiRewrite.PROMPT)
            assertThat(maxTokens).isEqualTo(GeminiRewrite.MAX_TOKENS)
            return rewrite
        }
        override suspend fun decide(request: String, toolsJson: String, timeoutMs: Int): JevReply {
            asked += "decide: $request"
            return picks[request] ?: JevReply.failed("timed out", "Jev took longer than 6 s")
        }
    }

    private val needleAsked = mutableListOf<Pair<String, List<String>>>()
    private fun needle(answer: NeedleReply = reply(0.81, alarm)): suspend (String, List<Tool>) -> NeedleReply = { request, tools ->
        needleAsked += request to tools.map { it.name }
        answer
    }

    /** Gemini Nano's rewrite, scripted: [command] (null for NONE), or [fails]. */
    private fun geminiSays(command: String?, fails: Boolean = false): suspend (String) -> Rewritten = { said ->
        if (fails) error("BUSY") else Rewritten(said, command)
    }

    private fun answer(
        said: String,
        pipeline: Pipeline = Pipeline(AudioStep.Whistle, RewriteStep.Off, ToolStep.Off),
        link: FakeLink = FakeLink(),
        gemini: suspend (String) -> Rewritten = geminiSays("Set an alarm for 7:15"),
        heardBy: List<Step>? = null,
        geminiUsable: Boolean = true,
    ) = runBlocking { AnswerFlow(link, needle(), gemini).answer(said, enabled, configs, pipeline, heardBy, geminiUsable) }

    private fun Answer.chain() = Step.chain(path)
    private fun steps(rewrite: RewriteStep = RewriteStep.Off, tools: ToolStep = ToolStep.Off) = Pipeline(AudioStep.Whistle, rewrite, tools)

    @Test
    fun withEveryStepOffNeedle3AnswersTheWordsAsTheyAre() {
        val got = answer("wake me up at 7:15")
        assertThat(got.chain()).isEqualTo("Typed → Needle 3 (0.81)")
        assertThat(got.verdict.decision).isEqualTo(Decision.Run)
        assertThat(got.command).isNull()
        assertThat(needleAsked.single().first).isEqualTo("wake me up at 7:15")
    }

    @Test
    fun aSpokenRequestStartsWithWhatHeardIt() {
        val got = answer("wake me up at 7:15", heardBy = listOf(Step("Voice: Gemini Nano")), pipeline = steps(RewriteStep.Gemini))
        assertThat(got.chain()).isEqualTo("Voice: Gemini Nano → Rewrite: Gemini Nano → Needle 3 (0.81)")
    }

    @Test
    fun geminiNanosCommandIsWhatNeedle3Gets() {
        val got = answer("subah sava saat baje utha dena", pipeline = steps(RewriteStep.Gemini))
        assertThat(got.chain()).isEqualTo("Typed → Rewrite: Gemini Nano → Needle 3 (0.81)")
        assertThat(got.command).isEqualTo("Set an alarm for 7:15")
        assertThat(needleAsked.single().first).isEqualTo("Set an alarm for 7:15")
        // Unchanged by the rewrite: no "Understood as".
        assertThat(answer("Set an alarm for 7:15", pipeline = steps(RewriteStep.Gemini)).command).isNull()
    }

    @Test
    fun noneAsksNeedle3Nothing() {
        val got = answer("what's the capital of Peru", pipeline = steps(RewriteStep.Gemini), gemini = geminiSays(null))
        assertThat(got.nothingAsked).isTrue()
        assertThat(got.shown.calls).isEmpty()
        assertThat(got.chain()).isEqualTo("Typed → Rewrite: Gemini Nano → Nothing for the phone to do")
        assertThat(needleAsked).isEmpty()
    }

    @Test
    fun aFailedGeminiNanoRewriteGoesOnWithTheWordsAsTheyAre() {
        val got = answer("wake me up at 7:15", pipeline = steps(RewriteStep.Gemini), gemini = geminiSays(null, fails = true))
        assertThat(got.chain()).isEqualTo("Typed → Rewrite: off (Gemini Nano failed) → Needle 3 (0.81)")
        assertThat(got.fallbacks).containsExactly(Fallback("Rewrite", "your words as they are", "Gemini Nano: BUSY"))
        assertThat(needleAsked.single().first).isEqualTo("wake me up at 7:15")
    }

    @Test
    fun ferbLinksRewriteUsesFerbsPromptAndRules() {
        val link = FakeLink()
        val got = answer("kal subah sava saat baje", pipeline = steps(RewriteStep.Link), link = link)
        assertThat(got.chain()).isEqualTo("Typed → Rewrite: Ferb Link (41 ms) → Needle 3 (0.81)")
        assertThat(link.asked).containsExactly("rewrite: kal subah sava saat baje")
        assertThat(got.fallbacks).isEmpty()
        // NONE from the computer, read by Ferb's own rules.
        val none = answer("the alarm woke the baby", pipeline = steps(RewriteStep.Link), link = FakeLink(rewrite = RewriteReply(emptyList(), raw = "NONE", instructed = true)))
        assertThat(none.nothingAsked).isTrue()
    }

    @Test
    fun aFailedFerbLinkRewriteFallsBackToGeminiNanoOrTheWords() {
        // An older Ferb Link (its own prompt, no echo): Gemini Nano instead.
        val old = FakeLink(rewrite = RewriteReply(listOf("Turn on the flashlight", "Turn the volume up")))
        val viaGemini = answer("torch on karo", pipeline = steps(RewriteStep.Link), link = old)
        assertThat(viaGemini.chain()).isEqualTo("Typed → Rewrite: Gemini Nano instead → Needle 3 (0.81)")
        assertThat(viaGemini.fallbacks.single().line).isEqualTo("Rewrite: Gemini Nano instead (Ferb Link: too old, update it)")
        // The computer away, and Gemini Nano not set up: the words as they are.
        val away = answer("torch on karo", pipeline = steps(RewriteStep.Link), link = FakeLink(status = LinkStatus.NotSetUp), geminiUsable = false)
        assertThat(away.chain()).isEqualTo("Typed → Rewrite: off (Ferb Link: not set up) → Needle 3 (0.81)")
        assertThat(away.fallbacks.single()).isEqualTo(Fallback("Rewrite", "your words as they are", "Ferb Link: not set up"))
        // Timed out.
        val slow = answer("torch on karo", pipeline = steps(RewriteStep.Link), link = FakeLink(rewrite = RewriteReply.failed("timed out", "10 s")), geminiUsable = false)
        assertThat(slow.fallbacks.single().why).isEqualTo("Ferb Link: timed out")
        // Cut off at max_tokens: failed, so its half-written raw is never the command.
        val cut = RewriteReply(emptyList(), error = "answer cut off", raw = "Send a message to bhaiya: I", instructed = true)
        val words = answer("bhaiya ko message karo ki main late hoon", pipeline = steps(RewriteStep.Link), link = FakeLink(rewrite = cut), geminiUsable = false)
        assertThat(words.command).isNull()
        assertThat(needleAsked.last().first).isEqualTo("bhaiya ko message karo ki main late hoon")
        assertThat(words.fallbacks.single().why).isEqualTo("Ferb Link: answer cut off")
    }

    @Test
    fun jevNarrowsNeedle3sToolsToItsPick() {
        val link = FakeLink(picks = mapOf("Set an alarm for 7:15" to jev(SetAlarm.name, 0.93)))
        val got = answer("subah sava saat baje", pipeline = steps(RewriteStep.Gemini, ToolStep.Link), link = link)
        assertThat(got.chain()).isEqualTo("Typed → Rewrite: Gemini Nano → Tool selection: Jev (Alarms, 0.93) → Needle 3 (0.81)")
        // Jev gets the command, not the Hinglish.
        assertThat(link.asked).containsExactly("decide: Set an alarm for 7:15")
        assertThat(needleAsked.single().second).containsExactly(SetAlarm.name)
        assertThat(got.checked).containsExactly(SetAlarm.name)
    }

    @Test
    fun jevsNoToolAsksNeedle3Nothing() {
        val link = FakeLink(picks = mapOf("tell me a joke" to jev(JevReply.NONE, 0.9)))
        val got = answer("tell me a joke", pipeline = steps(tools = ToolStep.Link), link = link)
        assertThat(got.shown.calls).isEmpty()
        assertThat(needleAsked).isEmpty()
        assertThat(got.chain()).isEqualTo("Typed → Tool selection: Jev (no tool, 0.90)")
    }

    @Test
    fun withoutJevNeedle3PicksFromTheRoutedTools() {
        // Jev off in Ferb Link.
        val off = answer("wake me up at 7:15", pipeline = steps(tools = ToolStep.Link), link = FakeLink(status = LinkStatus.Ready(jev = false)))
        assertThat(off.chain()).isEqualTo("Typed → Tool selection: off (Jev off) → Needle 3 (0.81)")
        assertThat(off.fallbacks.single()).isEqualTo(Fallback("Tool selection", "Needle 3 alone", "Jev is off in Ferb Link"))
        // Jev timed out.
        val slow = answer("wake me up at 7:15", pipeline = steps(tools = ToolStep.Link))
        assertThat(slow.fallbacks.single().step).isEqualTo("Tool selection")
        assertThat(slow.chain()).startsWith("Typed → Tool selection: off (")
        // The computer away.
        val away = answer("wake me up at 7:15", pipeline = steps(tools = ToolStep.Link), link = FakeLink(status = LinkStatus.NotInstalled))
        assertThat(away.fallbacks.single().why).isEqualTo("Ferb Link: not installed")
    }
}
