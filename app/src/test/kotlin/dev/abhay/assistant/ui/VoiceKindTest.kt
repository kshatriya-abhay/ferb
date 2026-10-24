package dev.abhay.assistant.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** What the chat box shows for a spoken request: the field, a recording (✕ and ✓), or one waiting to be sent again. */
class VoiceKindTest {
    @Test
    fun eachVoiceStateHasItsKind() {
        assertThat(VoiceKind.of(null)).isEqualTo(VoiceKind.None)
        assertThat(VoiceKind.of(AssistantViewModel.Voice.Recording(0))).isEqualTo(VoiceKind.Recording)
        assertThat(VoiceKind.of(AssistantViewModel.Voice.Recording(0, onPhone = true, live = dev.abhay.assistant.voice.GeminiLanguage.English, heard = "set a"))).isEqualTo(VoiceKind.Recording)
        assertThat(VoiceKind.of(AssistantViewModel.Voice.Heard("set an alarm", 900))).isEqualTo(VoiceKind.Waiting)
        assertThat(VoiceKind.of(AssistantViewModel.Voice.Recorded(900, FloatArray(1)))).isEqualTo(VoiceKind.Waiting)
    }

    @Test
    fun theButtonsSayWhatTheyDo() {
        assertThat(CANCEL).isEqualTo("Cancel")
        assertThat(FINISH).isEqualTo("Stop and send")
    }
}
