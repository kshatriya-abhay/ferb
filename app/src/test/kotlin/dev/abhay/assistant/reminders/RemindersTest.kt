package dev.abhay.assistant.reminders

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.notes.MemoryNotes
import java.time.LocalDateTime
import org.junit.Test

class RemindersTest {
    private val now = LocalDateTime.of(2026, 10, 14, 13, 0) // a Wednesday afternoon

    private class MemoryStore : ReminderStore {
        var saved = emptyList<Reminder>()
        override fun load() = saved
        override fun save(reminders: List<Reminder>) { saved = reminders }
    }

    private fun book(store: MemoryStore = MemoryStore()) = ReminderBook(store) { now }

    @Test
    fun timesAsSaid() {
        fun at(text: String) = ReminderTime.read(text, now)
        assertThat(at("remind me in 20 minutes to check the oven")).isEqualTo(now.plusMinutes(20))
        assertThat(at("in an hour")).isEqualTo(now.plusHours(1))
        assertThat(at("tomorrow at 9")).isEqualTo(LocalDateTime.of(2026, 10, 15, 9, 0))
        assertThat(at("on Friday to pay the rent")).isEqualTo(LocalDateTime.of(2026, 10, 16, 9, 0)) // a day alone: 09:00
        assertThat(at("at 6 pm")).isEqualTo(LocalDateTime.of(2026, 10, 14, 18, 0))
        assertThat(at("at 8 am")).isEqualTo(LocalDateTime.of(2026, 10, 15, 8, 0)) // passed today: tomorrow
        assertThat(at("to call mum")).isNull()
    }

    @Test
    fun theNotesRemindKey() {
        assertThat(ReminderTime.parse("2026-10-20 09:30")).isEqualTo(LocalDateTime.of(2026, 10, 20, 9, 30))
        assertThat(ReminderTime.parse("2026-10-20")).isEqualTo(LocalDateTime.of(2026, 10, 20, 9, 0)) // a date alone: 09:00
        assertThat(ReminderTime.parse("\"2026-10-20T07:00\"")).isEqualTo(LocalDateTime.of(2026, 10, 20, 7, 0))
        assertThat(ReminderTime.parse("soon")).isNull()
        assertThat(Repeat.of("weekly")).isEqualTo(Repeat.Weekly)
        assertThat(Repeat.of("every month")).isEqualTo(Repeat.Monthly)
    }

