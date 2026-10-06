package dev.abhay.assistant.tools

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CallRunnerTest {
    private data class Call(val name: String, val opensApp: Boolean)

    @Test
    fun aCallAfterOneThatOpensAnAppWaitsUntilTheAssistantIsBack() = runTest {
        val inFront = MutableStateFlow(true)
        val log = mutableListOf<String>()
        val calls = listOf(Call("alarm 8", true), Call("alarm 9", true))
        launch {
            CallRunner(inFront).run(calls, { it.opensApp }, { call ->
                log += "run ${call.name}"
                inFront.value = false // the clock app's screen comes up
                Outcome.Done(call.name)
            }, { _, _ -> })
        }
        delay(100)
        assertThat(log).containsExactly("run alarm 8") // the second waits while the clock is in front
        log += "back"
        inFront.value = true
        delay(100)
        assertThat(log).containsExactly("run alarm 8", "back", "run alarm 9").inOrder()
    }

    @Test
    fun callsThatDontOpenAppsRunStraightAfterEachOther() = runTest {
        val inFront = MutableStateFlow(true)
        val outcomes = mutableListOf<Outcome>()
        CallRunner(inFront).run(listOf(Call("torch", false), Call("volume", false)), { it.opensApp }, { Outcome.Done(it.name) }, { _, o -> outcomes += o })
        assertThat(outcomes).containsExactly(Outcome.Done("torch"), Outcome.Done("volume")).inOrder()
    }

    @Test
    fun ifTheAssistantDoesntComeBackTheRestAreReportedNotRun() = runTest {
        val inFront = MutableStateFlow(true)
        val outcomes = mutableListOf<Outcome>()
        CallRunner(inFront, leaveTimeoutMs = 100, returnTimeoutMs = 1_000).run(
            listOf(Call("open WhatsApp", true), Call("torch", false)),
            { it.opensApp },
            { inFront.value = false; Outcome.Done(it.name) }, // the user stays in WhatsApp
            { _, o -> outcomes += o },
        )
        assertThat(outcomes[0]).isEqualTo(Outcome.Done("open WhatsApp"))
        assertThat((outcomes[1] as Outcome.Failed).reason).contains("Not run")
    }

    @Test
    fun aFailedLaunchDoesntWait() = runTest {
        val inFront = MutableStateFlow(true)
        val outcomes = mutableListOf<Outcome>()
        CallRunner(inFront).run(listOf(Call("open nothing", true), Call("torch", false)), { it.opensApp },
            { if (it.name == "torch") Outcome.Done("torch") else Outcome.Failed("No app") }, { _, o -> outcomes += o })
        assertThat(outcomes).containsExactly(Outcome.Failed("No app"), Outcome.Done("torch")).inOrder()
    }
}
