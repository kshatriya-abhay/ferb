package dev.abhay.assistant.request

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.engine.ToolCall
import dev.abhay.assistant.testing.unusedContext
import dev.abhay.assistant.tools.CallRunner
import dev.abhay.assistant.tools.FollowUp
import dev.abhay.assistant.tools.Outcome
import dev.abhay.assistant.tools.Prepared
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** [CallQueue]: a reply's waiting calls run in order, one batch at a time, each status reported. */
class CallQueueTest {
    private val ran = mutableListOf<String>()
    private fun ready(name: String) = CallState(ToolCall(name, emptyMap()), Prepared.Ready(name, opensApp = false) { ran += name; Outcome.Done("$name done") }, Status.Waiting)
    private fun queue() = CallQueue(CallRunner(MutableStateFlow(true)), ::unusedContext, CoroutineScope(Dispatchers.Default))

    /** An exchange's calls, changed only through [set], as the view model's are. */
    private class Exchange(vararg calls: CallState) {
        var calls = calls.toList()
        val seen = mutableListOf<Pair<Int, Status>>()
        @Synchronized fun set(i: Int, status: Status) { seen += i to status; calls = calls.mapIndexed { j, c -> if (j == i) c.copy(status = status) else c } }
    }

    @Test
    fun theWaitingCallsRunInOrderAndEachReportsItsOutcome() = runBlocking {
        val e = Exchange(
            ready("torch"),
            ready("skipped").copy(status = Status.Skipped),
            CallState(ToolCall("alarm", emptyMap()), Prepared.Invalid("No time given"), Status.Waiting),
            ready("volume"),
        )
        queue().run({ e.calls }, e::set)!!.join()
        assertThat(ran).containsExactly("torch", "volume").inOrder()
        assertThat(e.calls.map { it.status }).containsExactly(
            Status.Finished(Outcome.Done("torch done")), Status.Skipped,
            Status.Finished(Outcome.Failed("No time given")), Status.Finished(Outcome.Done("volume done")),
        ).inOrder()
        // Queued first, all together, then each finished in turn.
        assertThat(e.seen.take(3).map { it.second }).containsExactly(Status.Queued, Status.Queued, Status.Queued)
        assertThat(e.seen.drop(3).map { it.first }).containsExactly(0, 2, 3).inOrder()
    }

    @Test
    fun nothingWaitingRunsNothing() {
        val e = Exchange(ready("torch").copy(status = Status.Skipped))
        assertThat(queue().run({ e.calls }, e::set)).isNull()
        assertThat(e.seen).isEmpty()
    }

    @Test
    fun twoRepliesBatchesDontInterleave() = runBlocking {
        val q = queue()
        val first = Exchange(ready("a1"), ready("a2"), ready("a3"))
        val second = Exchange(ready("b1"), ready("b2"))
        listOfNotNull(q.run({ first.calls }, first::set), q.run({ second.calls }, second::set)).joinAll()
        // One batch, then the other, never mixed.
        assertThat(ran.joinToString(" ")).isAnyOf("a1 a2 a3 b1 b2", "b1 b2 a1 a2 a3")
    }

    @Test
    fun aFollowUpIsQueuedThenFinishedAndAFailureSaysWhy() = runBlocking {
        val seen = mutableListOf<Status>()
        queue().followUp(FollowUp("Turn off") { Outcome.Done("off") }) { synchronized(seen) { seen += it } }.join()
        queue().followUp(FollowUp("Undo") { error("the clock app went away") }) { synchronized(seen) { seen += it } }.join()
        assertThat(seen).containsExactly(Status.Queued, Status.Finished(Outcome.Done("off")), Status.Queued, Status.Finished(Outcome.Failed("the clock app went away"))).inOrder()
    }
}
