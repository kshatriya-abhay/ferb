package dev.abhay.assistant.ui

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.engine.Decision
import dev.abhay.assistant.engine.NeedleReply
import org.junit.Test

/** The assistant's sheet shows only what was asked since it opened, the newest few; the rest is in the full chat. */
class SheetHistoryTest {
    private val none = NeedleReply(emptyList(), emptyList(), "", null, emptyList(), false, 0.0, 0.0, 0.0, null)
    private fun exchange(id: Long) = AssistantViewModel.Exchange(id, "request $id", none, Decision.Refuse, 0.5, false, false, false, emptyList(), emptyList(), 10)

    @Test
    fun onlySinceTheSheetOpenedAndTheNewestFew() {
        val state = AssistantViewModel.UiState(history = (0L..5L).map(::exchange))
        assertThat(sheetHistory(state, since = 6)).isEmpty()
        assertThat(sheetHistory(state, since = 5).map { it.id }).containsExactly(5L)
        assertThat(sheetHistory(state, since = 0).map { it.id }).containsExactly(4L, 5L).inOrder()
        assertThat(SHEET_EXCHANGES).isEqualTo(2)
    }
}
