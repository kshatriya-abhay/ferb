package dev.abhay.assistant.ui

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.engine.Decision
import dev.abhay.assistant.engine.NeedleReply
import dev.abhay.assistant.engine.ToolCall
import dev.abhay.assistant.link.Answerer
import dev.abhay.assistant.link.FerbLink
import dev.abhay.assistant.link.JevReply
import dev.abhay.assistant.link.LinkCalls
import dev.abhay.assistant.link.LinkMode
import dev.abhay.assistant.link.LinkStatus
import dev.abhay.assistant.tools.Flashlight
import dev.abhay.assistant.tools.SetAlarm
import dev.abhay.assistant.tools.Tool
import dev.abhay.assistant.tools.ToolConfig
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** [AnswerFlow]: who answers on each path, and the chain of steps it records as it goes. */
class AnswerFlowTest {
    private val enabled = listOf(SetAlarm, Flashlight)
    private val configs = enabled.associate { it.name to ToolConfig.defaults(it) }

    private fun reply(confidence: Double?, vararg calls: ToolCall, source: String? = null, error: String? = null) = NeedleReply(
        calls = calls.toList(), suppressed = emptyList(), reasoning = "", confidence = confidence, ungrounded = emptyList(),
        negation = false, prefillTps = 0.0, decodeTps = 0.0, peakRamMb = 0.0, error = error, source = source,
        errorDetail = error?.let { "The computer: $it" },
    )

    private val alarm = ToolCall(SetAlarm.name, mapOf("time" to "7:15"))
    private val torch = ToolCall(Flashlight.name, mapOf("on" to false))
    private fun jev(choice: String, p: Double) = JevReply(choice, mapOf(choice to p))

