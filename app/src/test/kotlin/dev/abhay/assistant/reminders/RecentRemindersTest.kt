package dev.abhay.assistant.reminders

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import org.junit.Test

/** The Reminders tab's Recent: the past day's shown and done reminders, and pruning. */
class RecentRemindersTest {
    private var now = LocalDateTime.of(2026, 10, 14, 13, 0) // a Wednesday

    private class MemoryStore : ReminderStore {
        var saved = emptyList<Reminder>()
        override fun load() = saved
        override fun save(reminders: List<Reminder>) { saved = reminders }
    }

    private val store = MemoryStore()
    private val book = ReminderBook(store) { now }

    /** A reminder due at [at] whose notification shows then. */
    private fun shown(title: String, at: LocalDateTime, repeat: Repeat? = null): Reminder {
        val r = book.add(title, at, repeat)
        val was = now
        now = at
        book.fired(r.id)
        now = was
        return r
    }

    @Test
    fun thePastDayNewestFirstDoneOrNot() {
        val oven = shown("Oven", now.minusHours(1))
        shown("Rent", now.minusHours(5))
        shown("Old", now.minusHours(25)) // more than a day ago
        book.add("Later", now.plusHours(2)) // to come: not recent
        book.done(oven.id)
        val recent = book.recent()
        assertThat(recent.map { it.reminder.title }).containsExactly("Oven", "Rent").inOrder()
        assertThat(recent.map { it.done }).containsExactly(true, false).inOrder()
        assertThat(recent.map { ReminderTime.recent(it, now) }).containsExactly("Done 1:00 pm", "Was due 8:00 am").inOrder()
        assertThat(book.upcoming().map { it.title }).containsExactly("Later")
    }

    @Test
    fun theWindowIsADay() {
        shown("Edge", now.minusHours(24))
        shown("Inside", now.minusHours(23).minusMinutes(59))
        assertThat(book.recent().map { it.reminder.title }).containsExactly("Inside")
    }

    @Test
    fun yesterdaySaysSo() {
        val late = RecentReminder(Reminder("x", "Bins", LocalDateTime.of(2026, 10, 13, 21, 0)), LocalDateTime.of(2026, 10, 13, 21, 0), done = false)
        assertThat(ReminderTime.recent(late, now)).isEqualTo("Was due yesterday 9:00 pm")
        assertThat(ReminderTime.recent(late.copy(done = true), now)).isEqualTo("Done yesterday 9:00 pm")
    }

    @Test
    fun aRepeatThatFiredIsInBothSections() {
        val pills = shown("Pills", now.minusHours(4), Repeat.Daily)
        assertThat(book.upcoming().single().at).isEqualTo(now.minusHours(4).plusDays(1))
        val recent = book.recent().single()
        assertThat(recent.reminder.id).isEqualTo(pills.id)
        assertThat(recent.time).isEqualTo(now.minusHours(4))
        assertThat(ReminderTime.recent(recent, now)).isEqualTo("Was due 9:00 am")
        // Done on its notification afterwards: still upcoming tomorrow, done in Recent.
        book.done(pills.id)
        assertThat(book.upcoming().single().at).isEqualTo(now.minusHours(4).plusDays(1))
        assertThat(book.recent().single().done).isTrue()
        // Cleared from Recent: the repeat stays scheduled.
        val before = book.dismiss(pills.id)!!
        assertThat(book.recent()).isEmpty()
        assertThat(book.upcoming()).hasSize(1)
        book.restore(before)
        assertThat(book.recent()).hasSize(1)
    }

    @Test
    fun clearingAOneOffFromRecentRemovesIt() {
        val oven = shown("Oven", now.minusHours(1))
        val before = book.dismiss(oven.id)!!
        assertThat(store.saved).isEmpty()
        book.restore(before)
        assertThat(book.recent().single().reminder).isEqualTo(before)
    }

    @Test
    fun oneOffsOverForADayArePruned() {
        shown("Shown yesterday", now.minusHours(30))
        val done = shown("Done long ago", now.minusHours(50))
        val was = now
        now = now.minusHours(49)
        book.done(done.id)
        now = was
        shown("Shown today", now.minusHours(2))
        book.add("Pills", now.minusHours(30), Repeat.Daily) // a repeat stays
        book.add("Later", now.plusHours(1))
        book.prune()
        assertThat(store.saved.map { it.title }).containsExactly("Shown today", "Pills", "Later")
    }

    @Test
    fun theTimesSurviveTheStore() {
        val file = java.io.File.createTempFile("reminders", ".json").apply { deleteOnExit() }
        val r = Reminder("a", "Oven", now, fired = true, firedAt = now, doneAt = now.plusMinutes(3))
        FileReminderStore(file).save(listOf(r))
        assertThat(FileReminderStore(file).load()).containsExactly(r)
    }
}
