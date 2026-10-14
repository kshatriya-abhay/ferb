package dev.abhay.assistant.notes

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.ui.toggled
import org.junit.Test

/** A box ticked in the viewer changes that task in the file, even if the file changed meanwhile. */
class ViewerTickTest {
    private val shown = "---\nferb-id: 1\n---\n- [ ] milk\n- [ ] eggs\n"
    private val eggs = NoteText.tasks(shown)[1].mark

    @Test
    fun theFileAsShownFlipsAtTheOffset() {
        assertThat(toggled(shown, shown, eggs)).isEqualTo(shown.replace("- [ ] eggs", "- [x] eggs"))
    }

    @Test
    fun aFileChangedMeanwhileFlipsTheSameTask() {
        // Obsidian added a line above: the offsets moved, the task is found by order and text.
        val now = shown.replace("---\n- [ ]", "---\nShopping:\n- [ ]")
        assertThat(toggled(shown, now, eggs)).isEqualTo(now.replace("- [ ] eggs", "- [x] eggs"))
        // CRLF in the file now: the same, the line endings kept.
        val crlf = shown.replace("\n", "\r\n")
        assertThat(toggled(shown, crlf, eggs)).isEqualTo(crlf.replace("- [ ] eggs", "- [x] eggs"))
    }

    @Test
    fun aTaskThatChangedIsLeftAlone() {
        val now = shown.replace("- [ ] eggs", "- [x] eggs") // ticked in Obsidian already
        assertThat(toggled(shown, now, eggs)).isEqualTo(now)
        val renamed = shown.replace("eggs", "bread")
        assertThat(toggled(shown, renamed, eggs)).isEqualTo(renamed)
    }
}
