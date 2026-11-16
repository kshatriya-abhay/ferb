package dev.abhay.assistant.reminders

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.LocalDateTime
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [FileReminderStore]: what it can't read, it keeps; nothing is lost to a bad entry or a bad save (review P2-57). */
class ReminderStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = LocalDateTime.of(2026, 11, 16, 9, 0)

    private val good = """{"id":"a","title":"Pay the rent","at":"2026-12-01T09:00","repeat":"monthly","snoozed":"","fired":false,"firedAt":"","doneAt":""}"""
    private val broken = """{"id":"b","title":"Broken","at":"tomorrow","repeat":"","snoozed":"","fired":false,"firedAt":"","doneAt":""}"""

    @Test
    fun anUnreadableEntryDoesntEraseTheOthersAndIsKept() {
        // One bad entry made load() return nothing, and the next save wrote over every reminder.
        val file = tmp.newFile("reminders.json").apply { writeText("[$good,$broken]") }
        ReminderBook(FileReminderStore(file)) { now }.add("Buy milk", now.plusHours(1))
        assertThat(FileReminderStore(file).load().map { it.title }).containsExactly("Pay the rent", "Buy milk")
        // The entry Ferb can't read is written back as it was, not dropped.
        assertThat(file.readText()).contains("\"Broken\"")
    }

    @Test
    fun aFileThatIsntReadableAtAllIsMovedAsideNotOverwritten() {
        val file = tmp.newFile("reminders.json").apply { writeText("[$good,") }
        val book = ReminderBook(FileReminderStore(file)) { now }
        assertThat(book.all()).isEmpty()
        book.add("Buy milk", now.plusHours(1))
        assertThat(File(file.parentFile, "reminders.json.bad").readText()).isEqualTo("[$good,")
        assertThat(FileReminderStore(file).load().map { it.title }).containsExactly("Buy milk")
    }

    @Test
    fun aSaveLeavesTheFileWholeAndNoTempBehind() {
        val file = File(tmp.root, "reminders.json")
        val store = FileReminderStore(file)
        ReminderBook(store) { now }.add("Call the plumber", now.plusHours(2))
        assertThat(store.load().map { it.title }).containsExactly("Call the plumber")
        assertThat(tmp.root.list()!!.toList()).containsExactly("reminders.json")
    }
}
