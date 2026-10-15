package dev.abhay.assistant.link

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.engine.Decision
import dev.abhay.assistant.engine.NeedleReply
import dev.abhay.assistant.engine.ToolCall
import dev.abhay.assistant.tools.MakeCall
import dev.abhay.assistant.tools.MediaControl
import dev.abhay.assistant.tools.SetAlarm
import dev.abhay.assistant.tools.SetTimer
import dev.abhay.assistant.tools.Tools
import dev.abhay.assistant.tools.Verdict
import org.junit.Test

/** Jev's second opinion ([LinkPolicy.jev]): when it runs, asks, or changes nothing. */
class JevTest {
    private fun reply(source: String?, vararg calls: ToolCall) = NeedleReply(
        calls = calls.toList(), suppressed = emptyList(), reasoning = "", confidence = 0.3, ungrounded = emptyList(),
        negation = false, prefillTps = 0.0, decodeTps = 0.0, peakRamMb = 0.0, error = null, source = source,
    )

    private val alarm = ToolCall(SetAlarm.name, mapOf("time" to "7:00"))
    private val pause = ToolCall(MediaControl.name, mapOf("action" to "pause"))
    private val computer = reply(FerbLink.SOURCE, alarm)
    private val phoneAlarm = reply(null, alarm)
    private val phonePause = reply(null, pause)

    /** The verdict without Jev: the computer's passes the checks and runs; Needle 3's unsure one asks. */
    private val runs = Verdict(Decision.Run, 0.0, alwaysConfirm = false)
    private val asks = Verdict(Decision.Confirm, 0.7, alwaysConfirm = false)

    private fun jev(choice: String, p: Double) = JevReply(choice, if (choice == JevReply.NONE) mapOf(choice to p) else mapOf(choice to p, JevReply.NONE to 1 - p))

    @Test
    fun theComputersCallRunsWhenJevAgreesAndAsksWhenItIsSureOfAnother() {
        assertThat(LinkPolicy.jev(runs, computer, jev(SetAlarm.name, 0.97))).isEqualTo(Decision.Run)
        assertThat(LinkPolicy.jev(runs, computer, jev(SetTimer.name, 0.9))).isEqualTo(Decision.Confirm)
        assertThat(LinkPolicy.jev(runs, computer, jev(JevReply.NONE, 0.7))).isEqualTo(Decision.Confirm)
    }

    @Test
    fun anUnsureFailedOrMissingJevChangesNothing() {
        // Hinglish: an unsure Jev mustn't make the computer's answers ask.
        assertThat(LinkPolicy.jev(runs, computer, jev(JevReply.NONE, 0.65))).isEqualTo(Decision.Run)
        assertThat(LinkPolicy.jev(runs, computer, JevReply.failed("timed out", "Jev took too long"))).isEqualTo(Decision.Run)
        assertThat(LinkPolicy.jev(runs, computer, null)).isEqualTo(Decision.Run)
        assertThat(LinkPolicy.jev(asks, phonePause, jev(MediaControl.name, 0.69))).isEqualTo(Decision.Confirm)
    }

    @Test
    fun withTheComputerOffJevRunsNeedle3sCallOnlyForToolsThatMay() {
        assertThat(LinkPolicy.jev(asks, phonePause, jev(MediaControl.name, 0.99))).isEqualTo(Decision.Run)
        // Jev checks the tool, never the values: "wake me up at 11:30 at night" became 11:30 am.
        assertThat(LinkPolicy.jev(asks, phoneAlarm, jev(SetAlarm.name, 1.0))).isEqualTo(Decision.Confirm)
    }

    @Test
    fun theUsualChecksStillAsk() {
        val call = ToolCall(MakeCall.name, mapOf("name" to "mummy"))
        val alwaysAsks = Verdict(Decision.Confirm, 0.0, alwaysConfirm = true)
        assertThat(LinkPolicy.jev(alwaysAsks, reply(FerbLink.SOURCE, call), jev(MakeCall.name, 0.98))).isEqualTo(Decision.Confirm)
        val unmentioned = Verdict(Decision.Confirm, 0.0, alwaysConfirm = false, unmentioned = true)
        assertThat(LinkPolicy.jev(unmentioned, computer, jev(SetAlarm.name, 0.98))).isEqualTo(Decision.Confirm)
    }

    @Test
    fun noAlarmsTimersOrAlwaysAskingToolsMayRunOnJevsWord() {
        val may = Tools.all.filter { it.jevMayRun }
        assertThat(may.map { it.name }).containsNoneOf(SetAlarm.name, SetTimer.name)
        may.forEach { assertThat(it.confirmByDefault).isFalse() }
    }

    @Test
    fun theDetailsSayWhatJevThought() {
        assertThat(LinkPolicy.jevLine(null, listOf(SetAlarm.name))).isNull()
        assertThat(LinkPolicy.jevLine(jev(SetAlarm.name, 0.93), listOf(SetAlarm.name))).isEqualTo("Jev agreed (0.93)")
        assertThat(LinkPolicy.jevLine(jev(SetTimer.name, 0.81), listOf(SetAlarm.name))).isEqualTo("Jev suggested ${SetTimer.title} (0.81)")
        assertThat(LinkPolicy.jevLine(jev(JevReply.NONE, 0.55), listOf(SetAlarm.name))).isEqualTo("Jev wasn't sure (no tool, 0.55)")
        assertThat(LinkPolicy.jevLine(JevReply.failed("timed out", "Jev took too long"), emptyList())).isEqualTo("Jev didn't answer: Jev took too long")
    }

    @Test
    fun aReplyIsReadOnlyWhenItSucceeded() {
        val ok = JevReply.parse("""{"success":true,"choice":"set_alarm","probabilities":{"none":0.03,"set_alarm":0.97},"options":["none","set_alarm"],"model":"jev-1.13.0","ms":44}""")
        assertThat(ok.p).isWithin(1e-9).of(0.97)
        assertThat(ok.error).isNull()
        val failed = JevReply.parse("""{"success":false,"choice":"","probabilities":{},"error":"timed out","error_kind":"timeout"}""")
        assertThat(failed.p).isNull()
        assertThat(failed.errorKind).isEqualTo("timeout")
        assertThat(JevReply.parse("not json").error).isEqualTo("unexpected answer")
    }
}
