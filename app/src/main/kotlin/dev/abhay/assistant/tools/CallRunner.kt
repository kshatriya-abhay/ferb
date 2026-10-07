package dev.abhay.assistant.tools

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs a reply's calls one after another. A call that opens another app's screen (an alarm, a
 * settings page) can't be followed straight away: a second intent sent while the first app's
 * screen is still up can be delivered to that screen instead of starting a new one, and dropped.
 * (On HyperOS, "set two alarms" set only the first: the second SET_ALARM arrived 14 ms later and
 * went to the clock's still-open screen.) So after each such call, the runner waits for that app
 * to take over and hand back, and only runs the next once the assistant is in front again.
 */
class CallRunner(
    private val inFront: StateFlow<Boolean>,
    private val leaveTimeoutMs: Long = 1_500,
    private val returnTimeoutMs: Long = 6_000,
) {
    /** After starting another app's screen: waits for it to come up and hand back. False if the assistant isn't back in time. */
    suspend fun awaitBack(): Boolean {
        withTimeoutOrNull(leaveTimeoutMs) { inFront.first { !it } }
        return withTimeoutOrNull(returnTimeoutMs) { inFront.first { it } } != null
    }

    /**
     * Runs [calls] in order, reporting each outcome through [report] as it happens. [execute] runs
     * one call. A call that can't run because the assistant isn't back in front fails, as do the rest.
     */
    suspend fun <T> run(calls: List<T>, opensApp: (T) -> Boolean, execute: (T) -> Outcome, report: (T, Outcome) -> Unit) {
        var mustWaitForReturn = false
        for (call in calls) {
            if (mustWaitForReturn && withTimeoutOrNull(returnTimeoutMs) { inFront.first { it } } == null) {
                report(call, Outcome.Failed("Not run: the other app was still open"))
                continue
            }
            val opens = opensApp(call)
            val outcome = execute(call)
            report(call, outcome)
            mustWaitForReturn = false
            if (opens && outcome is Outcome.Done) {
                // First the other app's screen comes up (the assistant pauses), then it goes away again.
                withTimeoutOrNull(leaveTimeoutMs) { inFront.first { !it } }
                mustWaitForReturn = true
            }
        }
    }
}
