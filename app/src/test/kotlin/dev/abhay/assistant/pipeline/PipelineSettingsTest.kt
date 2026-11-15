package dev.abhay.assistant.pipeline

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.testing.FakeApp
import org.junit.After
import org.junit.Test

/** [PipelineSettings]: the saved choice, and what the phone can use as Gemini Nano and Ferb Link report it. */
class PipelineSettingsTest {
    private fun settings() = PipelineSettings(FakeApp.allocate(FakeApp.PrefsContext::class.java))

    @After fun tearDown() = FakeApp.prefs.clear()

    @Test
    fun geminiNanosAnswerLeavesFerbLinksStateAsLastRead() {
        // Review P2-13: geminiChecked read Ferb Link again itself, on the main thread; its caller reads it now.
        val s = settings()
        s.linkChanged(PipelineSettings.LinkState(installed = true, ready = true, audio = true))
        s.geminiChecked(speech = true, rewrite = false)
        val can = s.abilities.value
        assertThat(listOf(can.link, can.linkAudio, can.linkApp, can.geminiSpeech, can.geminiRewrite)).containsExactly(true, true, true, true, false).inOrder()
    }
}
