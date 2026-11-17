package dev.abhay.assistant.testing

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.ui.AssistantViewModel
import dev.abhay.assistant.ui.ChatSession
import org.junit.Test

/** Review P2-179: each FakeApp starts fresh, so a test's result doesn't depend on the ones before it. */
class FakeAppTest {
    @Test
    fun aSaveInOneAppIsntSeenByTheNext() {
        val first = FakeApp.create()
        val name = first.toolSettings.configs.value.keys.first()
        val was = first.toolSettings.configs.value.getValue(name).enabled
        first.toolSettings.save(first.toolSettings.configs.value + (name to first.toolSettings.configs.value.getValue(name).copy(enabled = !was)))
        assertThat(first.toolSettings.configs.value.getValue(name).enabled).isEqualTo(!was)
        assertThat(FakeApp.create().toolSettings.configs.value.getValue(name).enabled).isEqualTo(was)
    }

    @Test
    fun theSharedConversationStartsEmpty() {
        ChatSession.state.value = AssistantViewModel.UiState(input = "left over")
        ChatSession.exchangeIds.set(41)
        FakeApp.create()
        assertThat(ChatSession.state.value.input).isEmpty()
        assertThat(ChatSession.exchangeIds.get()).isEqualTo(0)
    }
}
