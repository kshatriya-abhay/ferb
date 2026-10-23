package dev.abhay.assistant.reminders

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.notes.MemoryNotes
import dev.abhay.assistant.notes.NoteText
import java.time.LocalDateTime
import org.junit.Test

class RemindersTest {
    private val now = LocalDateTime.of(2026, 10, 14, 13, 0) // a Wednesday afternoon

    private class MemoryStore : ReminderStore {
        var saved = emptyList<Reminder>()
        override fun load() = saved
        override fun save(reminders: List<Reminder>) { saved = reminders }
    }

    private fun book(notes: MemoryNotes = MemoryNotes(), store: MemoryStore = MemoryStore()) = ReminderBook(store, notes) { now }

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
    fun aReminderWithoutANoteNeedsNoFolder() {
        val store = MemoryStore()
        val reminders = book(MemoryNotes(ready = false), store)
        val r = reminders.add("Check the oven", now.plusMinutes(20))!!
        assertThat(store.saved).containsExactly(r)
        // Done without a repeat: gone.
        assertThat(reminders.done(r.id)).isNull()
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun aReminderOnANoteWritesItsKeysAndOnlyThose() {
        val original = "---\ntags: [home]\n---\n# Rent\nPay by bank transfer.\n"
        val notes = MemoryNotes("Rent" to original)
        val store = MemoryStore()
        val reminders = book(notes, store)
        val r = reminders.add("Rent", LocalDateTime.of(2026, 11, 1, 9, 0), Repeat.Monthly, notes.list().single())!!
        val text = notes.files.getValue("Rent")
        assertThat(NoteText.get(text, NoteText.ID)).isEqualTo(r.id)
        assertThat(NoteText.get(text, NoteText.REMIND)).isEqualTo("2026-11-01 09:00")
        assertThat(NoteText.get(text, NoteText.REPEAT)).isEqualTo("monthly")
        assertThat(NoteText.body(text)).isEqualTo(NoteText.body(original))
        // Done on its notification (Nov 1, 09:05): next month, in the note too.
        assertThat(ReminderBook(store, notes) { LocalDateTime.of(2026, 11, 1, 9, 5) }.done(r.id)!!.at).isEqualTo(LocalDateTime.of(2026, 12, 1, 9, 0))
        assertThat(NoteText.get(notes.files.getValue("Rent"), NoteText.REMIND)).isEqualTo("2026-12-01 09:00")
        // Removed: the keys go, the id stays (the note keeps its identity).
        reminders.remove(r.id)
        val after = notes.files.getValue("Rent")
        assertThat(NoteText.get(after, NoteText.REMIND)).isNull()
        assertThat(NoteText.get(after, NoteText.REPEAT)).isNull()
        assertThat(after).isEqualTo(NoteText.set(original, NoteText.ID, r.id))
    }

    @Test
    fun aRepeatSkipsTimesAlreadyPast() {
        val reminders = book()
        val r = reminders.add("Water the plants", now.minusDays(3), Repeat.Daily)!!
        assertThat(reminders.done(r.id)!!.at).isEqualTo(now.minusDays(3).plusDays(4))
    }

    @Test
    fun aRepeatThatFiredComesBackWithoutDone() {
        val notes = MemoryNotes("Plants" to "Water them.\n")
        val reminders = book(notes)
        val r = reminders.add("Plants", now.minusMinutes(1), Repeat.Daily, notes.list().single())!!
        // Fired a minute ago: tomorrow, in the note too, and scheduled, as it's due after now.
        val next = reminders.fired(r.id)!!
        assertThat(next.at).isEqualTo(now.minusMinutes(1).plusDays(1))
        assertThat(next.due.isAfter(now)).isTrue()
        assertThat(NoteText.get(notes.files.getValue("Plants"), NoteText.REMIND)).isEqualTo(ReminderTime.format(next.at))
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
        val later = ReminderBook(store, MemoryNotes()) { now.plusMinutes(11) }
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
        assertThat(reminders.fired(r.id)).isEqualTo(r.copy(fired = true))
        assertThat(reminders.done(r.id)).isNull()
        assertThat(reminders.all()).isEmpty()
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
    fun theFolderRebuildsLinkedRemindersAndTakesObsidiansToo() {
        val notes = MemoryNotes(
            "Dentist" to "---\nremind: 2026-10-20\n---\nBring the card.\n", // set in Obsidian, no id yet
            "Ideas" to "no reminder",
        )
        val store = MemoryStore()
        val reminders = book(notes, store)
        reminders.add("Oven", now.plusMinutes(5)) // no note: kept as it is
        val found = reminders.scan()
        assertThat(found.map { it.title }).containsExactly("Oven", "Dentist").inOrder()
        val dentist = found.single { it.title == "Dentist" }
        assertThat(dentist.at).isEqualTo(LocalDateTime.of(2026, 10, 20, 9, 0))
        // The note got its id, and nothing else changed.
        assertThat(notes.files.getValue("Dentist")).isEqualTo("---\nremind: 2026-10-20\nferb-id: ${dentist.id}\n---\nBring the card.\n")
        // Renamed in Obsidian: still found by its id.
        notes.files["Dentist appointment"] = notes.files.remove("Dentist")!!
        assertThat(reminders.noteFor(dentist.id)!!.name).isEqualTo("Dentist appointment")
        // The key removed in Obsidian: the reminder goes on the next scan.
        notes.files["Dentist appointment"] = NoteText.set(notes.files.getValue("Dentist appointment"), NoteText.REMIND, null)
        assertThat(reminders.scan().map { it.title }).containsExactly("Oven")
    }
}
