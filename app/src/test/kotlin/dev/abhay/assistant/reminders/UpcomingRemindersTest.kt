package dev.abhay.assistant.reminders

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.notes.MemoryNotes
import java.time.LocalDateTime
import org.junit.Test

/** The Notes screen's Reminders tab: what it lists, in what order, and how. */
class UpcomingRemindersTest {
    private val now = LocalDateTime.of(2026, 10, 14, 13, 0) // a Wednesday afternoon

    private class MemoryStore : ReminderStore {
        var saved = emptyList<Reminder>()
        override fun load() = saved
        override fun save(reminders: List<Reminder>) { saved = reminders }
    }

    private fun book(notes: MemoryNotes = MemoryNotes()) = ReminderBook(MemoryStore(), notes) { now }

    @Test
    fun soonestFirstAndOnlyWhatsStillToCome() {
        val reminders = book()
        reminders.add("Next week", now.plusDays(7))
        reminders.add("Pills", now.plusHours(20), Repeat.Daily)
        val gone = reminders.add("Oven", now.minusMinutes(5))!!
        reminders.fired(gone.id) // shown, waiting for Done: not upcoming
        reminders.add("Missed", now.minusHours(1)) // its time went by: not upcoming
        val snoozed = reminders.add("Call", now.plusDays(2))!!
        reminders.snooze(snoozed.id) // its next notification is in 10 min: listed by that
        assertThat(reminders.upcoming().map { it.title }).containsExactly("Call", "Pills", "Next week").inOrder()
    }

    @Test
    fun noReminders() {
        assertThat(book().upcoming()).isEmpty()
        assertThat(ReminderTime.NONE_UPCOMING).isEqualTo("No upcoming reminders. Ask Ferb to remind you.")
    }

    @Test
    fun theNextTimeIsFriendly() {
        fun next(at: LocalDateTime) = ReminderTime.next(at, now)
        assertThat(next(LocalDateTime.of(2026, 10, 14, 21, 0))).isEqualTo("Today 9:00 pm")
        assertThat(next(LocalDateTime.of(2026, 10, 15, 7, 0))).isEqualTo("Tomorrow 7:00 am")
        assertThat(next(LocalDateTime.of(2026, 10, 28, 18, 30))).isEqualTo("Wed 28 Oct, 6:30 pm")
        assertThat(next(LocalDateTime.of(2027, 1, 5, 12, 0))).isEqualTo("Tue 5 Jan 2027, 12:00 pm")
    }

    @Test
    fun theRepeatIsSaid() {
        fun said(repeat: Repeat?) = ReminderTime.repeat(Reminder("x", "x", LocalDateTime.of(2026, 10, 19, 9, 0), repeat)) // a Monday
        assertThat(said(null)).isNull()
        assertThat(said(Repeat.Daily)).isEqualTo("Daily")
        assertThat(said(Repeat.Weekly)).isEqualTo("Every Monday")
        assertThat(said(Repeat.Monthly)).isEqualTo("Monthly")
        assertThat(said(Repeat.Yearly)).isEqualTo("Yearly")
    }

    @Test
    fun aReminderWithANoteComesWithIt() {
        val notes = MemoryNotes("Rent" to "Pay it.\n")
        val reminders = book(notes)
        reminders.add("Rent", now.plusDays(1), note = notes.list().single())
        reminders.add("Stretch", now.plusHours(1))
        assertThat(reminders.upcomingWithNotes().map { (r, n) -> r.title to n?.name }).containsExactly("Stretch" to null, "Rent" to "Rent").inOrder()
    }

    @Test
    fun aChangeIsSignalledSoTheTabRefreshes() {
        val reminders = book()
        val before = reminders.changes.value
        val r = reminders.add("Oven", now.plusMinutes(1))!!
        assertThat(reminders.changes.value).isGreaterThan(before)
        val added = reminders.changes.value
        reminders.fired(r.id)
        assertThat(reminders.changes.value).isGreaterThan(added)
    }
}
