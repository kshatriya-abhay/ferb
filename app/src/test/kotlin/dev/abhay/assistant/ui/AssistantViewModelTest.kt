package dev.abhay.assistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.AssistantApp
import dev.abhay.assistant.engine.Decision
import dev.abhay.assistant.engine.NeedleReply
import dev.abhay.assistant.engine.ToolCall
import dev.abhay.assistant.request.CallQueue
import dev.abhay.assistant.request.CallState
import dev.abhay.assistant.request.Status
import dev.abhay.assistant.testing.FakeApp
import dev.abhay.assistant.tools.CallRunner
import dev.abhay.assistant.tools.Outcome
import dev.abhay.assistant.tools.Prepared
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** [AssistantViewModel] with the conversation the chat and the sheet share ([ChatSession]). */
class AssistantViewModelTest {
    private val main = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(main)

    @After fun tearDown() {
        ChatSession.state.value = AssistantViewModel.UiState()
        Dispatchers.resetMain()
    }

    private fun viewModel(app: AssistantApp, store: ViewModelStore) = ViewModelProvider(store, object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AssistantViewModel(app) as T
    })[AssistantViewModel::class.java]

    @Test
    fun aScreenClosedWhileItsVoiceRequestIsHeardLeavesTheConversationFree() {
        // Review P2-18: the screen's scope was cancelled mid-transcription, and busy stayed set for both screens.
        val app = FakeApp.create()
        // Whistle busy with another clip: this request waits for the engine.
        (FakeApp.field(app.engine, "lock") as Mutex).tryLock()
        val sheet = ViewModelStore()
        val vm = viewModel(app, sheet)
        main.scheduler.advanceUntilIdle()

        ChatSession.state.value = AssistantViewModel.UiState(voice = AssistantViewModel.Voice.Recorded(1_000, speechLike()))
        vm.send()
        main.scheduler.advanceUntilIdle()
        assertThat(ChatSession.state.value.busy).isTrue()

        sheet.clear() // the sheet swiped away: AssistActivity finishes
        main.scheduler.advanceUntilIdle()
        val after = ChatSession.state.value
        assertThat(after.busy).isFalse()
        assertThat(after.pending).isNull()
        assertThat(after.transcribing).isFalse()
    }

    @Test
    fun clearingTheChatWhileItsCallsRunDoesntCrash() {
        // Review P2-19: the queue read the exchange back after each wait, and threw once Clear had removed it.
        val app = FakeApp.create()
        val crashes = mutableListOf<Throwable>()
        val queue = CallQueue(CallRunner(MutableStateFlow(true)), { FakeApp.allocate(FakeApp.PrefsContext::class.java) },
            CoroutineScope(SupervisorJob() + main + CoroutineExceptionHandler { _, e -> crashes += e }))
        FakeApp.set(app, "callQueue\$delegate", lazyOf(queue))
        val store = ViewModelStore()
        val vm = viewModel(app, store)
        main.scheduler.advanceUntilIdle()

        // Two calls; the first opens another app, which never comes up here, so Ferb waits 1.5 s for it.
        var ran = 0
        val opens = Prepared.Ready("Open the clock", opensApp = true) { ran++; Outcome.Done("Opened") }
        val call = ToolCall("set_alarm", emptyMap())
        val reply = NeedleReply(listOf(call, call), emptyList(), "", 0.9, 0.0, 0.0, null)
        val exchange = AssistantViewModel.Exchange(7, "alarm at 7 and 8", reply, Decision.Run, 0.5, null, emptyList(),
            listOf(CallState(call, opens, Status.Waiting), CallState(call, opens, Status.Waiting)), 0)
        ChatSession.state.value = AssistantViewModel.UiState(history = listOf(exchange))
        vm.run(7)
        main.scheduler.advanceTimeBy(500)
        vm.clearHistory()
        main.scheduler.advanceUntilIdle()

        assertThat(crashes).isEmpty()
        assertThat(ran).isEqualTo(2)
        store.clear()
    }

    @Test
    fun aNoticeIsShownOnceAndTheSameTextTwiceShowsTwice() {
        // Review P2-122: a notice was state shown as an event, so it showed again on every return to
        // the chat, and the same text twice (the mic refused twice) didn't show the second time.
        val vm = viewModel(FakeApp.create(), ViewModelStore())
        vm.notify("Ferb needs the microphone.")
        val first = vm.state.value.notice!!
        vm.noticeShown(first.id)
        assertThat(vm.state.value.notice).isNull()
        vm.notify("Ferb needs the microphone.")
        assertThat(vm.state.value.notice!!.id).isNotEqualTo(first.id)
        // A screen that showed an older one doesn't clear a newer one.
        vm.noticeShown(first.id)
        assertThat(vm.state.value.notice).isNotNull()
    }

    private fun speechLike() = FloatArray(16_000) { i -> if (i < 8_000) 0f else if (i % 2 == 0) 0.5f else -0.5f }
}
