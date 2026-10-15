package dev.abhay.assistant

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.notes.NoteText
import dev.abhay.assistant.notes.SafNotes
import dev.abhay.assistant.tools.AddToList
import dev.abhay.assistant.tools.CheckOff
import dev.abhay.assistant.tools.Outcome
import dev.abhay.assistant.tools.PrepareEnv
import dev.abhay.assistant.tools.Prepared
import dev.abhay.assistant.tools.ShowList
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The notes folder through the real Storage Access Framework calls (DocumentsContract, streams),
 * on a test folder in the app's cache (the debug build's TestDocumentsProvider), never the user's.
 */
@RunWith(AndroidJUnit4::class)
class NotesDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "testdocs")
    private val notes = testNotes(context)

    @Before @After
    fun clean() {
        dir.deleteRecursively()
    }

    @Test
    fun aNoteIsSavedExactlyAndReadBack() {
        val text = "---\r\ntags: [test]\r\n---\r\n# Shopping\r\n- [ ] milk\r\nno final newline"
        val note = notes.create("Shopping", text)!!
        assertThat(note.name).isEqualTo("Shopping")
        assertThat(File(dir, "Shopping.md").readBytes()).isEqualTo(text.toByteArray())
        assertThat(notes.read(note)).isEqualTo(text)
        assertThat(notes.list().map { it.name }).containsExactly("Shopping")
        // A clash gets " 2", as in Obsidian.
        assertThat(notes.create("Shopping", "x")!!.name).isEqualTo("Shopping 2")
    }

    @Test
    fun aShorterSaveLeavesNoTailAndOnlyTheKeyChanges() {
        val text = "---\ntags: [test]\n---\nA long body that will get shorter.\n"
        val note = notes.create("Plans", text)!!
        assertThat(notes.update(note) { NoteText.set(it, NoteText.REMIND, "2026-10-20 09:00") }).isTrue()
        assertThat(notes.read(note)).isEqualTo(NoteText.set(text, NoteText.REMIND, "2026-10-20 09:00"))
        notes.update(note) { "short\n" }
        assertThat(File(dir, "Plans.md").readText()).isEqualTo("short\n")
        assertThat(notes.delete(note)).isTrue()
        assertThat(notes.list()).isEmpty()
    }

    @Test
    fun syncthingsConflictCopiesAreLeftAlone() {
        notes.create("Shopping", "x")
        dir.resolve("Shopping.sync-conflict-20261015-101010-ABCDEFG.md").writeText("y")
        dir.resolve("image.png").writeText("z")
        assertThat(notes.list().map { it.name }).containsExactly("Shopping")
    }

    @Test
    fun listsThroughTheRealFolder() {
        val env = PrepareEnv(notes = notes)
        val added = (AddToList.prepare(mapOf("list" to "shopping", "items" to "milk and eggs"), env) as Prepared.Ready).run(context) as Outcome.Done
        assertThat(added.message).isEqualTo("Added milk, eggs to Shopping")
        val shown = (ShowList.prepare(mapOf("list" to "shopping"), env) as Prepared.Ready).run(context) as Outcome.Done
        assertThat(shown.lines.map { it.text }).containsExactly("milk", "eggs").inOrder()
        (CheckOff.prepare(mapOf("item" to "eggs"), env) as Prepared.Ready).run(context)
        assertThat(NoteText.tasks(notes.read(notes.list().single())!!).map { it.text to it.checked }).containsExactly("milk" to false, "eggs" to true).inOrder()
    }

    companion object {
        /** The test folder as a document tree, remembered in preferences of its own. */
        fun testNotes(context: android.content.Context) = SafNotes(context, granted = { true }, prefsName = "notes_devicetest").apply {
            folder = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT)
        }
    }
}
