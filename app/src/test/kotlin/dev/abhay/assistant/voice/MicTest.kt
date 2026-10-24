package dev.abhay.assistant.voice

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.onboarding.Tip
import dev.abhay.assistant.ui.chatTips
import org.junit.Test

/** When the chat box shows the mic, with "Voice on this phone (Whistle)" on or off. */
class MicTest {
    @Test fun withWhistleOnTheMicAlwaysShows() {
        assertThat(Mic.shows(computerHears = false, whistleOn = true)).isTrue()
        assertThat(Mic.shows(computerHears = true, whistleOn = true)).isTrue()
    }

    @Test fun withWhistleOffItShowsOnlyWhenTheComputerHears() {
        // Phone picked, Ferb Link off, or Computer picked without audio: Whistle would hear.
        assertThat(Mic.shows(computerHears = false, whistleOn = false)).isFalse()
        // Computer picked and taking audio: Gemma transcribes there.
        assertThat(Mic.shows(computerHears = true, whistleOn = false)).isTrue()
    }

    @Test fun theMicTipOnlyWhenTheMicShows() {
        fun tips(mic: Boolean) = chatTips(emptyList(), startScreen = false, answererShown = false, micShown = mic).map { it.tip }
        assertThat(tips(Mic.shows(computerHears = false, whistleOn = false))).doesNotContain(Tip.Mic)
        assertThat(tips(Mic.shows(computerHears = false, whistleOn = true))).contains(Tip.Mic)
    }

    @Test fun theSettingSaysComputerModeCanStillListen() {
        assertThat(VoiceSettings.TITLE).isEqualTo("Use voice to text on-device")
        assertThat(VoiceSettings.DESCRIPTION).contains("Computer mode can still listen.")
    }

    @Test fun theMicTipIsWordedPerSetting() {
        assertThat(Mic.tip(whistleOn = true)).isEqualTo(Tip.Mic.text)
        assertThat(Mic.tip(whistleOn = true)).contains("speak English")
        assertThat(Mic.tip(whistleOn = false)).contains("Heard by your computer; English or Hindi.")
        assertThat(Mic.tip(whistleOn = false)).doesNotContain("On this phone")
    }
}
