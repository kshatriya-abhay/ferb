package dev.abhay.assistant.link

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FerbLinkTest {
    @Test
    fun aTranscriptThatFailedWithoutWordsIsStillAFailure() {
        // Review P2-118: Transcript.parse ignored success, unlike Jev's and the rewrite's replies.
        assertThat(Transcript.parse("""{"success":false,"heard":"","text":""}""").error).isEqualTo("transcription failed")
        assertThat(Transcript.parse("""{"success":true,"heard":"","text":""}""").error).isNull()
    }

    @Test
    fun aTranscriptHasWhatWasHeardAndTheEnglishCommand() {
        val heard = Transcript.parse("""{"heard":"subah 5 baje ka alarm laga do","text":"Set an alarm for 5 am","model":"gemma-4-e2b","ms":640}""")
        assertThat(heard.heard).isEqualTo("subah 5 baje ka alarm laga do")
        assertThat(heard.text).isEqualTo("Set an alarm for 5 am")
        assertThat(heard.ms).isEqualTo(640L)
        assertThat(heard.error).isNull()
        val failed = Transcript.parse("""{"heard":"","text":"","error":"The laptop took too long to answer","error_kind":"timeout"}""")
        assertThat(failed.error).isEqualTo("The laptop took too long to answer")
        assertThat(Transcript.parse("not json").error).contains("couldn't be read")
    }

    @Test
    fun aSleepingModelGetsTheLongestWait() {
        // status with model_state: asleep or unloaded means the next request waits for a load.
        assertThat(FerbLink.statusOf(LinkResult { mapOf("version" to 1, "enabled" to true, "configured" to true, "model_state" to "asleep")[it] }))
            .isEqualTo(LinkStatus.Ready(asleep = true))
        assertThat((FerbLink.statusOf(LinkResult { mapOf("version" to 1, "enabled" to true, "configured" to true, "model_state" to "unloaded")[it] }) as LinkStatus.Ready).asleep).isTrue()
        // Loaded, or an older Ferb Link that doesn't say: the normal timeouts.
        assertThat((FerbLink.statusOf(LinkResult { mapOf("version" to 1, "enabled" to true, "configured" to true, "model_state" to "loaded")[it] }) as LinkStatus.Ready).asleep).isFalse()
        assertThat((FerbLink.statusOf(LinkResult { mapOf("version" to 1, "enabled" to true, "configured" to true)[it] }) as LinkStatus.Ready).asleep).isFalse()
        assertThat(FerbLink.timeout(FerbLink.REWRITE_TIMEOUT_MS, waking = false)).isEqualTo(10_000)
        assertThat(FerbLink.timeout(FerbLink.REWRITE_TIMEOUT_MS, waking = true)).isEqualTo(60_000)
        assertThat(FerbLink.timeout(FerbLink.TRANSCRIBE_TIMEOUT_MS, waking = true)).isEqualTo(60_000)
    }
}
