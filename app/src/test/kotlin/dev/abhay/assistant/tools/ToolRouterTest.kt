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
        assertThat(route("what's the capital of France")).isEmpty()
        // "tell" goes to Messages since 2026-10-12 ("tell rohit I'm late"); Needle 3 calls nothing for a joke.
        assertThat(route("tell me a joke")).containsExactly("send_text")
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
}
