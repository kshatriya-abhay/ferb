package dev.abhay.assistant.eval

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.voice.Endpoint
import dev.abhay.assistant.voice.EnergyVad
import java.io.File
import javax.sound.sampled.AudioSystem
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Auto-send on the voice bench's clips (16 kHz mono WAVs, build/voice/clips, or FERB_EVAL_CLIPS):
 * each is fed to [EnergyVad] 100 ms at a time, as the recorder reads, and counts as cut early when
 * the endpoint ends it before its last speech. FERB_EVAL=1. The gate: at most 1 clip cut early
 * (at 1 s of silence and 300 ms per onset, 22 of the 81 were), and each end at most 100 ms past
 * the silence window after the speech before it.
 */
class EndpointReplay {
    @Test
    fun clips() {
        assumeTrue("FERB_EVAL=1", System.getenv("FERB_EVAL") == "1")
        val dir = File(System.getenv("FERB_EVAL_CLIPS") ?: "../build/voice/clips")
        val clips = dir.listFiles { f -> f.name.endsWith(".wav") }?.sorted().orEmpty()
        assumeTrue("clips in $dir", clips.isNotEmpty())
        val early = mutableListOf<String>()
        var ended = 0
        var worstLate = 0L
        for (clip in clips) {
            val pcm = read(clip)
            val vad = EnergyVad(16_000)
            var endAt: Long? = null
            var speechBeforeEnd = 0L
            var window = 0L
            var i = 0
            while (i < pcm.size) {
                val n = minOf(1_600, pcm.size - i)
                vad.feed(pcm, i, n)
                i += n
                if (endAt == null && vad.endpoint.decide(vad.nowMs) == Endpoint.Decision.End) {
                    endAt = vad.nowMs; speechBeforeEnd = vad.endpoint.lastSpeechMs!!; window = vad.endpoint.window()
                }
            }
            val lastSpeech = vad.endpoint.lastSpeechMs ?: -1L
            if (endAt != null) {
                ended++
                if (lastSpeech > endAt) early += "${clip.name}: ended at $endAt ms, speech until $lastSpeech ms"
                worstLate = maxOf(worstLate, endAt - speechBeforeEnd - window)
            }
        }
        println("EndpointReplay: ${clips.size} clips, $ended ended by silence, ${early.size} cut early, worst end ${worstLate} ms past the window")
        early.forEach { println("  $it") }
        assertThat(early.size).isAtMost(1)
        assertThat(worstLate).isAtMost(100L)
    }

    private fun read(file: File): FloatArray {
        AudioSystem.getAudioInputStream(file).use { input ->
            val format = input.format
            check(format.sampleRate == 16_000f && format.channels == 1 && format.sampleSizeInBits == 16) { "${file.name}: $format" }
            val bytes = input.readAllBytes()
            val big = format.isBigEndian
            return FloatArray(bytes.size / 2) { k ->
                val lo = bytes[2 * k + if (big) 1 else 0].toInt() and 0xff
                val hi = bytes[2 * k + if (big) 0 else 1].toInt()
                ((hi shl 8) or lo).toShort() / 32768f
            }
        }
    }
}
