package dev.abhay.assistant.voice

import kotlin.math.log10

/**
 * When a spoken request is over, so it's sent without ✓ (2026-10-24): it waits for speech, then
 * ends after [silenceMs] without it; with no speech at all by [noSpeechMs], it gives up (nothing
 * is sent). Time is in ms on the recording's own clock ([EnergyVad] for PCM) or the wall clock
 * (Gemini Nano, whose partial results are its speech). ✓ and ✕ still finish it at once.
 */
class Endpoint(val silenceMs: Long = SILENCE_MS, val noSpeechMs: Long = NO_SPEECH_MS) {
    enum class Decision { Continue, End, NoSpeech }

    private var lastSpeech: Long? = null

    /** Whether speech has been heard. */
    val heardSpeech get() = lastSpeech != null

    /** Speech at [atMs]: a speech frame, or a new partial result. */
    fun speech(atMs: Long) {
        lastSpeech = maxOf(lastSpeech ?: atMs, atMs)
    }

    fun decide(nowMs: Long): Decision {
        val last = lastSpeech ?: return if (nowMs >= noSpeechMs) Decision.NoSpeech else Decision.Continue
        return if (nowMs - last >= silenceMs) Decision.End else Decision.Continue
    }

    /** How far through the silence before sending (0–1), for ✓'s progress; null before speech, or while speaking. */
    fun sending(nowMs: Long): Float? {
        val last = lastSpeech ?: return null
        val quiet = nowMs - last
        // A pause shorter than a breath isn't shown, so the ring doesn't flicker between words.
        if (quiet < SHOW_AFTER_MS) return null
        return (quiet.toFloat() / silenceMs).coerceIn(0f, 1f)
    }

    companion object {
        const val SILENCE_MS = 2_000L
        const val NO_SPEECH_MS = 8_000L
        const val SHOW_AFTER_MS = 300L
    }
}

/**
 * Speech in mono float PCM, frame by frame (20 ms): a frame's energy against a noise floor taken
 * from the first [CALIBRATE_MS] and then kept up to date while there's no speech. Speech starts
 * [ON_DB] above the floor and lasts while it's [OFF_DB] above (hysteresis), and counts only after
 * [MIN_SPEECH_MS] of it, so a click or a cough isn't speech. It feeds an [Endpoint].
 */
class EnergyVad(private val rate: Int = PcmRecorder.SAMPLE_RATE, val endpoint: Endpoint = Endpoint()) {
    private val frame = rate * FRAME_MS / 1000
    private val pending = FloatArray(frame)
    private var filled = 0
    private var frames = 0
    private val calibration = mutableListOf<Double>()
    private var floor = Double.NaN
    private var inRun = false
    private var runFrames = 0
    private var confirmed = false

    /** The recording's clock: ms of audio fed so far. */
    val nowMs get() = frames.toLong() * FRAME_MS

    /** Feeds [count] samples of [pcm] from [from]; the endpoint's decision after them. */
    fun feed(pcm: FloatArray, from: Int = 0, count: Int = pcm.size - from): Endpoint.Decision {
        var i = from
        val end = from + count
        while (i < end) {
            val take = minOf(frame - filled, end - i)
            pcm.copyInto(pending, filled, i, i + take)
            filled += take
            i += take
            if (filled == frame) {
                frame(energyDb(pending))
                filled = 0
            }
        }
        return endpoint.decide(nowMs)
    }

    private fun frame(db: Double) {
        frames++
        if (frames * FRAME_MS <= CALIBRATE_MS) {
            calibration += db
            floor = calibration.sorted()[calibration.size / 2]
            return
        }
        val on = maxOf(floor + ON_DB, MIN_SPEECH_DB)
        val off = maxOf(floor + OFF_DB, MIN_SPEECH_DB - (ON_DB - OFF_DB))
        inRun = if (inRun) db > off else db > on
        if (inRun) {
            runFrames++
            if (runFrames * FRAME_MS >= MIN_SPEECH_MS) confirmed = true
            if (confirmed) endpoint.speech(nowMs)
        } else {
            runFrames = 0
            confirmed = false
            // The floor follows the room: down at once, up slowly, only on frames that aren't speech.
            floor = if (db < floor) db else floor + FLOOR_RISE * (db - floor)
        }
    }

    companion object {
        const val FRAME_MS = 20
        const val CALIBRATE_MS = 300
        const val ON_DB = 12.0
        const val OFF_DB = 6.0
        const val MIN_SPEECH_MS = 300
        /** Never speech below this (dBFS), however quiet the room: breathing, hiss. */
        const val MIN_SPEECH_DB = -50.0
        private const val FLOOR_RISE = 0.05

        fun energyDb(frame: FloatArray): Double {
            var sum = 0.0
            for (x in frame) sum += x.toDouble() * x
            return 10 * log10(sum / frame.size + 1e-12)
        }
    }
}
