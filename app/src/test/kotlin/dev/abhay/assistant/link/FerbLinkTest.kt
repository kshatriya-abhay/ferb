package dev.abhay.assistant.link

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.engine.Decision
import dev.abhay.assistant.engine.NeedleReply
import dev.abhay.assistant.engine.ToolCall
import dev.abhay.assistant.tools.SetAlarm
import dev.abhay.assistant.tools.SetTimer
import dev.abhay.assistant.tools.ToolConfig
import dev.abhay.assistant.tools.Verdict
import org.junit.Test

class FerbLinkTest {
    /** Ferb Link's reply as its `Reply.toJson` writes it. */
    private val laptopAlarm = """{"success":true,"function_calls":[{"name":"set_alarm","arguments":{"time":"6:45 am"}}],
        "confidence":0.0213,"first":0.99994,"mean":0.91,"text":"","model":"gemma-4-E2B-it-Q4_K_M","ms":1480,"source":"laptop"}"""

    private val laptopFailed = """{"success":false,"function_calls":[],"confidence":0.0,"first":0.0,"mean":0.0,"text":"",
        "model":"","ms":3002,"source":"laptop","error":"The laptop took too long to answer","error_kind":"timeout"}"""

    private val configs = mapOf(SetAlarm.name to ToolConfig.defaults(SetAlarm), SetTimer.name to ToolConfig.defaults(SetTimer))

    private fun needle(confidence: Double?, vararg calls: ToolCall) = NeedleReply(
        calls = calls.toList(), suppressed = emptyList(), reasoning = "", confidence = confidence, ungrounded = emptyList(),
        negation = false, prefillTps = 0.0, decodeTps = 0.0, peakRamMb = 0.0, error = null,
    )

    private fun alarm(time: String) = ToolCall(SetAlarm.name, mapOf("time" to time))

    @Test
    fun aLaptopReplyReadsLikeNeedle3sWithItsModelAndFirstToken() {
        val reply = FerbLink.parse(laptopAlarm)
        assertThat(reply.calls).containsExactly(alarm("6:45 am"))
        assertThat(reply.fromLaptop).isTrue()
        assertThat(reply.model).isEqualTo("gemma-4-E2B-it-Q4_K_M")
        assertThat(reply.ms).isEqualTo(1480L)
        // The first token's probability stands in for the confidence (for the details only).
        assertThat(reply.confidence).isWithin(1e-9).of(0.99994)
        assertThat(reply.error).isNull()
    }

    @Test
    fun aFailedOrUnreadableLaptopReplyIsAnError() {
        assertThat(FerbLink.parse(laptopFailed).error).isEqualTo("The laptop took too long to answer")
        val garbled = FerbLink.parse("not json")
        // A few words for the chat, the reason at length for the details.
        assertThat(garbled.error).isEqualTo("unexpected answer")
        assertThat(garbled.errorDetail).contains("couldn't be read")
        assertThat(garbled.fromLaptop).isTrue()
        // Needle 3's own replies have no source.
        assertThat(NeedleReply.parse("""{"success":true,"function_calls":[],"confidence":0.5}""").fromLaptop).isFalse()
    }

    @Test
    fun ferbLinksShortErrorAndItsDetailAreKeptApart() {
        val reply = FerbLink.parse("""{"success":false,"error":"timed out","error_detail":"Your computer took longer than 10 s to answer"}""")
        assertThat(reply.error).isEqualTo("timed out")
        assertThat(reply.errorDetail).isEqualTo("Your computer took longer than 10 s to answer")
    }

    @Test
    fun theComputerAnswersFirstOnlyWhenPickedInTheChatBox() {
        assertThat(LinkPolicy.laptopFirst(LinkMode.Option, Answerer.Link)).isTrue()
        assertThat(LinkPolicy.laptopFirst(LinkMode.Option, Answerer.Needle)).isFalse()
        assertThat(LinkPolicy.laptopFirst(LinkMode.Fallback, Answerer.Link)).isFalse()
        assertThat(LinkPolicy.laptopFirst(LinkMode.Off, Answerer.Link)).isFalse()
    }

    @Test
    fun inFallbackTheComputerIsAskedWhenNeedle3IsUnsureOrFindsNoTool() {
        val request = "wake me up at 6:45"
        val sure = needle(0.9, alarm("6:45"))
        assertThat(LinkPolicy.askLaptopAfter(sure, Verdict.of(sure, configs, request))).isFalse()
        // Alarms run from 0.4.
        val unsure = needle(0.2, alarm("6:45"))
        assertThat(LinkPolicy.askLaptopAfter(unsure, Verdict.of(unsure, configs, request))).isTrue()
        val none = needle(0.6)
        assertThat(LinkPolicy.askLaptopAfter(none, Verdict.of(none, configs, request))).isTrue()
        // Sure, but asking only because the request doesn't say the time: not the computer's job.
        val ungrounded = needle(0.9, alarm("7:30"))
        assertThat(Verdict.of(ungrounded, configs, request).ungrounded).isTrue()
        assertThat(LinkPolicy.askLaptopAfter(ungrounded, Verdict.of(ungrounded, configs, request))).isFalse()
    }

    @Test
    fun theComputersAnswerRunsWhenTheUsualChecksPass() {
        val request = "wake me up at 6:45"
        val laptop = FerbLink.parse(laptopAlarm)
        assertThat(LinkPolicy.verdict(laptop, configs, request).decision).isEqualTo(Decision.Run)
        // Whatever its first-token probability: there's no bar.
        assertThat(LinkPolicy.verdict(laptop.copy(confidence = 0.01), configs, request).decision).isEqualTo(Decision.Run)
        // A tool set to always ask still asks.
        val asking = configs + (SetAlarm.name to ToolConfig.defaults(SetAlarm).copy(alwaysConfirm = true))
        assertThat(LinkPolicy.verdict(laptop, asking, request).decision).isEqualTo(Decision.Confirm)
        // So does a value the request doesn't say.
        assertThat(LinkPolicy.verdict(laptop, configs, "wake me up early").decision).isEqualTo(Decision.Confirm)
    }

    @Test
    fun aTranscriptHasWhatWasHeardAndTheEnglishCommand() {
        val heard = Transcript.parse("""{"heard":"subah 5 baje ka alarm laga do","text":"Set an alarm for 5 am","model":"gemma-4-e2b","ms":640}""")
        assertThat(heard.heard).isEqualTo("subah 5 baje ka alarm laga do")
        assertThat(heard.text).isEqualTo("Set an alarm for 5 am")
        assertThat(heard.ms).isEqualTo(640L)
        assertThat(heard.error).isNull()
        val failed = Transcript.parse("""{"heard":"","text":"","error":"The laptop took too long to answer","error_kind":"timeout"}""")
        assertThat(failed.error).isEqualTo("The laptop took too long to answer")
        assertThat(Transcript.parse("not json").error).contains("couldn't be read")
    }
}
