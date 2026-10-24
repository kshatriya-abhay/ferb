package dev.abhay.assistant.voice

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.voice.Endpoint.Decision
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Test

/** Auto-send: speech, then 2 s of silence, ends a request; no speech in 8 s gives up; a pause, a click don't. */
class EndpointerTest {
    private val rate = 16_000
    private val random = Random(7)

    /** Room noise, about -60 dBFS. */
    private fun noise(ms: Int) = FloatArray(rate * ms / 1000) { (random.nextFloat() - 0.5f) * 0.004f }

    /** Voice-like: a 180 Hz tone at about -23 dBFS, rising and falling as syllables do, over noise. */
    private fun speech(ms: Int) = FloatArray(rate * ms / 1000) { i ->
        val syllable = 0.6 + 0.4 * sin(2 * PI * 4 * i / rate)
        (0.1 * syllable * sin(2 * PI * 180 * i / rate)).toFloat() + (random.nextFloat() - 0.5f) * 0.004f
    }

    /** A click: 30 ms, loud. */
    private fun click() = FloatArray(rate * 30 / 1000) { i -> if (i % 2 == 0) 0.8f else -0.8f }

    /** Feeds [parts] 100 ms at a time, as the recorder reads: each decision, with the recording's time after its chunk. */
    private fun run(vararg parts: FloatArray): List<Pair<Long, Decision>> {
        val vad = EnergyVad(rate)
        val all = parts.fold(FloatArray(0)) { a, b -> a + b }
        val step = rate / 10
        return (all.indices step step).map { i ->
            val decision = vad.feed(all, i, minOf(step, all.size - i))
            vad.nowMs to decision
        }
    }

    @Test
    fun silenceOnlyGivesUpAfterEightSeconds() {
        val decisions = run(noise(9_000))
        val first = decisions.first { it.second != Decision.Continue }
        assertThat(first.second).isEqualTo(Decision.NoSpeech)
        assertThat(first.first).isAtLeast(8_000L)
        assertThat(first.first).isAtMost(8_100L)
    }

    @Test
    fun speechThenTwoSecondsOfSilenceEnds() {
        val decisions = run(noise(500), speech(1_200), noise(2_500))
        val end = decisions.first { it.second != Decision.Continue }
        assertThat(end.second).isEqualTo(Decision.End)
        // About 2 s after the speech stopped (at 1.7 s).
        assertThat(end.first).isIn(com.google.common.collect.Range.closed(3_600L, 3_900L))
    }

    @Test
    fun aPauseShorterThanTwoSecondsKeepsListening() {
        val decisions = run(noise(500), speech(1_000), noise(1_500), speech(1_000), noise(1_000))
        assertThat(decisions.map { it.second }.toSet()).containsExactly(Decision.Continue)
    }

    @Test
    fun aClickIsNotSpeech() {
        val vad = EnergyVad(rate)
        vad.feed(noise(500) + click() + noise(3_000))
        assertThat(vad.endpoint.heardSpeech).isFalse()
    }

    @Test
    fun geminiNanosPartialResultsAreItsSpeech() {
        val endpoint = Endpoint()
        assertThat(endpoint.decide(7_900)).isEqualTo(Decision.Continue)
        assertThat(endpoint.sending(1_000)).isNull()
        endpoint.speech(500)
        endpoint.speech(1_400) // a newer partial result
        assertThat(endpoint.decide(3_300)).isEqualTo(Decision.Continue)
        assertThat(endpoint.decide(3_400)).isEqualTo(Decision.End)
        // ✓'s progress: nothing in a short pause, then filling over the 2 s.
        assertThat(endpoint.sending(1_500)).isNull()
        assertThat(endpoint.sending(2_400)).isWithin(0.01f).of(0.5f)
        assertThat(endpoint.sending(9_000)).isEqualTo(1f)
        assertThat(Endpoint().decide(8_000)).isEqualTo(Decision.NoSpeech)
    }
}
