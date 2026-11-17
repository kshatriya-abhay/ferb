package dev.abhay.assistant.engine

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.testing.FakeApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** [AssistantEngine]'s own rules, with a fake native engine. */
class AssistantEngineTest {
    private class Native : EngineCalls {
        var loads = 0
        val loading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun load(asset: String) { loading.complete(Unit); release.await(); loads++ }
        override suspend fun init(systemFacts: String, toolsJson: String) = 42
        override suspend fun complete(request: String) = "{}"
        override suspend fun transcribe(pcm: FloatArray, language: String?) = "{}"
    }

    @Test
    fun aLoadAScreenStopsWaitingForStillCountsAndIsntRepeated() = runBlocking<Unit> {
        // Review P2-89: the cancel was caught as a failure, loadMs stayed unset though the model had
        // loaded, and the next configure loaded Needle 3 a second time.
        val native = Native()
        var now = 0L
        val engine = AssistantEngine(FakeApp.allocate(FakeApp.PrefsContext::class.java), native) { now }
        val screen = launch(Dispatchers.Default) { engine.configure(listOf("set_alarm"), "[]") }
        native.loading.await()
        now = 300
        // The screen goes while the model is still loading; then the load finishes.
        screen.cancel()
        native.release.complete(Unit)
        screen.join()
        assertThat(engine.state.value).isInstanceOf(AssistantEngine.State.Ready::class.java)
        assertThat((engine.state.value as AssistantEngine.State.Ready).loadMs).isEqualTo(300)
        engine.configure(listOf("set_timer"), "[]")
        assertThat(native.loads).isEqualTo(1)
        assertThat((engine.state.value as AssistantEngine.State.Ready).tools).containsExactly("set_timer")
    }
}
