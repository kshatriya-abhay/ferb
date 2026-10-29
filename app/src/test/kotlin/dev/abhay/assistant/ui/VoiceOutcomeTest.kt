package dev.abhay.assistant.ui

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.link.Transcript
import org.junit.Test

/** What a spoken request leads to, from the computer's transcription (as Ferb Link returns it). */
class VoiceOutcomeTest {
    @Test
    fun aTranscriptIsAnsweredAsItsEnglishCommand() {
        val hinglish = Transcript.parse("""{"heard":"subah 5 baje ka alarm laga do","text":"Set an alarm for 5 am","model":"gemma-4-e2b","ms":612}""")
        assertThat(VoiceOutcome.of(hinglish)).isEqualTo(VoiceOutcome.Answer("Set an alarm for 5 am", heard = "subah 5 baje ka alarm laga do"))
        // English: what was heard is the command, so no "Heard: …" under it.
        val english = Transcript.parse("""{"heard":"open zoom","text":"Open zoom"}""")
        assertThat(VoiceOutcome.of(english)).isEqualTo(VoiceOutcome.Answer("Open zoom", heard = null))
    }

    @Test
    fun silenceAndFailuresAreNotices() {
        // Silence (Ferb Link's SpeechCheck): nothing to keep.
        val silence = VoiceOutcome.of(Transcript.parse("""{"heard":"","text":""}""")) as VoiceOutcome.Failed
        assertThat(silence.keepClip).isFalse()
        assertThat(silence.notice).startsWith("Nothing heard")
        // The computer failed: the recording stays, to send again; the reason in its short form.
        val failed = VoiceOutcome.of(Transcript.failed("Timed out")) as VoiceOutcome.Failed
        assertThat(failed.keepClip).isTrue()
        assertThat(failed.notice).isEqualTo("Your computer couldn't hear it: timed out")
    }
}
