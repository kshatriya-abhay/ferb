package dev.abhay.assistant.reminders

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import org.junit.Test

/** Clearing a reminder from the Reminders tab, and its Undo. */
class ClearReminderTest {
    private val now = LocalDateTime.of(2026, 10, 14, 13, 0)

    private class MemoryStore : ReminderStore {
        var saved = emptyList<Reminder>()
        override fun load() = saved
        override fun save(reminders: List<Reminder>) { saved = reminders }
    }

    @Test
    fun aRepeatIsClearedEntirelyAndUndoBringsItBackAsItWas() {
        val store = MemoryStore()
        val book = ReminderBook(store) { now }
        val r = book.add("Bins", LocalDateTime.of(2026, 10, 19, 8, 0), Repeat.Weekly)
        book.add("Call mum", now.plusHours(2))
        val snoozed = book.snooze(r.id)!!
        val cleared = book.clear(r.id)!!
        assertThat(cleared).isEqualTo(snoozed)
        assertThat(book.upcoming().map { it.title }).containsExactly("Call mum")
        book.restore(cleared)
        assertThat(store.saved.single { it.id == r.id }).isEqualTo(snoozed)
        assertThat(book.upcoming().map { it.title }).containsExactly("Bins", "Call mum").inOrder()
    }

    @Test
    fun nothingToClear() {
        assertThat(ReminderBook(MemoryStore()) { now }.clear("nope")).isNull()
    }
}
