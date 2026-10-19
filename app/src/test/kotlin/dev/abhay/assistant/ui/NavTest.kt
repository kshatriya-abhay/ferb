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
}
