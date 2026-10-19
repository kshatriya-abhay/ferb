package dev.abhay.assistant.voice

import com.google.common.truth.Truth.assertThat
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Test

/** Phone-mode voice before and after Whistle: the silence check, its JSON, and what follows. */
class SpeechTest {
    private val rate = PcmRecorder.SAMPLE_RATE

    private fun silence(seconds: Double) = FloatArray((seconds * rate).toInt())
    private fun hiss(seconds: Double, level: Float = 0.05f) = Random(1).let { r -> FloatArray((seconds * rate).toInt()) { (r.nextFloat() * 2 - 1) * level } }
    private fun hum(seconds: Double) = FloatArray((seconds * rate).toInt()) { (0.2 * sin(2 * PI * 50 * it / rate)).toFloat() }

    /** Syllable-like bursts: a tone switched on and off four times a second, over faint hiss. */
    private fun speechLike(seconds: Double): FloatArray {
        val noise = hiss(seconds, 0.002f)
        return FloatArray(noise.size) { i ->
            val on = (i * 4 / rate) % 2 == 0
            noise[i] + if (on) (0.3 * sin(2 * PI * 220 * i / rate)).toFloat() else 0f
        }
    }

    @Test
    fun noiseIsNotSpeechAndSyllablesAre() {
        assertThat(SpeechCheck.mayHoldSpeech(silence(2.0))).isFalse()
        assertThat(SpeechCheck.mayHoldSpeech(hiss(3.0))).isFalse()
        assertThat(SpeechCheck.mayHoldSpeech(hum(3.0))).isFalse()
        assertThat(SpeechCheck.spreadDb(speechLike(2.0))).isGreaterThan(30.0)
        assertThat(SpeechCheck.mayHoldSpeech(speechLike(2.0))).isTrue()
        // Too short to judge: under five frames is no speech.
        assertThat(SpeechCheck.mayHoldSpeech(FloatArray(100) { 0.5f })).isFalse()
    }

    @Test
    fun theSameScaleAsFerbLink() {
        // The threshold was measured on 16-bit samples; a quiet clip's spread is scale-free above the floor.
        val loud = speechLike(2.0)
        val quiet = FloatArray(loud.size) { loud[it] * 0.1f }
        assertThat(SpeechCheck.spreadDb(quiet)).isWithin(0.5).of(SpeechCheck.spreadDb(loud))
    }

    @Test
    fun whistlesJsonAndWhatFollows() {
        val heard = Heard.parse("""{"text":" Set a timer for ten minutes. ","language":"en","ttft_ms":40.0,"decode_tps":90.0}""", ms = 300, audioMs = 2000)
        assertThat(heard.text).isEqualTo("Set a timer for ten minutes.")
        assertThat(heard.language).isEqualTo("en")
        assertThat(PhoneVoice.of(Result.success(heard))).isEqualTo(PhoneVoice.Request("Set a timer for ten minutes."))

        val nothing = Heard.parse("""{"text":"","language":"","ttft_ms":0.0,"decode_tps":0.0}""", 100, 1000)
        assertThat(nothing.language).isNull()
        assertThat(PhoneVoice.of(Result.success(nothing))).isEqualTo(PhoneVoice.Failed(PhoneVoice.NO_WORDS, keepClip = false))

        // The engine failing keeps the clip, to send again.
        val failed = PhoneVoice.of(Result.failure(IllegalStateException("needle_transcribe failed: x"))) as PhoneVoice.Failed
        assertThat(failed.keepClip).isTrue()
        assertThat(failed.notice).startsWith("Your phone couldn't hear it")

        assertThat(PhoneVoice.silent(silence(2.0))).isEqualTo(PhoneVoice.Failed(PhoneVoice.NO_WORDS, keepClip = false))
        assertThat(PhoneVoice.silent(speechLike(2.0))).isNull()
    }
}
