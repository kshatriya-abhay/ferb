package dev.abhay.assistant.notes

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.tools.CreateNote
import org.junit.Test

/** The Notes screen lists only Ferb's notes: those with its id in their frontmatter. */
class FerbNotesTest {
    private fun ref(name: String) = NoteRef("id:$name", name, 0)

    @Test
    fun onlyNotesWithFerbsIdAreListed() {
        val files = mapOf(
            "Car parked" to CreateNote.noteFile("the car is parked on level 3", "a1"),
            "Shopping list" to CreateNote.noteFile("", "b2") + "- [ ] milk\n",
            "Journal" to "Dear diary\n",
            "Hand-made list" to "- [ ] eggs\n",
            "Other frontmatter" to "---\ntags: [x]\n---\nbody\n",
            "Empty id" to "---\nferb-id: \n---\nbody\n",
            "Reminder on my note" to "---\ntags: [x]\nferb-id: c3\nremind: 2026-10-20T09:00\n---\nbody\n",
            "CRLF" to "---\r\nferb-id: d4\r\n---\r\nbody\r\n",
        )
        val notes = files.keys.map(::ref) + ref("Unreadable")
        val shown = NoteStore.ferbs(notes) { files[it.name] }.map { it.name }
        assertThat(shown).containsExactly("Car parked", "Shopping list", "Reminder on my note", "CRLF").inOrder()
    }
}
