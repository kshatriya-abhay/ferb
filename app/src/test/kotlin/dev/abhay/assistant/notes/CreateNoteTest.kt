package dev.abhay.assistant.notes

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.testing.unusedContext
import dev.abhay.assistant.tools.CreateNote
import dev.abhay.assistant.tools.FollowUpEnv
import dev.abhay.assistant.tools.Outcome
import dev.abhay.assistant.tools.PrepareEnv
import dev.abhay.assistant.tools.Prepared
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Notes go to the notes app, or, once the user picks a folder, to Markdown files in it. */
class CreateNoteTest {
    private val toFolder = mapOf(CreateNote.saveTo.key to CreateNote.FOLDER)
    private val note = mapOf("text" to "the car is parked on level 3")

    @Test
    fun toTheFolderAsAMarkdownFileWithFerbsId() {
        val folder = MemoryNotes()
        val ready = CreateNote.prepare(note, PrepareEnv(notes = folder, options = toFolder)) as Prepared.Ready
        val done = ready.run(unusedContext()) as Outcome.Done
        assertThat(done.message).isEqualTo("Saved “the car is parked on level” in your notes")
        val text = folder.files.getValue("the car is parked on level")
        assertThat(NoteText.get(text, NoteText.ID)).isNotEmpty()
        assertThat(NoteText.body(text)).isEqualTo("the car is parked on level 3\n")
        // Undo removes it, as Ferb wrote it.
        val undo = runBlocking { done.actions.first { it.label == "Undo" }.run(FollowUpEnv(unusedContext()) { true }) }
        assertThat(undo).isInstanceOf(Outcome.Done::class.java)
        assertThat(folder.files).isEmpty()
    }

    @Test
    fun undoLeavesANoteChangedSince() {
        val folder = MemoryNotes()
        val done = (CreateNote.prepare(note, PrepareEnv(notes = folder, options = toFolder)) as Prepared.Ready).run(unusedContext()) as Outcome.Done
        folder.files.replaceAll { _, text -> text + "edited in Obsidian\n" }
        assertThat(runBlocking { done.actions.first { it.label == "Undo" }.run(FollowUpEnv(unusedContext()) { true }) }).isInstanceOf(Outcome.Failed::class.java)
        assertThat(folder.files).hasSize(1)
    }

    @Test
    fun withoutAFolderPickedItGoesToTheNotesAppAndAsksForOne() {
        val keep = CreateNote.prepare(note, PrepareEnv(notes = MemoryNotes(ready = false), options = toFolder)) as Prepared.Ready
        assertThat(keep.summary).startsWith("Take a note")
        assertThat(keep.needs).containsExactly(NoteStore.NEEDS_FOLDER)
        // Set to the notes app: as before, nothing asked.
        val app = CreateNote.prepare(note, PrepareEnv()) as Prepared.Ready
        assertThat(app.needs).isEmpty()
    }

    @Test
    fun namesAreWhatObsidianAllows() {
        assertThat(NoteStore.fileName("Ideas: 2026/10 #work?")).isEqualTo("Ideas 202610 work")
        assertThat(NoteStore.fileName("   ")).isEqualTo("Note")
        val folder = MemoryNotes("Shopping" to "x")
        assertThat(folder.create("Shopping", "y")!!.name).isEqualTo("Shopping 2")
        assertThat(NoteStore.isNote("Shopping.sync-conflict-20261014-120000-ABC.md")).isFalse()
    }

    @Test
    fun theChoicesAsTheUserSeesThem() {
        val items = CreateNote.saveTo.items.associateBy { it.id }
        assertThat(items.getValue(CreateNote.KEEP).description).contains("Google Keep")
        val markdown = items.getValue(CreateNote.FOLDER)
        assertThat(markdown.label).isEqualTo("Markdown notes (powered by Obsidian)")
        // Offered only with Obsidian installed; Google Keep's option always.
        assertThat(markdown.needsObsidian).isTrue()
        assertThat(items.getValue(CreateNote.KEEP).needsObsidian).isFalse()
    }
}