    @Test
    fun aOneOffIsMarkedDoneAndNoLongerUpcoming() {
        val store = MemoryStore()
        val reminders = book(store)
        val r = reminders.add("Check the oven", now.minusMinutes(1))
        assertThat(store.saved).containsExactly(r)
        assertThat(r.id).isNotEmpty()
        reminders.fired(r.id)
        // Done without a repeat: kept a day for Recent, done, never shown again.
        val done = reminders.done(r.id)!!
        assertThat(done.doneAt).isEqualTo(now)
        assertThat(reminders.upcoming()).isEmpty()
        assertThat(reminders.missed()).isEmpty()
        // Undo of adding it still removes it outright.
        reminders.remove(r.id)
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun aMonthlyRepeatDoneOnItsNotificationMovesAMonth() {
        val store = MemoryStore()
        val r = book(store).add("Rent", LocalDateTime.of(2026, 11, 1, 9, 0), Repeat.Monthly)
        // Done on its notification (Nov 1, 09:05): next month.
        assertThat(ReminderBook(store) { LocalDateTime.of(2026, 11, 1, 9, 5) }.done(r.id)!!.at).isEqualTo(LocalDateTime.of(2026, 12, 1, 9, 0))
    }

    @Test
    fun aRepeatSkipsTimesAlreadyPast() {
        val reminders = book()
        val r = reminders.add("Water the plants", now.minusDays(3), Repeat.Daily)!!
        assertThat(reminders.done(r.id)!!.at).isEqualTo(now.minusDays(3).plusDays(4))
    }

    @Test
    fun aRepeatThatFiredComesBackWithoutDone() {
        val reminders = book()
        val r = reminders.add("Plants", now.minusMinutes(1), Repeat.Daily)
        // Fired a minute ago: tomorrow, and scheduled, as it's due after now.
        val next = reminders.fired(r.id)!!
        assertThat(next.at).isEqualTo(now.minusMinutes(1).plusDays(1))
        assertThat(next.due.isAfter(now)).isTrue()
        // Done afterwards doesn't skip a day.
        assertThat(reminders.done(r.id)!!.at).isEqualTo(next.at)
    }

    @Test
    fun aSnoozedRepeatThatFiredAgainKeepsItsNextTime() {
        val store = MemoryStore()
        val reminders = book(store = store)
        val r = reminders.add("Stretch", now.minusMinutes(10), Repeat.Daily)!!
        reminders.fired(r.id) // tomorrow
        reminders.snooze(r.id) // the notification again in 10 minutes
        val later = ReminderBook(store) { now.plusMinutes(11) }
        // The snooze's notification: the snooze goes, the reminder stays due tomorrow.
        val after = later.fired(r.id)!!
        assertThat(after.snoozed).isNull()
        assertThat(after.due).isEqualTo(now.minusMinutes(10).plusDays(1))
    }

    @Test
    fun repeatsMissedWhileThePhoneWasOffCatchUp() {
        val reminders = book()
        val daily = reminders.add("Pills", now.minusHours(5), Repeat.Daily)!! // due this morning, phone off
        val oneOff = reminders.add("Oven", now.minusHours(1))!!
        val later = reminders.add("Call", now.plusHours(2), Repeat.Weekly)!!
        val after = reminders.catchUp().associateBy { it.id }
        assertThat(after.getValue(daily.id).at).isEqualTo(now.minusHours(5).plusDays(1))
        assertThat(after.getValue(oneOff.id)).isEqualTo(oneOff) // a one-off waits for Done
        assertThat(after.getValue(later.id)).isEqualTo(later)
    }

    @Test
    fun aOneOffMissedWhileThePhoneWasOffShowsLateOnce() {
        val reminders = book()
        val missed = reminders.add("Pay the rent", now.minusHours(4))!! // 9:00, phone off
        val shown = reminders.add("Oven", now.minusMinutes(5))!!
        reminders.fired(shown.id) // its notification showed on time
        reminders.add("Later", now.plusHours(1))
        reminders.add("Pills", now.minusHours(2), Repeat.Daily) // a repeat catches up instead
        assertThat(reminders.missed().map { it.title }).containsExactly("Pay the rent")
        assertThat(ReminderTime.due(missed.at, now)).isEqualTo("Due 9:00 am")
        // Shown late, then marked: never twice.
        reminders.fired(missed.id)
        assertThat(reminders.missed()).isEmpty()
    }

    @Test
    fun aLateLineSaysTheDayWhenItWasAnotherDay() {
        assertThat(ReminderTime.due(LocalDateTime.of(2026, 10, 13, 18, 30), now)).isEqualTo("Due Tue 13 Oct, 6:30 pm")
    }

    @Test
    fun theFiredMarkSurvivesTheStoreAndOldEntriesCountAsShown() {
        val file = java.io.File.createTempFile("reminders", ".json").apply { deleteOnExit() }
        val store = FileReminderStore(file)
        store.save(listOf(Reminder("a", "Oven", LocalDateTime.of(2026, 10, 14, 9, 0), fired = true)))
        assertThat(store.load().single().fired).isTrue()
        // Saved before the mark existed: past means shown, future not.
        file.writeText("""[{"id":"b","title":"Old","at":"2020-01-01T09:00","repeat":"","note":"","snoozed":""},
            {"id":"c","title":"Soon","at":"2099-01-01T09:00","repeat":"","note":"","snoozed":""}]""")
        assertThat(store.load().associate { it.id to it.fired }).containsExactly("b", true, "c", false)
    }

    @Test
    fun aOneOffThatFiredStaysUntilDone() {
        val reminders = book()
        val r = reminders.add("Oven", now.minusMinutes(1))!!
        assertThat(reminders.fired(r.id)).isEqualTo(r.copy(fired = true, firedAt = r.at))
        assertThat(reminders.upcoming()).isEmpty()
        assertThat(reminders.done(r.id)).isEqualTo(r.copy(fired = true, firedAt = r.at, doneAt = now))
    }

    @Test
    fun snoozeMovesOnlyTheNextNotification() {
        val reminders = book()
        val r = reminders.add("Stretch", now)!!
        val snoozed = reminders.snooze(r.id)!!
        assertThat(snoozed.due).isEqualTo(now.plusMinutes(10))
        assertThat(snoozed.at).isEqualTo(now)
    }

    @Test
    fun remindersThatLivedOnNotesAreReadOnceAndTheNotesLeftAlone() {
        val linked = "---\nferb-id: n1\nremind: 2026-10-20 08:00\nrepeat: weekly\n---\nBins.\n"
        val obsidian = "---\nremind: 2026-10-20\n---\nBring the card.\n" // set in Obsidian, never given an id
        val old = "---\nferb-id: n3\nremind: 2026-10-01 09:00\n---\n"
        val notes = MemoryNotes("Bins" to linked, "Dentist" to obsidian, "Old" to old, "Ideas" to "no reminder")
        val store = MemoryStore()
        val reminders = book(store)
        // Already in the store (it was linked): kept as it is, its snooze too.
        store.saved = listOf(Reminder("n1", "Bins", LocalDateTime.of(2026, 10, 20, 8, 0), Repeat.Weekly, snoozed = now.plusMinutes(5)))
        reminders.import(ReminderMigration.fromNotes(notes, now))
        assertThat(store.saved.map { it.title }).containsExactly("Bins", "Dentist", "Old")
        assertThat(store.saved.first { it.id == "n1" }.snoozed).isEqualTo(now.plusMinutes(5))
        val dentist = store.saved.single { it.title == "Dentist" }
        assertThat(dentist.at).isEqualTo(LocalDateTime.of(2026, 10, 20, 9, 0))
        assertThat(dentist.repeat).isNull()
        // Gone by: counts as shown, so it doesn't turn up late.
        assertThat(store.saved.single { it.title == "Old" }.fired).isTrue()
        assertThat(reminders.missed()).isEmpty()
        // Nothing written to the notes.
        assertThat(notes.files).containsExactly("Bins", linked, "Dentist", obsidian, "Old", old, "Ideas", "no reminder")
        // Without a folder: nothing to read.
        assertThat(ReminderMigration.fromNotes(MemoryNotes(ready = false), now)).isEmpty()
    }

    @Test
    fun aStoredReminderOnANoteBecomesAPlainOne() {
        val file = java.io.File.createTempFile("reminders", ".json").apply { deleteOnExit() }
        file.writeText("""[{"id":"n1","title":"Bins","at":"2099-01-04T08:00","repeat":"weekly","note":"n1","snoozed":"2099-01-04T08:10","fired":false}]""")
        val store = FileReminderStore(file)
        val r = store.load().single()
        assertThat(r).isEqualTo(Reminder("n1", "Bins", LocalDateTime.of(2099, 1, 4, 8, 0), Repeat.Weekly, LocalDateTime.of(2099, 1, 4, 8, 10)))
        store.save(listOf(r))
        assertThat(file.readText()).doesNotContain("note")
    }
}
