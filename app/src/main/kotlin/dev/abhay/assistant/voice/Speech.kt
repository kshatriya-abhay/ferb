package dev.abhay.assistant.voice

import org.json.JSONObject
import kotlin.math.log10

/**
 * Whether a recording could hold speech at all, before Whistle hears it: Ferb Link's SpeechCheck,
 * on float PCM. Speech rises and falls several times a second; room noise, hiss and hum stay level.
 * So: the spread between the loud and quiet 20 ms frames (90th against 10th percentile of their
 * energy). Ferb Link measured (2026-10-12) silence, hiss, hum and pink or brown noise at 0 to 6 dB,
 * commands, alone or in noise, at 30 dB and more.
 */
object SpeechCheck {
    /** Below this spread a recording is taken as no speech. Between 6 (noise) and 30 (speech). */
    const val MIN_SPREAD_DB = 12.0

    /** The frame-energy spread in dB of mono float PCM in [-1, 1] at [rate]. */
    fun spreadDb(pcm: FloatArray, rate: Int = PcmRecorder.SAMPLE_RATE): Double {
        val frame = rate / 50
        if (frame <= 0 || pcm.size < frame * 5) return 0.0
        val energies = (0 until pcm.size / frame).map { f ->
            var sum = 0.0
            for (i in f * frame until (f + 1) * frame) sum += pcm[i].toDouble() * pcm[i]
            // On Ferb Link's 16-bit scale, so the same floor (and threshold) applies.
            10 * log10(sum / frame * 32768.0 * 32768.0 + 1e-9)
        }.sorted()
        fun at(q: Double) = energies[(q * (energies.size - 1)).toInt()]
        return at(0.9) - at(0.1)
    }

    fun mayHoldSpeech(pcm: FloatArray): Boolean = spreadDb(pcm) >= MIN_SPREAD_DB
}

/** What Whistle heard: [text] (empty for silence or steady noise), and how long it took. */
data class Heard(val text: String, val language: String?, val ms: Long, val audioMs: Long) {
    companion object {
        /** The engine's transcription JSON: {"text", "language", "ttft_ms", "decode_tps"}. */
        fun parse(json: String, ms: Long, audioMs: Long): Heard {
            val o = JSONObject(json)
            return Heard(o.optString("text").trim(), o.optString("language").ifEmpty { null }, ms, audioMs)
        }
    }
}

/** What a phone recording leads to: a request to answer, or a notice. */
sealed interface PhoneVoice {
    data class Request(val text: String) : PhoneVoice
    data class Failed(val notice: String, val keepClip: Boolean) : PhoneVoice

    companion object {
        const val NO_WORDS = "No words heard: try again, a little closer"

        /** Before Whistle: a clip with no speech in it never reaches the model. */
        fun silent(pcm: FloatArray): Failed? = if (SpeechCheck.mayHoldSpeech(pcm)) null else Failed(NO_WORDS, keepClip = false)

        fun of(heard: Result<Heard>): PhoneVoice = heard.fold(
            onSuccess = { if (it.text.isBlank()) Failed(NO_WORDS, keepClip = false) else Request(it.text) },
            onFailure = { Failed("Your phone couldn't hear it: ${it.message ?: it}", keepClip = true) },
        )
    }
}
