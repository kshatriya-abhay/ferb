package dev.abhay.assistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.AssistantApp
import dev.abhay.assistant.engine.Decision
import dev.abhay.assistant.engine.NeedleReply
import dev.abhay.assistant.notes.SafNotes
import dev.abhay.assistant.onboarding.Onboarding
import dev.abhay.assistant.onboarding.OnboardingState
import dev.abhay.assistant.testing.FakeApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Review P2-133: Settings' tool draft against a tool the chat turned on (its "Turn on" under a reply). */
class SettingsDraftTest {
    private val main = StandardTestDispatcher()
    private val store = ViewModelStore()

    @Before fun setUp() {
        FakeApp.prefs.clear() // FakeApp keeps its preferences per process: each test starts from the defaults
        Dispatchers.setMain(main)
    }

    @After fun tearDown() {
        store.clear()
        ChatSession.state.value = AssistantViewModel.UiState()
        Dispatchers.resetMain()
    }

    private fun app(): AssistantApp = FakeApp.create().also { app ->
        val context = FakeApp.allocate(FakeApp.PrefsContext::class.java)
        FakeApp.set(app, "onboarding\$delegate", lazyOf(Onboarding({ OnboardingState(welcomed = true) }, {})))
        FakeApp.set(app, "notes\$delegate", lazyOf(SafNotes(context, granted = { false })))
    }

    private fun chat(app: AssistantApp) = ViewModelProvider(store, object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AssistantViewModel(app) as T
    })[AssistantViewModel::class.java]

    /** ChatActivity makes both view models when it opens; the user then taps "Turn on" under a reply. */
    private fun turnOnFromTheChat(app: AssistantApp, tool: String) {
        val reply = NeedleReply(emptyList(), emptyList(), "", 0.0, 0.0, 0.0, null)
        val exchange = AssistantViewModel.Exchange(7, "call mum", reply, Decision.Run, 0.0, null, emptyList(),
            emptyList(), 0, turnOn = listOf(tool))
        ChatSession.state.value = AssistantViewModel.UiState(history = listOf(exchange))
        chat(app).turnOn(7, tool)
        main.scheduler.runCurrent()
        assertThat(app.toolSettings.configs.value.getValue(tool).enabled).isTrue()
    }

    @Test
    fun aToolTurnedOnInTheChatIsNotAnUnsavedChangeInSettings() {
        val app = app()
        val settings = SettingsViewModel(app)
        turnOnFromTheChat(app, "make_call")

        // Settings → Tools: the user changed nothing, so its switch shows on and there's no "Unsaved changes" bar.
        assertThat(settings.draft.value.getValue("make_call").enabled).isTrue()
        assertThat(settings.draft.value == settings.saved.value).isTrue()
    }

    @Test
    fun savingAnotherToolsChangeKeepsTheToolTurnedOnInTheChat() {
        val app = app()
        val settings = SettingsViewModel(app)
        turnOnFromTheChat(app, "make_call")

        // Settings → Tools: the user turns on another tool and taps Save.
        settings.update("send_text") { it.copy(enabled = true) }
        settings.save()

        assertThat(app.toolSettings.configs.value.getValue("send_text").enabled).isTrue()
        assertThat(app.toolSettings.configs.value.getValue("make_call").enabled).isTrue()
    }
}
