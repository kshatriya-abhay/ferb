package dev.abhay.assistant.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ToolRouterTest {
    private val enabled = Tools.all
    private val configs = ToolSettings.defaults()

    private fun route(request: String) = ToolRouter.route(request, enabled, configs).map { it.name }

    @Test
    fun aRequestGetsTheToolsItMentions() {
        assertThat(route("Wake me up at 6:45")).containsExactly("set_alarm")
        assertThat(route("lights on")).containsExactly("set_flashlight")
        assertThat(route("open wifi settings")).containsExactly("open_app", "open_settings").inOrder()
    }

    @Test
    fun aRequestMentioningNoToolGetsNone() {
        assertThat(route("tell me a joke")).isEmpty()
    }

    @Test
    fun onlyToolsTurnedOnAreRouted() {
        assertThat(ToolRouter.route("set an alarm and show my alarms", listOf(SetAlarm), configs).map { it.name }).containsExactly("set_alarm")
    }

    @Test
    fun theUsersKeywordsReplaceTheDefaults() {
        val lamp = configs + ("set_flashlight" to configs.getValue("set_flashlight").copy(keywords = listOf("lamp", "night light")))
        assertThat(ToolRouter.route("lamp on", enabled, lamp).map { it.name }).containsExactly("set_flashlight")
        assertThat(ToolRouter.route("torch on", enabled, lamp)).isEmpty()
    }

    @Test
    fun theFallbackSeesEveryToolFiveAtATime() {
        val groups = ToolRouter.groups(enabled)
        assertThat(groups.flatten()).containsExactlyElementsIn(enabled).inOrder()
        groups.forEach { assertThat(it.size).isAtMost(ToolRouter.MAX_SEEN) }
    }
}
