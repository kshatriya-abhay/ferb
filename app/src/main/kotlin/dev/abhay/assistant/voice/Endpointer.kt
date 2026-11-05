package dev.abhay.assistant.voice

import kotlin.math.log10

/**
 * When a spoken request is over, so it's sent without ✓ (2026-10-24): it waits for speech, then
 * ends after [silenceMs] without it, or [graceMs] while less than [GRACE_UNTIL_MS] of speech has
 * been heard (an opening pause: "Remind me… um…"); with no speech at all by [noSpeechMs], it gives
 * up (nothing is sent). Time is in ms on the recording's own clock ([EnergyVad] for PCM) or the
 * wall clock (Gemini Nano, whose responses are its speech: [NANO_SILENCE_MS]). ✓ and ✕ still
 * finish it at once.
 */
class Endpoint(
    val silenceMs: Long = SILENCE_MS,
    val noSpeechMs: Long = NO_SPEECH_MS,
    val graceMs: Long = GRACE_MS,
) {
    enum class Decision { Continue, End, NoSpeech }

    private var firstSpeech: Long? = null
    private var lastSpeech: Long? = null

    /** When speech was last heard, if it has been. */
    val lastSpeechMs get() = lastSpeech

    /** Whether speech has been heard. */
    val heardSpeech get() = lastSpeech != null

    /** Speech at [atMs]: a speech frame, or a response from the recogniser. */
    fun speech(atMs: Long) {
        if (firstSpeech == null) firstSpeech = atMs
        lastSpeech = maxOf(lastSpeech ?: atMs, atMs)
    }

    /** The silence that ends it now: longer until [GRACE_UNTIL_MS] of speech (first to last) has been heard. */
    fun window(): Long {
        val first = firstSpeech ?: return silenceMs
        val heard = lastSpeech!! - first
        return if (heard < GRACE_UNTIL_MS) maxOf(graceMs, silenceMs) else silenceMs
    }

    fun decide(nowMs: Long): Decision {
        val last = lastSpeech ?: return if (nowMs >= noSpeechMs) Decision.NoSpeech else Decision.Continue
        return if (nowMs - last >= window()) Decision.End else Decision.Continue
    }

    /** How far through the silence before sending (0–1), for ✓'s progress; null before speech, or while speaking. */
    fun sending(nowMs: Long): Float? {
        val last = lastSpeech ?: return null
        val quiet = nowMs - last
        // A pause shorter than a breath isn't shown, so the ring doesn't flicker between words.
        if (quiet < SHOW_AFTER_MS) return null
        return (quiet.toFloat() / window()).coerceIn(0f, 1f)
    }

    companion object {
        /**
         * 1.8 s (2026-11-05, from 1 s): at 1 s, replaying EnergyVad over the voice bench's 81 clips,
         * 22 ended before the speaker had finished; 1.8 s, with [EnergyVad.RESUME_MS] and the grace
         * period, 1 at most (EndpointReplayTest). It was 2 s until 2026-10-25.
         */
        const val SILENCE_MS = 1_800L
        /** Gemini Nano: 2 s after its last response, as its partials lag the speech and come in bursts. */
        const val NANO_SILENCE_MS = 2_000L
        /** The silence allowed while less than [GRACE_UNTIL_MS] of speech has been heard. */
        const val GRACE_MS = 2_500L
        const val GRACE_UNTIL_MS = 1_500L
        const val NO_SPEECH_MS = 8_000L
        const val SHOW_AFTER_MS = 300L
    }
}

/**
 * Speech in mono float PCM, frame by frame (20 ms): a frame's energy against a noise floor taken
 * from the first [CALIBRATE_MS] and then kept up to date while there's no speech. Speech starts
 * [ON_DB] above the floor and lasts while it's [OFF_DB] above (hysteresis), and counts only after
 * [MIN_SPEECH_MS] of it, so a click or a cough isn't speech; once speech has been heard, after
 * [RESUME_MS], so a short word after a pause ("uh", "and", "kal") keeps it going. It feeds an [Endpoint].
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
    private var heardAny = false

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
            if (runFrames * FRAME_MS >= (if (heardAny) RESUME_MS else MIN_SPEECH_MS)) { confirmed = true; heardAny = true }
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
        /** After the first speech: a shorter run counts again (at 300 ms each time, 22 of the 81 bench clips ended early). */
        const val RESUME_MS = 100
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
