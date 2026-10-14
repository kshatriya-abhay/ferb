package dev.abhay.assistant.notes

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.notes.NoteEdit.Kind
import dev.abhay.assistant.notes.NoteEdit.Save
import org.junit.Test

/** The editor saves exactly what was typed, in the file's own line endings, around its frontmatter. */
class NoteEditTest {
    private val note = "---\ntags: [home]\nferb-id: 1\n---\n# Plans\n\n- [ ] paint the fence\nCall **Ravi**.\n"
    private val crlf = note.replace("\n", "\r\n")

    @Test
    fun unchangedWritesNothing() {
        assertThat(NoteEdit.save(note, note, NoteEdit.editable(note))).isEqualTo(Save.Nothing)
        assertThat(NoteEdit.save(crlf, crlf, NoteEdit.editable(crlf))).isEqualTo(Save.Nothing)
        // Unchanged, the file comes back byte for byte.
        assertThat(NoteEdit.file(note, NoteEdit.editable(note))).isEqualTo(note)
        assertThat(NoteEdit.file(crlf, NoteEdit.editable(crlf))).isEqualTo(crlf)
        val bare = "no frontmatter, no final newline"
        assertThat(NoteEdit.file(bare, NoteEdit.editable(bare))).isEqualTo(bare)
    }

    @Test
    fun anEditKeepsTheFrontmatterAndLineEndings() {
        val edited = NoteEdit.editable(crlf).replace("Call", "Ring")
        assertThat(NoteEdit.editable(crlf)).doesNotContain("\r")
        assertThat(NoteEdit.save(crlf, crlf, edited)).isEqualTo(Save.Write(crlf.replace("Call", "Ring")))
    }

    @Test
    fun aChangeElsewhereIsKeptOrAsked() {
        val edited = NoteEdit.editable(note) + "More.\n"
        // A reminder moved meanwhile (frontmatter only): kept, with this body.
        val reminded = NoteText.set(note, NoteText.REMIND, "2026-10-20 09:00")
        assertThat(NoteEdit.save(note, reminded, edited)).isEqualTo(Save.Write(NoteText.head(reminded) + edited))
        // The body changed in Obsidian too: the user decides.
        val elsewhere = note.replace("paint the fence", "paint the gate")
        assertThat(NoteEdit.save(note, elsewhere, edited)).isInstanceOf(Save.Conflict::class.java)
    }

    @Test
    fun stylesMarkTheMarkdownWithoutChangingIt() {
        val text = "# Plans\n- [ ] paint\n- [x] buy\n- milk\n1. first\n**bold** and *it* and `code`\nSee [[Shopping]] or [web](https://x.org).\n> quoted"
        fun spans(kind: Kind) = NoteEdit.styles(text).filter { it.kind == kind }.map { text.substring(it.start, it.end) }
        assertThat(spans(Kind.Heading)).containsExactly("# Plans")
        assertThat(spans(Kind.Task)).containsExactly("- [ ]")
        assertThat(spans(Kind.Done)).containsExactly("- [x]")
        assertThat(spans(Kind.Marker)).containsExactly("-", "1.").inOrder()
        assertThat(spans(Kind.Bold)).containsExactly("**bold**")
        assertThat(spans(Kind.Italic)).containsExactly("*it*")
        assertThat(spans(Kind.Code)).containsExactly("`code`")
        assertThat(spans(Kind.Link)).containsExactly("[[Shopping]]", "[web](https://x.org)")
        assertThat(spans(Kind.Quote)).containsExactly("> quoted")
    }
}
