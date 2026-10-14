package dev.abhay.assistant.notes

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Ferb's edits change only what they mean to: the rest of an Obsidian note stays byte for byte. */
class NoteTextTest {
    private val obsidian = "---\ntags: [shopping]\naliases:\n  - groceries\nremind: 2026-10-20 09:00\n---\n# Shopping\n\n- [ ] milk\n- [x] eggs\n* [ ] bread\n\nNotes **here**.\n"
    private val crlf = obsidian.replace("\n", "\r\n")
    private val bare = "# Ideas\n- [ ] one\nno final newline"

    @Test
    fun frontmatterIsFoundAndReadButNeverRewritten() {
        assertThat(NoteText.get(obsidian, "remind")).isEqualTo("2026-10-20 09:00")
        assertThat(NoteText.get(obsidian, "tags")).isEqualTo("[shopping]")
        assertThat(NoteText.get(crlf, "remind")).isEqualTo("2026-10-20 09:00")
        assertThat(NoteText.get(bare, "remind")).isNull()
        assertThat(NoteText.body(obsidian)).startsWith("# Shopping")
        assertThat(NoteText.head(obsidian) + NoteText.body(obsidian)).isEqualTo(obsidian)
        assertThat(NoteText.head(crlf) + NoteText.body(crlf)).isEqualTo(crlf)
        assertThat(NoteText.head(bare)).isEmpty()
    }

    @Test
    fun settingAKeyChangesOnlyItsLine() {
        val moved = NoteText.set(obsidian, "remind", "2026-10-21 09:00")
        assertThat(moved).isEqualTo(obsidian.replace("remind: 2026-10-20 09:00", "remind: 2026-10-21 09:00"))
        val added = NoteText.set(obsidian, "ferb-id", "abc")
        assertThat(added).isEqualTo(obsidian.replace("remind: 2026-10-20 09:00\n---", "remind: 2026-10-20 09:00\nferb-id: abc\n---"))
        val removed = NoteText.set(obsidian, "remind", null)
        assertThat(removed).isEqualTo(obsidian.replace("remind: 2026-10-20 09:00\n", ""))
        // Back and forth: the same bytes.
        assertThat(NoteText.set(NoteText.set(obsidian, "ferb-id", "abc"), "ferb-id", null)).isEqualTo(obsidian)
    }

    @Test
    fun crlfFilesKeepTheirLineEndings() {
        val added = NoteText.set(crlf, "ferb-id", "abc")
        assertThat(added).isEqualTo(crlf.replace("remind: 2026-10-20 09:00\r\n---", "remind: 2026-10-20 09:00\r\nferb-id: abc\r\n---"))
        assertThat(added.replace("\r\n", "")).doesNotContain("\n")
        assertThat(NoteText.set(crlf, "remind", "2026-10-21 09:00")).isEqualTo(crlf.replace("10-20", "10-21"))
        assertThat(NoteText.append(crlf, listOf("- [ ] tea"))).isEqualTo("$crlf- [ ] tea\r\n")
    }

    @Test
    fun aNoteWithoutFrontmatterGetsOneOnlyWhenAKeyIsSet() {
        assertThat(NoteText.set(bare, "ferb-id", "abc")).isEqualTo("---\nferb-id: abc\n---\n$bare")
        assertThat(NoteText.set(bare, "remind", null)).isEqualTo(bare)
        assertThat(NoteText.set(crlf.substringAfter("---\r\n\r\n").let { "# A\r\nb\r\n" }, "ferb-id", "x")).isEqualTo("---\r\nferb-id: x\r\n---\r\n# A\r\nb\r\n")
    }

    @Test
    fun appendingAddsLinesAndLeavesTheRest() {
        assertThat(NoteText.append(obsidian, listOf("- [ ] tea", "- [ ] rice"))).isEqualTo("$obsidian- [ ] tea\n- [ ] rice\n")
        // No final newline: one is added before the new lines, nothing else changes.
        assertThat(NoteText.append(bare, listOf("- [ ] two"))).isEqualTo("$bare\n- [ ] two\n")
        assertThat(NoteText.append("", listOf("- [ ] first"))).isEqualTo("- [ ] first\n")
    }

    @Test
    fun aCheckboxFlipsOneCharacter() {
        val tasks = NoteText.tasks(obsidian)
        assertThat(tasks.map { it.text to it.checked }).containsExactly("milk" to false, "eggs" to true, "bread" to false).inOrder()
        val ticked = NoteText.flip(obsidian, tasks[0].mark)!!
        assertThat(ticked).isEqualTo(obsidian.replace("- [ ] milk", "- [x] milk"))
        assertThat(ticked.length).isEqualTo(obsidian.length)
        assertThat(NoteText.flip(ticked, tasks[0].mark)).isEqualTo(obsidian)
        assertThat(NoteText.flip(obsidian, tasks[1].mark)).isEqualTo(obsidian.replace("- [x] eggs", "- [ ] eggs"))
        // CRLF: the same, the line endings untouched.
        val crlfTasks = NoteText.tasks(crlf)
        assertThat(NoteText.flip(crlf, crlfTasks[2].mark)).isEqualTo(crlf.replace("* [ ] bread", "* [x] bread"))
        // Not a task mark: no change.
        assertThat(NoteText.flip(obsidian, 0)).isNull()
        assertThat(NoteText.flip(obsidian, obsidian.indexOf("Notes"))).isNull()
    }

    @Test
    fun tasksInsideTheFrontmatterAreNotTasks() {
        val tricky = "---\nnote: - [ ] not a task\n---\n- [ ] real\n"
        assertThat(NoteText.tasks(tricky).map { it.text }).containsExactly("real")
    }
}
