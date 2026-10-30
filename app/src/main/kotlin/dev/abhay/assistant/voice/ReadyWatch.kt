package dev.abhay.assistant.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where waiting for Gemini Nano stands, for the screens. */
enum class GeminiWatch {
    /** Not waiting for anything. */
    Off,
    /** A download is on: AICore is asked every so often until it's ready. */
    Watching,
    /** Asked for [ReadyWatch.CAP_MS] with Ferb open and still not ready: the screens offer Try again. */
    GaveUp,
    /** It became ready while Ferb waited, in this process: the screens say so. */
    Ready,
}

/**
 * Waits for Gemini Nano to be ready by asking AICore ([check], checkStatus for both parts), not by
 * trusting the download's callbacks: AICore's model is shared across apps, and its download may be
 * done or still running whatever the callbacks said (2026-10-30).
 *
 * Asks only with Ferb in front ([inFront]): AICore blocks some background use, and nothing shows
 * it allows checkStatus from the background, so there's no check, and no notification, while
 * Ferb is away. On coming back it asks at once. It asks every [FIRST_MS], backing off to [MAX_MS]
 * while nothing changes, and at once on [nudge] (the download said it finished). It stops when
 * ready, when a part failed or can't run, or after [CAP_MS] of asking with Ferb open.
 */
class ReadyWatch(
    private val scope: CoroutineScope,
    private val inFront: StateFlow<Boolean>,
    private val now: () -> Long,
    private val check: suspend () -> GeminiSetup,
) {
    private val _state = MutableStateFlow(GeminiWatch.Off)
    val state: StateFlow<GeminiWatch> = _state.asStateFlow()

    private val nudges = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    /** Starts waiting, unless it already is. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        _state.value = GeminiWatch.Watching
        job = scope.launch { watch() }
    }

    /** Ask now (the download's callback said it finished, or Ferb came back). */
    fun nudge() {
        nudges.trySend(Unit)
    }

    /** A check made elsewhere (a screen, a resume) found it ready: done waiting, and say so. */
    fun sawReady() {
        if (_state.value == GeminiWatch.Watching || _state.value == GeminiWatch.GaveUp) {
            job?.cancel()
            _state.value = GeminiWatch.Ready
        }
    }

    private suspend fun watch() {
        var wait = FIRST_MS
        var asked = 0L
        var last: GeminiSetup? = null
        while (true) {
            val began = now()
            withTimeoutOrNull(wait) { nudges.receive() }
            if (inFront.value) asked += now() - began
            else inFront.first { it } // Away: no checks until Ferb is back, then one at once.
            val s = check()
            _state.value = when {
                s.ready -> GeminiWatch.Ready
                stops(s) -> GeminiWatch.Off
                asked >= CAP_MS -> GeminiWatch.GaveUp
                else -> GeminiWatch.Watching
            }
            if (_state.value != GeminiWatch.Watching) return
            wait = next(wait, changed = s != last)
            last = s
        }
    }

    companion object {
        const val FIRST_MS = 10_000L
        const val MAX_MS = 30_000L
        /** Half an hour of asking with Ferb open; coming back to Ferb still asks once after that. */
        const val CAP_MS = 30 * 60_000L
        /** AICore's pacing: at least this long between two of Ferb's status checks. */
        const val PACE_MS = 500L

        /** The next wait: back to [FIRST_MS] when something changed, else half as long again, up to [MAX_MS]. */
        fun next(wait: Long, changed: Boolean): Long = if (changed) FIRST_MS else minOf(wait * 3 / 2, MAX_MS)

        /** Nothing more to wait for: a part failed, can't run here, or couldn't be checked (Try again). */
        fun stops(s: GeminiSetup) = s.parts.any { it is Part.Failed || it == Part.Unavailable || it is Part.Unchecked }

        /** How long to wait before the next status check, [last] being the previous one's time. */
        fun pace(now: Long, last: Long): Long = (last + PACE_MS - now).coerceAtLeast(0)
    }
}
