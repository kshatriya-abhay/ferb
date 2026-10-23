package dev.abhay.assistant.reminders

import android.Manifest
import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.notes.MemoryNotes
import dev.abhay.assistant.notes.NoteStore
import dev.abhay.assistant.notes.NoteText
import dev.abhay.assistant.testing.unusedContext
import dev.abhay.assistant.tools.AddReminder
import dev.abhay.assistant.tools.FollowUpEnv
import dev.abhay.assistant.tools.Outcome
import dev.abhay.assistant.tools.PrepareEnv
import dev.abhay.assistant.tools.Prepared
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Test

class AddReminderTest {
    private val now = LocalDateTime.of(2026, 10, 14, 13, 0)
    private class MemoryStore : ReminderStore {
        var saved = emptyList<Reminder>()
        override fun load() = saved
        override fun save(reminders: List<Reminder>) { saved = reminders }
    }

    private fun env(notes: MemoryNotes = MemoryNotes(ready = false), store: MemoryStore = MemoryStore(), granted: Boolean = true, changed: () -> Unit = {}) =
        PrepareEnv(now = now.atZone(ZoneId.of("Asia/Kolkata")), notes = notes, reminders = ReminderBook(store, notes) { now },
            remindersChanged = changed, granted = { granted })

    private fun call(request: String, vararg args: Pair<String, String>) = AddReminder.fromRequest(mapOf(*args), request)

    @Test
    fun aReminderFromTheRequestsOwnWords() {
        val store = MemoryStore()
        var scheduled = 0
        val prepared = AddReminder.prepare(call("remind me on Friday to pay the rent", "text" to "pay the rent", "time" to "Friday"), env(store = store) { scheduled++ }) as Prepared.Ready
        assertThat(prepared.summary).isEqualTo("Remind you: “Pay the rent” · Fri 16 Oct, 09:00")
        val done = prepared.run(unusedContext()) as Outcome.Done
        assertThat(store.saved.single().at).isEqualTo(LocalDateTime.of(2026, 10, 16, 9, 0))
        assertThat(scheduled).isEqualTo(1)
        runBlocking { done.actions.single { it.label == "Undo" }.run(FollowUpEnv(unusedContext()) { true }) }
        assertThat(store.saved).isEmpty()
        assertThat(scheduled).isEqualTo(2)
    }

    @Test
    fun inMinutesAndRepeatsOnlyWhenSaid() {
        val r = AddReminder.prepare(call("remind me in 20 minutes to check the oven", "text" to "check the oven", "time" to "in 20 minutes", "repeat" to "daily"), env()) as Prepared.Ready
        // Needle 3 added a repeat the request doesn't say: ignored.
        assertThat(r.summary).isEqualTo("Remind you: “Check the oven” · Wed 14 Oct, 13:20")
        val weekly = AddReminder.prepare(call("remind me every Monday at 8 to take out the bins", "text" to "take out the bins", "time" to "Monday at 8", "repeat" to "weekly"), env()) as Prepared.Ready
        assertThat(weekly.summary).endsWith(", weekly")
        // Needle 3 left the repeat out; the request says it.
        val bins = AddReminder.prepare(call("remind me every Monday at 8 to take out the bins", "text" to "take out the bins", "time" to "8"), env()) as Prepared.Ready
        assertThat(bins.summary).isEqualTo("Remind you: \u201cTake out the bins\u201d \u00b7 Mon 19 Oct, 08:00, weekly")
    }

    @Test
    fun onANoteItWritesTheNotesKeys() {
        val notes = MemoryNotes("Rent" to "Pay by transfer.\n")
        (AddReminder.prepare(call("remind me about the rent note on Friday", "text" to "the rent", "time" to "Friday", "note" to "rent"), env(notes)) as Prepared.Ready)
            .run(unusedContext())
        assertThat(NoteText.get(notes.files.getValue("Rent"), NoteText.REMIND)).isEqualTo("2026-10-16 09:00")
    }

    @Test
    fun whatItNeedsFirst() {
        val noFolder = AddReminder.prepare(call("remind me about the rent note on Friday", "text" to "rent", "time" to "Friday", "note" to "rent"), env())
        assertThat((noFolder as Prepared.Invalid).needs).containsExactly(NoteStore.NEEDS_FOLDER)
        val noNotifications = AddReminder.prepare(call("remind me in 5 minutes to stretch", "text" to "stretch", "time" to "in 5 minutes"), env(granted = false))
        assertThat((noNotifications as Prepared.Invalid).needs).containsExactly(Manifest.permission.POST_NOTIFICATIONS)
        val noTime = AddReminder.prepare(call("remind me to call mum", "text" to "call mum", "time" to "later"), env())
        assertThat(noTime).isInstanceOf(Prepared.Invalid::class.java)
    }

    @Test
    fun aReminderReplyOpensTheRemindersTab() {
        var opened = 0
        val e = PrepareEnv(now = now.atZone(ZoneId.of("Asia/Kolkata")), notes = MemoryNotes(ready = false),
            reminders = ReminderBook(MemoryStore(), MemoryNotes(ready = false)) { now }, openReminders = { opened++ })
        val done = (AddReminder.prepare(call("remind me tomorrow at 9 to call mum", "text" to "call mum", "time" to "tomorrow at 9"), e) as Prepared.Ready)
            .run(unusedContext()) as Outcome.Done
        assertThat(done.actions.map { it.label }).containsExactly("Reminders", "Undo").inOrder()
        // It opens the tab and keeps the reply as it was, its buttons too.
        val after = runBlocking { done.actions.first().run(FollowUpEnv(unusedContext()) { true }) } as Outcome.Done
        assertThat(opened).isEqualTo(1)
        assertThat(after.message).isEqualTo(done.message)
        assertThat(after.actions.map { it.label }).containsExactly("Reminders", "Undo").inOrder()
    }
}
