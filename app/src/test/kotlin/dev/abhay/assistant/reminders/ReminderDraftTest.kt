package dev.abhay.assistant.reminders

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Test

/** The New reminder page's draft: its default time, when Save is on, the repeats, and saving. */
class ReminderDraftTest {
    private val now = LocalDateTime.of(2026, 10, 14, 13, 20) // a Wednesday

    private class MemoryStore : ReminderStore {
        var saved = emptyList<Reminder>()
        override fun load() = saved
        override fun save(reminders: List<Reminder>) { saved = reminders }
    }

    @Test
    fun itStartsAtTheNextWholeHour() {
        assertThat(ReminderDraft.new(now).at).isEqualTo(LocalDateTime.of(2026, 10, 14, 14, 0))
        assertThat(ReminderDraft.new(LocalDateTime.of(2026, 10, 14, 13, 0)).at).isEqualTo(LocalDateTime.of(2026, 10, 14, 14, 0))
        // Late in the evening: tomorrow's first hour.
        assertThat(ReminderDraft.new(LocalDateTime.of(2026, 10, 14, 23, 30)).at).isEqualTo(LocalDateTime.of(2026, 10, 15, 0, 0))
        assertThat(ReminderDraft.new(now).title).isEmpty()
        assertThat(ReminderDraft.new(now).repeat).isEqualTo(RepeatChoice.None)
    }

    @Test
    fun saveNeedsATitleAndATimeToCome() {
        val draft = ReminderDraft.new(now)
        assertThat(draft.valid(now)).isFalse()
        assertThat(draft.copy(title = "   ").valid(now)).isFalse()
        assertThat(draft.copy(title = "Call mum").valid(now)).isTrue()
        val past = draft.copy(title = "Call mum", time = LocalTime.of(13, 0))
        assertThat(past.valid(now)).isFalse()
        assertThat(past.problem(now)).isEqualTo("That time has passed")
        assertThat(past.copy(date = LocalDate.of(2026, 10, 15)).valid(now)).isTrue()
        assertThat(draft.problem(now)).isNull()
    }

    @Test
    fun theRepeatChoices() {
        val monday = LocalDate.of(2026, 10, 19)
        assertThat(RepeatChoice.entries.map { it.label(monday) })
            .containsExactly("Doesn't repeat", "Daily", "Weekly on Monday", "Monthly", "Yearly").inOrder()
        assertThat(RepeatChoice.entries.map { it.repeat }).containsExactly(null, Repeat.Daily, Repeat.Weekly, Repeat.Monthly, Repeat.Yearly).inOrder()
        // The tab then says the same weekday.
        val weekly = Reminder("x", "Bins", monday.atTime(8, 0), RepeatChoice.Weekly.repeat)
        assertThat(ReminderTime.repeat(weekly)).isEqualTo("Every Monday")
    }

    @Test
    fun itSavesThroughTheBookWithoutANote() {
        val store = MemoryStore()
        val book = ReminderBook(store) { now }
        val at = LocalDateTime.of(2026, 10, 19, 8, 0)
        val saved = ReminderDraft("  Take out the bins ", at.toLocalDate(), at.toLocalTime(), RepeatChoice.Weekly).save(book, now)!!
        assertThat(saved.title).isEqualTo("Take out the bins")
        assertThat(store.saved).containsExactly(saved)
        assertThat(book.upcoming().single()).isEqualTo(Reminder(saved.id, "Take out the bins", at, Repeat.Weekly))
        // Not valid: nothing saved.
        assertThat(ReminderDraft.new(now).save(book, now)).isNull()
        assertThat(store.saved).hasSize(1)
    }
}
