package dev.abhay.assistant.notes

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.testing.unusedContext
import dev.abhay.assistant.tools.AddToList
import dev.abhay.assistant.tools.CheckOff
import dev.abhay.assistant.tools.FollowUp
import dev.abhay.assistant.tools.FollowUpEnv
import dev.abhay.assistant.tools.Outcome
import dev.abhay.assistant.tools.PrepareEnv
import dev.abhay.assistant.tools.Prepared
import dev.abhay.assistant.tools.ShowList
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Lists are notes with tasks; Ferb appends tasks and ticks boxes, and changes nothing else. */
class ListToolsTest {
    private val shopping = "---\ntags: [home]\n---\n# Shopping\r\n- [ ] milk\r\n- [x] eggs\r\n"
    private fun folder() = MemoryNotes("Shopping" to shopping, "Ideas" to "no tasks here")
    private fun run(prepared: Prepared) = (prepared as Prepared.Ready).run(unusedContext())
    private fun follow(action: FollowUp) = runBlocking { action.run(FollowUpEnv(unusedContext()) { true }) }

    @Test
    fun addingAppendsTasksToTheListFoundByName() {
        val notes = folder()
        val done = run(AddToList.prepare(mapOf("list" to "the shopping list", "items" to "bread, tea and rice"), PrepareEnv(notes = notes))) as Outcome.Done
        assertThat(done.message).isEqualTo("Added bread, tea, rice to Shopping")
        // In the file's own line ending (this one's body is CRLF after an LF frontmatter: the first line decides).
        assertThat(notes.files["Shopping"]).isEqualTo("$shopping- [ ] bread\n- [ ] tea\n- [ ] rice\n")
        // Undo: exactly the file as it was.
        follow(done.actions.first { it.label == "Undo" })
        assertThat(notes.files["Shopping"]).isEqualTo(shopping)
    }

    @Test
    fun aNewListIsANewNote() {
        val notes = folder()
        val prepared = AddToList.prepare(mapOf("list" to "packing", "items" to "charger"), PrepareEnv(notes = notes)) as Prepared.Ready
        assertThat(prepared.detail).isEqualTo("A new list")
        run(prepared)
        val text = notes.files.getValue("Packing")
        assertThat(NoteText.get(text, NoteText.ID)).isNotEmpty()
        assertThat(NoteText.tasks(text).map { it.text }).containsExactly("charger")
    }

    @Test
    fun showingListsTheUntickedItemsEachTickable() {
        val notes = folder()
        val shown = run(ShowList.prepare(mapOf("list" to "shopping"), PrepareEnv(notes = notes))) as Outcome.Done
        assertThat(shown.message).isEqualTo("Shopping: 1 item")
        assertThat(shown.lines.map { it.text }).containsExactly("milk")
        val after = follow(shown.lines.single().tick!!) as Outcome.Done
        assertThat(after.message).isEqualTo("Shopping: nothing left")
        assertThat(notes.files["Shopping"]).isEqualTo(shopping.replace("- [ ] milk", "- [x] milk"))
    }

    @Test
    fun tickingOffFindsTheItemAndUndoUnticksIt() {
        val notes = folder()
        // No list named: the one with that item; said a little differently.
        val prepared = CheckOff.prepare(mapOf("item" to "the milk"), PrepareEnv(notes = notes)) as Prepared.Ready
        assertThat(prepared.summary).isEqualTo("Tick “milk” on Shopping")
        val done = run(prepared) as Outcome.Done
        assertThat(notes.files["Shopping"]).isEqualTo(shopping.replace("- [ ] milk", "- [x] milk"))
        follow(done.actions.first { it.label == "Undo" })
        assertThat(notes.files["Shopping"]).isEqualTo(shopping)
        // Ticked already, or not there: nothing to do.
        assertThat(CheckOff.prepare(mapOf("list" to "shopping", "item" to "eggs"), PrepareEnv(notes = notes))).isInstanceOf(Prepared.Invalid::class.java)
        assertThat(CheckOff.prepare(mapOf("list" to "shopping", "item" to "butter"), PrepareEnv(notes = notes))).isInstanceOf(Prepared.Invalid::class.java)
    }

    @Test
    fun withoutAFolderTheReplyAsksForOne() {
        val none = PrepareEnv(notes = MemoryNotes(ready = false))
        listOf(
            AddToList.prepare(mapOf("list" to "shopping", "items" to "milk"), none),
            ShowList.prepare(mapOf("list" to "shopping"), none),
            CheckOff.prepare(mapOf("item" to "milk"), none),
        ).forEach { assertThat((it as Prepared.Invalid).needs).containsExactly(NoteStore.NEEDS_FOLDER) }
    }
}
