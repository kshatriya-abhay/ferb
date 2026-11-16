package dev.abhay.assistant.ui

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Which way Ferb's screens move, and the back transition keeping the leaving screen on top. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class NavTest {
    private val chat = listOf("assistant")
    private val settings = chat + "settings"
    private val tools = settings + "tools"

    @Test
    fun deeperIsForwardShallowerIsBack() {
        assertThat(Nav.isBack(chat, settings)).isFalse()
        assertThat(Nav.isBack(settings, tools)).isFalse()
        // "Ferb Link" from the chat opens two at once: still forward.
        assertThat(Nav.isBack(chat, chat + "settings" + "link")).isFalse()
        assertThat(Nav.isBack(tools, settings)).isTrue()
        // "Try it" on a tool's page, or the assist trigger: straight back to the chat.
        assertThat(Nav.isBack(tools + "tool:set_alarm", chat)).isTrue()
    }

    @Test
    fun backKeepsTheLeavingScreenOnTop() {
        val motion = MotionScheme.expressive()
        assertThat(Nav.transform(back = true, shift = 90, motion).targetContentZIndex).isEqualTo(-1f)
        assertThat(Nav.transform(back = false, shift = 90, motion).targetContentZIndex).isEqualTo(0f)
    }

    @Test
    fun aSavedStackComesBackAndAnUnknownScreenIsDropped() {
        // Review P2-15: screens were strings, and any name not known became a tool's page.
        val saver = Screen.stackSaver(Screen.Page.Assistant)
        val stack = listOf(Screen.Page.Assistant, Screen.Page.Tools, Screen.Tool("set_alarm"), Screen.Keywords("set_alarm"))
        val saved = with(saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(stack) }!!
        assertThat(saver.restore(saved)).isEqualTo(stack)
        assertThat(saver.restore(arrayListOf("assistant", "renamed_screen", "settings"))).containsExactly(Screen.Page.Assistant, Screen.Page.Settings).inOrder()
        assertThat(saver.restore(arrayListOf("gone"))).containsExactly(Screen.Page.Assistant)
        assertThat(Screen.of("tool:")).isNull()
    }
}
