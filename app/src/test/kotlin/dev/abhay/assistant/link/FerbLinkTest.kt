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
        assertThat(LinkPolicy.laptopFirst(LinkMode.Off, Answerer.Link)).isFalse()
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

    @Test
    fun aSleepingModelGetsTheLongestWait() {
        // status with model_state: asleep or unloaded means the next request waits for a load.
        assertThat(FerbLink.statusOf(LinkResult { mapOf("version" to 1, "enabled" to true, "configured" to true, "model_state" to "asleep")[it] }))
            .isEqualTo(LinkStatus.Ready(asleep = true))
        assertThat((FerbLink.statusOf(LinkResult { mapOf("version" to 1, "enabled" to true, "configured" to true, "model_state" to "unloaded")[it] }) as LinkStatus.Ready).asleep).isTrue()
        // Loaded, or an older Ferb Link that doesn't say: the normal timeouts.
        assertThat((FerbLink.statusOf(LinkResult { mapOf("version" to 1, "enabled" to true, "configured" to true, "model_state" to "loaded")[it] }) as LinkStatus.Ready).asleep).isFalse()
        assertThat((FerbLink.statusOf(LinkResult { mapOf("version" to 1, "enabled" to true, "configured" to true)[it] }) as LinkStatus.Ready).asleep).isFalse()
        assertThat(FerbLink.timeout(FerbLink.TIMEOUT_MS, waking = false)).isEqualTo(10_000)
        assertThat(FerbLink.timeout(FerbLink.TIMEOUT_MS, waking = true)).isEqualTo(60_000)
        assertThat(FerbLink.timeout(FerbLink.TRANSCRIBE_TIMEOUT_MS, waking = true)).isEqualTo(60_000)
    }

    @Test
    fun aTimedReplyCarriesItsTimeAndAsks() {
        val reply = FerbLink.parse("""{"success":true,"source":"laptop","function_calls":[
            {"name":"set_alarm","arguments":{"time":"7:15"},"when":{"v":1,"kind":"at","day":{"relative":2},"time":{"hour":7,"minute":15}},"asks":["parso is not the day after tomorrow"],"trimmed":false},
            {"name":"open_app","arguments":{"app_name":"Maps"},"when":null,"asks":[],"trimmed":true},
            {"name":"set_timer","arguments":{"duration":"10 minutes"}}]}""")
        assertThat((reply.calls[0].arguments["when"] as org.json.JSONObject).getString("kind")).isEqualTo("at")
        assertThat(reply.calls[0].arguments[dev.abhay.assistant.tools.TimeArgs.ASK]).isEqualTo("parso is not the day after tomorrow")
        assertThat(reply.calls[1].arguments[dev.abhay.assistant.tools.TimeArgs.ASK]).isEqualTo("a value from your computer was cut short")
        // A plain reply, or an older Ferb Link's: the calls as they were.
        assertThat(reply.calls[2].arguments).isEqualTo(mapOf("duration" to "10 minutes"))
    }
}
