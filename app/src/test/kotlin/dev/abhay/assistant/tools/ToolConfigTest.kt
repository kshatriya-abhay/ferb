package dev.abhay.assistant.tools

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.engine.Decision
import dev.abhay.assistant.engine.NeedleReply
import org.junit.Test

class ToolConfigTest {
    @Test
    fun configsSurviveTheirJson() {
        val config = ToolConfig(enabled = true, alwaysConfirm = true, runFrom = 0.55, options = mapOf("clock" to ClockProviders.SYSTEM_CLOCK))
        assertThat(ToolConfig.fromJson(config.toJson(), SetAlarm)).isEqualTo(config)
    }

    @Test
    fun missingOrBadValuesFallBackToTheToolsDefaults() {
        assertThat(ToolConfig.fromJson("not json", SetAlarm)).isEqualTo(ToolConfig.defaults(SetAlarm))
        val partial = ToolConfig.fromJson("""{"enabled": false, "run_from": 7}""", SetAlarm)
        assertThat(partial.enabled).isFalse()
        assertThat(partial.runFrom).isEqualTo(ToolConfig.MAX_RUN_FROM) // clamped
        assertThat(partial.choice(ClockProviders.option)).isEqualTo(ClockProviders.SYSTEM_CLOCK)
    }

    @Test
    fun anUnknownChoiceMeansTheDefault() {
        val config = ToolConfig.defaults(SetAlarm).copy(options = mapOf("clock" to "gone_provider"))
        assertThat(config.choice(ClockProviders.option)).isEqualTo(ClockProviders.SYSTEM_CLOCK)
    }

    @Test
    fun clockToolsOfferTheClockChoice() {
        listOf(SetAlarm, SetTimer, ShowAlarms).forEach { assertThat(it.options).containsExactly(ClockProviders.option) }
        assertThat(Flashlight.options).isEmpty()
    }

    private fun reply(confidence: Double, vararg tools: String) = NeedleReply.parse(
        """{"success":true,"function_calls":[${tools.joinToString { """{"name":"$it","arguments":{}}""" }}],"confidence":$confidence}""",
    )

    private val configs = ToolSettings.defaults()

    @Test
    fun aReplyNeedsTheHighestBarAmongItsTools() {
        assertThat(Verdict.of(reply(0.35, "set_flashlight"), configs).decision).isEqualTo(Decision.Run)
        val both = Verdict.of(reply(0.35, "set_flashlight", "set_alarm"), configs)
        assertThat(both.runAt).isEqualTo(0.4)
        assertThat(both.decision).isEqualTo(Decision.Confirm)
    }

    @Test
    fun alwaysConfirmAsksWhateverTheConfidence() {
        val asking = configs + ("set_flashlight" to configs.getValue("set_flashlight").copy(alwaysConfirm = true))
        val verdict = Verdict.of(reply(0.99, "set_flashlight"), asking)
        assertThat(verdict.decision).isEqualTo(Decision.Confirm)
        assertThat(verdict.alwaysConfirm).isTrue()
    }

    @Test
    fun noCallsIsARefusalAndUnknownToolsNeedCactussBar() {
        assertThat(Verdict.of(reply(0.9), configs).decision).isEqualTo(Decision.Refuse)
        assertThat(Verdict.of(reply(0.6, "mystery_tool"), configs).runAt).isEqualTo(Decision.RUN_AT)
    }
}