    /** Ferb Link, scripted: its status, the computer's answer, Jev's picks and the rewrite, and what was asked. */
    private inner class FakeLink(
        var status: LinkStatus = LinkStatus.Ready(model = "gemma-4-e2b", jev = true),
        var computer: NeedleReply = reply(0.99, alarm, source = FerbLink.SOURCE),
        val picks: Map<String, JevReply> = emptyMap(),
    ) : LinkCalls {
        val asked = mutableListOf<String>()
        override fun status() = status
        override suspend fun complete(request: String, toolsJson: String, waking: Boolean): NeedleReply { asked += "complete: $request"; return computer }
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

    private fun answer(link: FakeLink, request: String, mode: LinkMode, answerer: Answerer = Answerer.Needle, jev: Boolean = false,
                       heard: String? = null, needle: suspend (String, List<Tool>) -> NeedleReply = needle()) = runBlocking {
        AnswerFlow(link, needle).answer(request, heard, enabled, configs, mode, answerer, jev)
    }

    private fun Answer.chain() = Step.chain(path)

    @Test
    fun phoneAlone() {
        val got = answer(FakeLink(), "wake me up at 7:15", LinkMode.Off)
        assertThat(got.chain()).isEqualTo("Typed → Needle 3 (0.81)")
        assertThat(got.verdict.decision).isEqualTo(Decision.Run)
    }

    @Test
    fun phoneWithJevGivesNeedle3OnlyJevsTool() {
        val link = FakeLink(picks = mapOf("wake me up at 7:15" to jev(SetAlarm.name, 0.93)))
        val got = answer(link, "wake me up at 7:15", LinkMode.Option, jev = true)
        assertThat(got.chain()).isEqualTo("Typed → Jev (Alarms, 0.93) → Needle 3 (0.81)")
        assertThat(needleAsked.single().second).containsExactly(SetAlarm.name)
    }

    @Test
    fun computerAloneAndWithJev() {
        assertThat(answer(FakeLink(), "wake me up at 7:15", LinkMode.Option, Answerer.Link).chain()).isEqualTo("Typed → Gemma")
        val link = FakeLink(picks = mapOf("wake me up at 7:15" to jev(SetAlarm.name, 0.93)))
        val got = answer(link, "wake me up at 7:15", LinkMode.Option, Answerer.Link, jev = true)
        assertThat(got.chain()).isEqualTo("Typed → Jev (Alarms, 0.93) → Gemma")
        assertThat(got.shown.fromLaptop).isTrue()
    }

    @Test
    fun fallbacksAreSteps() {
        // Jev late: as without Jev, to the computer.
        val late = answer(FakeLink(), "wake me up at 7:15", LinkMode.Option, Answerer.Link, jev = true)
        assertThat(late.chain()).isEqualTo("Typed → Jev timed out → Gemma")
        assertThat(late.jevNote).isEqualTo("Jev timed out")
        // The computer failing: Needle 3 answers.
        val failing = FakeLink(computer = reply(null, source = FerbLink.SOURCE, error = "timed out"))
        val got = answer(failing, "wake me up at 7:15", LinkMode.Option, Answerer.Link)
        assertThat(got.chain()).isEqualTo("Typed → Gemma: timed out → Needle 3 (0.81)")
        assertThat(got.laptopError).isEqualTo("timed out")
    }

    @Test
    fun whenNeedle3IsUnsureTheComputerAnswers() {
        val got = answer(FakeLink(), "wake me up at 7:15", LinkMode.Fallback, needle = needle(reply(0.2, alarm)))
        assertThat(got.chain()).isEqualTo("Typed → Needle 3 (0.20) → Gemma")
    }

    @Test
    fun jevSaysNoToolAndNeitherModelIsAsked() {
        val link = FakeLink(picks = mapOf("how are you" to jev(JevReply.NONE, 0.9)))
        val got = answer(link, "how are you", LinkMode.Option, Answerer.Link, jev = true)
        assertThat(got.chain()).isEqualTo("Typed → Jev (no tool, 0.90)")
        assertThat(got.verdict.decision).isEqualTo(Decision.Refuse)
        assertThat(link.asked).doesNotContain("complete: how are you")
    }

    @Test
    fun voiceTakesTheTranscriptionsTextStraightToJev() {
        val link = FakeLink(picks = mapOf("Set an alarm for 7:15 am" to jev(SetAlarm.name, 0.93)))
        val got = answer(link, "Set an alarm for 7:15 am", LinkMode.Option, Answerer.Link, jev = true, heard = "sava saat baje utha dena")
        assertThat(got.chain()).isEqualTo("Voice → Gemma transcription → Jev (Alarms, 0.93) → Needle 3 (0.81)")
        assertThat(link.asked).doesNotContain("complete: Set an alarm for 7:15 am")
        // Jev failing: as without Jev, the computer answers, and the chain says so.
        val late = answer(FakeLink(), "Set an alarm for 7:15 am", LinkMode.Option, Answerer.Link, jev = true, heard = "sava saat baje utha dena")
        assertThat(late.chain()).isEqualTo("Voice \u2192 Gemma transcription \u2192 Jev timed out \u2192 Gemma")
        // Jev off: as today, the computer answers.
        val off = answer(FakeLink(), "Set an alarm for 7:15 am", LinkMode.Option, Answerer.Link, heard = "sava saat baje utha dena")
        assertThat(off.chain()).isEqualTo("Voice → Gemma transcription → Gemma")
    }

    @Test
    fun typedHinglishStaysWithTheComputerForNow() {
        // No word list: typed requests in Computer mode go to Jev and Gemma, whatever the language.
        val link = FakeLink(picks = mapOf("sava saat baje utha dena" to jev(SetAlarm.name, 0.9)))
        val got = answer(link, "sava saat baje utha dena", LinkMode.Option, Answerer.Link, jev = true)
        assertThat(got.chain()).isEqualTo("Typed \u2192 Jev (Alarms, 0.90) \u2192 Gemma")
    }

    @Test
    fun jevAtItsLimitIsANote() {
        val link = FakeLink(status = LinkStatus.Ready(model = "gemma-4-e2b", jev = false, jevCapped = true))
        val got = answer(link, "wake me up at 7:15", LinkMode.Option, Answerer.Link, jev = true)
        assertThat(got.chain()).isEqualTo("Typed → Gemma")
        assertThat(got.jevNote).isEqualTo("Jev's limit reached")
    }

    @Test
    fun theComputersTimeStructureIsResolvedAndAnAlarmOnAnotherDayAsks() {
        val monday = org.json.JSONObject("""{"v":1,"kind":"at","day":{"weekday":"monday","which":null},"time":{"hour":7,"minute":0,"meridiem":"am","part":null},"source":"Monday 7 am"}""")
        val link = FakeLink(computer = reply(0.99, ToolCall(SetAlarm.name, mapOf("time" to "7 am", "when" to monday)), source = FerbLink.SOURCE))
        val friday9 = java.time.LocalDateTime.parse("2026-10-16T09:00").atZone(java.time.ZoneId.of("Asia/Kolkata"))
        val got = runBlocking {
            AnswerFlow(link, needle(), now = { friday9 }).answer("set an alarm for Monday 7 am", null, enabled, configs, LinkMode.Option, Answerer.Link, jevSwitch = false)
        }
        assertThat(got.verdict.decision).isEqualTo(Decision.Confirm)
        assertThat(got.shown.calls.single().arguments[dev.abhay.assistant.tools.TimeArgs.WANTED]).isEqualTo("2026-10-19T07:00")
    }
}
