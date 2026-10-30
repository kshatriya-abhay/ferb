package dev.abhay.assistant.voice

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.onboarding.Tip
import dev.abhay.assistant.pipeline.AudioStep
import dev.abhay.assistant.ui.chatTips
import org.junit.Test

/** When the chat box shows the mic (audio processing on), and its tip for each way of hearing. */
class MicTest {
    @Test fun theMicShowsUnlessAudioProcessingIsOff() {
        assertThat(Mic.shows(AudioStep.Off)).isFalse()
        AudioStep.entries.filter { it != AudioStep.Off }.forEach { assertThat(Mic.shows(it)).isTrue() }
    }

    @Test fun theMicTipOnlyWhenTheMicShows() {
        fun tips(mic: Boolean) = chatTips(emptyList(), startScreen = false, micShown = mic).map { it.tip }
        assertThat(tips(Mic.shows(AudioStep.Off))).doesNotContain(Tip.Mic)
        assertThat(tips(Mic.shows(AudioStep.Gemini))).contains(Tip.Mic)
    }

    @Test fun theMicTipSaysWhichLanguages() {
        assertThat(Mic.tip(AudioStep.Whistle)).isEqualTo(Tip.Mic.text)
        assertThat(Mic.tip(AudioStep.Whistle)).doesNotContain("Hindi")
        assertThat(Mic.tip(AudioStep.Gemini)).contains("Hindi")
        assertThat(Mic.tip(AudioStep.Link)).contains("Hindi")
        // ✓ and ✕, as the recording shows them.
        AudioStep.entries.forEach { assertThat(Mic.tip(it)).contains("✓") }
    }
}
