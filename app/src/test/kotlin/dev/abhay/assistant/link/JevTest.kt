package dev.abhay.assistant.link

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.tools.Flashlight
import dev.abhay.assistant.tools.MediaControl
import dev.abhay.assistant.tools.SetAlarm
import dev.abhay.assistant.tools.SetTimer
import dev.abhay.assistant.tools.TakePhoto
import org.junit.Test

/** "Use Jev to select the tool" ([LinkPolicy.select]): Jev's tool, no tool, or going on without Jev. */
class JevTest {
    private val on = listOf(SetAlarm.name, MediaControl.name, Flashlight.name, TakePhoto.name)

    private fun jev(choice: String, p: Double, vararg others: Pair<String, Double>) =
        JevReply(choice, mapOf(choice to p) + others)

    @Test
    fun jevsToolFromHalfGoesToTheAnsweringModel() {
        assertThat(LinkPolicy.select(jev(SetAlarm.name, 0.5), on)).isEqualTo(JevPick.Tool(SetAlarm.name))
        assertThat(LinkPolicy.select(jev(JevReply.NONE, 0.8), on)).isEqualTo(JevPick.NoTool)
    }

    @Test
    fun theComputerAlsoGetsJevsNextToolsForRequestsForTwoThings() {
        // "turn on the torch and open the camera": the flashlight first, the camera too.
        val two = jev(Flashlight.name, 0.6, TakePhoto.name to 0.3, MediaControl.name to 0.05, JevReply.NONE to 0.05)
        assertThat(LinkPolicy.select(two, on)).isEqualTo(JevPick.Tool(Flashlight.name, listOf(TakePhoto.name)))
        // Three tools at most, never "none" or a tool that's off.
        val many = jev(Flashlight.name, 0.5, TakePhoto.name to 0.4, MediaControl.name to 0.3, SetAlarm.name to 0.28,
            JevReply.NONE to 0.35, SetTimer.name to 0.3)
        assertThat(LinkPolicy.select(many, on)).isEqualTo(JevPick.Tool(Flashlight.name, listOf(TakePhoto.name, MediaControl.name)))
    }

    @Test
    fun withoutAPickItGoesOnWithoutJevSayingWhyInAFewWords() {
        val unsure = LinkPolicy.select(jev(SetAlarm.name, 0.49), on) as JevPick.Fallback
        assertThat(unsure.reason).isEqualTo("Jev wasn't sure")
        val late = LinkPolicy.select(JevReply.failed("timed out", "Jev took longer than 6 s"), on) as JevPick.Fallback
        assertThat(late.reason).isEqualTo("Jev: timed out")
        assertThat(late.detail).isEqualTo("Jev took longer than 6 s")
        val capped = JevReply(null, emptyMap(), error = "Jev's limit reached",
            errorDetail = "Jev reached its spending limit (\$3.00). Raise it in Ferb Link to turn Jev on again.", errorKind = "off")
        assertThat((LinkPolicy.select(capped, on) as JevPick.Fallback).reason).isEqualTo("Jev's limit reached")
        // A tool that's off: as without Jev.
        assertThat(LinkPolicy.select(jev(SetTimer.name, 0.9), on)).isInstanceOf(JevPick.Fallback::class.java)
    }

    @Test
    fun theDetailsSayWhatJevPicked() {
        assertThat(LinkPolicy.jevLine(null)).isNull()
        assertThat(LinkPolicy.jevLine(jev(SetAlarm.name, 0.93))).isEqualTo("Jev picked ${SetAlarm.title} (0.93)")
        assertThat(LinkPolicy.jevLine(jev(JevReply.NONE, 0.8))).isEqualTo("Jev picked no tool (0.80)")
        assertThat(LinkPolicy.jevLine(jev(JevReply.NONE, 0.45))).isEqualTo("Jev wasn't sure (no tool, 0.45)")
        assertThat(LinkPolicy.jevLine(JevReply.failed("timed out", "Jev took too long"))).isEqualTo("Jev didn't answer: Jev took too long")
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
