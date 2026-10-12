package dev.abhay.assistant.link

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.engine.Decision
import dev.abhay.assistant.engine.NeedleReply
import dev.abhay.assistant.engine.ToolCall
import dev.abhay.assistant.tools.PrepareEnv
import dev.abhay.assistant.tools.SetAlarm
import dev.abhay.assistant.tools.ShowAgenda
import dev.abhay.assistant.tools.SetTimer
import dev.abhay.assistant.tools.ToolConfig
import dev.abhay.assistant.tools.Verdict
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

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
        assertThat(garbled.error).contains("couldn't be read")
        assertThat(garbled.fromLaptop).isTrue()
        // Needle 3's own replies have no source.
        assertThat(NeedleReply.parse("""{"success":true,"function_calls":[],"confidence":0.5}""").fromLaptop).isFalse()
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
    fun theComputersAnswerWinsUnlessItFailed() {
        val needleSays = needle(0.2, alarm("6:45"))
        val laptopSaysNo = FerbLink.parse("""{"success":true,"function_calls":[],"first":1.0,"source":"laptop"}""")
        assertThat(LinkPolicy.keep(needleSays, laptopSaysNo)).isSameInstanceAs(laptopSaysNo)
        assertThat(LinkPolicy.keep(needleSays, FerbLink.parse(laptopFailed))).isSameInstanceAs(needleSays)
    }

    @Test
    fun agreementIsTheSameCallsWhateverTheCaseSpacesOrOrder() {
        val laptop = needle(1.0, alarm(" 6:45 AM"), ToolCall(SetTimer.name, mapOf("duration" to "10 minutes")))
        assertThat(LinkPolicy.agrees(laptop, needle(0.2, ToolCall(SetTimer.name, mapOf("duration" to "10 Minutes")), alarm("6:45 am")))).isTrue()
        assertThat(LinkPolicy.agrees(laptop, needle(0.2, alarm("6:45 am")))).isFalse()
        assertThat(LinkPolicy.agrees(needle(1.0, alarm("6:45")), needle(0.3, alarm("7:45")))).isFalse()
        // Numbers by value: 40 and "40" agree.
        val volume = { v: Any -> ToolCall("set_volume", mapOf("percent" to v)) }
        assertThat(LinkPolicy.agrees(needle(1.0, volume(40)), needle(0.3, volume("40")))).isTrue()
        // Nothing to run is never agreement.
        assertThat(LinkPolicy.agrees(needle(1.0), needle(0.3))).isFalse()
        assertThat(LinkPolicy.agrees(laptop, null)).isFalse()
    }

    @Test
    fun theComputersAnswerRunsWithoutAskingOnlyWhenNeedle3Agrees() {
        val request = "wake me up at 6:45"
        val laptop = FerbLink.parse(laptopAlarm)
        assertThat(LinkPolicy.verdict(laptop, needle(0.3, alarm("6:45 am")), configs, request).decision).isEqualTo(Decision.Run)
        assertThat(LinkPolicy.verdict(laptop, needle(0.3, alarm("7:45")), configs, request).decision).isEqualTo(Decision.Confirm)
        assertThat(LinkPolicy.verdict(laptop, needle(0.3), configs, request).decision).isEqualTo(Decision.Confirm)
        // Agreement doesn't override the tools' own checks: an alarm set to always ask still asks.
        val asking = configs + (SetAlarm.name to ToolConfig.defaults(SetAlarm).copy(alwaysConfirm = true))
        assertThat(LinkPolicy.verdict(laptop, needle(0.3, alarm("6:45 am")), asking, request).decision).isEqualTo(Decision.Confirm)
        // Nor the request's words: a time it doesn't say asks.
        assertThat(LinkPolicy.verdict(laptop, needle(0.3, alarm("6:45 am")), configs, "wake me up early").decision).isEqualTo(Decision.Confirm)
    }

    @Test
    fun answersWordedDifferentlyAgreeWhenTheyDoTheSame() {
        // Seen on the phone: Gemma 4 E2B said "Friday", Needle 3 a wrong ISO date; the agenda reads
        // the day from the request, so both show Friday.
        val request = "Am I free on Friday?"
        val env = PrepareEnv(now = ZonedDateTime.of(2026, 10, 12, 8, 0, 0, 0, ZoneId.of("Asia/Kolkata")))
        val meaning = { call: ToolCall -> LinkPolicy.prepared(call.name, ShowAgenda.prepare(ShowAgenda.fromRequest(call.arguments, request), env)) }
        val laptop = needle(1.0, ToolCall(ShowAgenda.name, mapOf("day" to "Friday")))
        val needleSays = needle(0.53, ToolCall(ShowAgenda.name, mapOf("day" to "2026-10-13")))
        assertThat(LinkPolicy.agrees(laptop, needleSays)).isFalse()
        assertThat(LinkPolicy.agrees(laptop, needleSays, meaning)).isTrue()
        // A different day said in the request's place still differs: "today" isn't Friday.
        val other = { call: ToolCall -> LinkPolicy.prepared(call.name, ShowAgenda.prepare(call.arguments, env)) }
        assertThat(LinkPolicy.agrees(laptop, needle(0.5, ToolCall(ShowAgenda.name, mapOf("day" to "today"))), other)).isFalse()
        // A call that can't run has no meaning, so never agrees.
        assertThat(LinkPolicy.agrees(laptop, needleSays) { null }).isFalse()
    }
}
