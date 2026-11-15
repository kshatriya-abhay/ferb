package dev.abhay.assistant.request

import android.content.Context
import dev.abhay.assistant.engine.ToolCall
import dev.abhay.assistant.tools.CallRunner
import dev.abhay.assistant.tools.FollowUp
import dev.abhay.assistant.tools.FollowUpEnv
import dev.abhay.assistant.tools.Outcome
import dev.abhay.assistant.tools.Prepared
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One call from a reply: checked, and what became of it. */
data class CallState(val call: ToolCall, val prepared: Prepared, val status: Status)

sealed interface Status {
    /** Waiting for the user's Run or Skip. */
    data object Waiting : Status
    /** About to run, after the calls before it. */
    data object Queued : Status
    data object Skipped : Status
    data class Finished(val outcome: Outcome) : Status
}

/**
 * Where a request's calls run, for the process, one batch at a time: not a screen's, so closing
 * the assistant sheet while the clock app is handing back doesn't cancel the calls after it.
 * [context] is the screen to run in (the assistant's if it's in front, else the app).
 */
class CallQueue(
    private val runner: CallRunner,
    private val context: () -> Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val running = Mutex()

    /**
     * Runs the waiting calls among [calls], one at a time ([CallRunner]): each is marked queued, then
     * finished with its outcome, through [set], as soon as it's known. A call that failed its checks
     * reports why. [calls] is the reply's calls as they were when it was queued: queued calls can't
     * change, and the reply may be cleared from the chat meanwhile, so nothing is read back. Null
     * when none was waiting.
     */
    fun run(calls: List<CallState>, set: (index: Int, status: Status) -> Unit): Job? {
        val waiting = calls.withIndex().filter { it.value.status == Status.Waiting }
        if (waiting.isEmpty()) return null
        waiting.forEach { set(it.index, Status.Queued) }
        return scope.launch {
            running.withLock {
                runner.run(
                    calls = waiting,
                    opensApp = { (it.value.prepared as? Prepared.Ready)?.opensApp == true },
                    execute = {
                        when (val p = it.value.prepared) {
                            is Prepared.Ready -> p.run(context())
                            is Prepared.Invalid -> Outcome.Failed(p.reason)
                        }
                    },
                    report = { call, outcome -> set(call.index, Status.Finished(outcome)) },
                )
            }
        }
    }

    /** Runs a result's button ("Turn off" after the torch went on): queued while it runs, then its outcome. */
    fun followUp(action: FollowUp, set: (Status) -> Unit): Job {
        set(Status.Queued)
        return scope.launch {
            val outcome = try {
                action.run(FollowUpEnv(context(), awaitBack = runner::awaitBack))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome.Failed(e.message ?: e.toString())
            }
            set(Status.Finished(outcome))
        }
    }
}
