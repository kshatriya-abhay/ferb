package dev.abhay.assistant.reminders

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.notes.MemoryNotes
import dev.abhay.assistant.notes.NoteText
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

    private val original = "---\ntags: [home]\nferb-id: n1\nremind: 2026-10-19 08:00\nrepeat: weekly\nsource: me\n---\nTake out the bins.\n"

    @Test
    fun aRepeatOnANoteIsClearedEntirelyAndUndoBringsItAllBack() {
        val notes = MemoryNotes("Bins" to original)
        val store = MemoryStore()
        val book = ReminderBook(store, notes) { now }
        book.scan()
        val snoozed = book.snooze("n1")!!
        val cleared = book.clear("n1")!!
        assertThat(store.saved).isEmpty()
        assertThat(book.upcoming()).isEmpty()
        // The keys go; the note, its other keys and its ferb-id stay.
        val text = notes.read(notes.list().single())!!
        assertThat(text).isEqualTo("---\ntags: [home]\nferb-id: n1\nsource: me\n---\nTake out the bins.\n")
        // A rescan doesn't bring it back.
        book.scan()
        assertThat(store.saved).isEmpty()

        assertThat(book.restore(cleared)).isTrue()
        assertThat(notes.read(notes.list().single())).isEqualTo(original)
        assertThat(store.saved).containsExactly(snoozed)
        assertThat(store.saved.single().repeat).isEqualTo(Repeat.Weekly)
    }

    @Test
    fun undoAfterTheNoteChangedPutsTheKeysBack() {
        val notes = MemoryNotes("Bins" to original)
        val book = ReminderBook(MemoryStore(), notes) { now }
        book.scan()
        val cleared = book.clear("n1")!!
        val note = notes.list().single()
        notes.update(note) { it + "Edited in Obsidian.\n" }
        assertThat(book.restore(cleared)).isTrue()
        val text = notes.read(note)!!
        assertThat(NoteText.get(text, NoteText.REMIND)).isEqualTo("2026-10-19 08:00")
        assertThat(NoteText.get(text, NoteText.REPEAT)).isEqualTo("weekly")
        assertThat(text).endsWith("Edited in Obsidian.\n")
        assertThat(book.upcoming().map { it.id }).containsExactly("n1")
    }

    @Test
    fun aReminderWithoutANote() {
        val store = MemoryStore()
        val book = ReminderBook(store, MemoryNotes()) { now }
        val r = book.add("Call mum", now.plusHours(2))!!
        val cleared = book.clear(r.id)!!
        assertThat(cleared.noteBefore).isNull()
        assertThat(book.upcoming()).isEmpty()
        book.restore(cleared)
        assertThat(store.saved).containsExactly(r)
        assertThat(book.clear("nope")).isNull()
    }

    @Test
    fun undoWhenTheNoteIsGoneSaysSo() {
        val notes = MemoryNotes("Bins" to original)
        val book = ReminderBook(MemoryStore(), notes) { now }
        book.scan()
        val cleared = book.clear("n1")!!
        notes.delete(notes.list().single())
        assertThat(book.restore(cleared)).isFalse()
        assertThat(book.upcoming()).isEmpty()
    }
}
