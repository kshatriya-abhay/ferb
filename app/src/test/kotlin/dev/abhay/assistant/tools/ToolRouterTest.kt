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
    fun remindIsntATimerUnlessATimerIsSaid() {
        assertThat(route("remind me in 25 minutes to water the plants")).doesNotContain("set_timer")
        assertThat(route("can you give me a reminder in an hour")).doesNotContain("set_timer")
        assertThat(route("start a 5 minute timer to remind me about the tea")).contains("set_timer")
        assertThat(route("countdown 2 minutes and remind me")).contains("set_timer")
        assertThat(route("25 minute timer")).containsExactly("set_timer")
        // Whatever keywords the user gives timers.
        val mine = configs + ("set_timer" to configs.getValue("set_timer").copy(keywords = listOf("minute")))
        assertThat(ToolRouter.route("remind me in 25 minutes", enabled, mine).map { it.name }).doesNotContain("set_timer")
        val saved = ToolConfig.fromJson("""{"keywords": ["minute"]}""", SetTimer)
        assertThat(saved.mentionedIn("remind me in 25 minutes")).isFalse()
        assertThat(saved.mentionedIn("25 minutes please")).isTrue()
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

    @Test
    fun aToolThatsOffIsOfferedWhenTheRequestMentionsIt() {
        // The defaults: the tools that need setup are off, Calls among them.
        assertThat(ToolRouter.off("call mom", configs).map { it.name }).containsExactly("make_call")
        // Nothing to offer for a tool that's on, or for a request no tool's words match.
        assertThat(ToolRouter.off("turn on the flashlight", configs)).isEmpty()
        assertThat(ToolRouter.off("what's the capital of France", configs)).isEmpty()
        // Once turned on, it's no longer offered.
        val on = configs + ("make_call" to configs.getValue("make_call").copy(enabled = true))
        assertThat(ToolRouter.off("call mom", on)).isEmpty()
    }
}
