package dev.abhay.assistant.tools

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Test

/**
 * A pin of what the date gate does today with each request of the two dev sets
 * (tool-check/datetime.txt and datetime-v2.txt; never the blind ones): the time it puts on the
 * call, read from the words as said. A refactor must leave every row as it is; a fix changes only
 * the rows it means to, and the snapshot is rewritten with `FERB_PIN_WRITE=1` in that commit, so
 * its diff shows them. It pins behavior, it doesn't score it: the expected answers aren't read.
 */
class DatePinTest {
    private val repo = File("..").canonicalFile
    private val snapshot = File(repo, "app/src/test/resources/dates/dev-pin.tsv")

    private fun rows(): List<String> = listOf("datetime.txt", "datetime-v2.txt").flatMap { set ->
        File(repo, "tool-check/$set").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.mapIndexed { i, raw ->
            val (now, tool, _, request) = raw.split("|", limit = 4).map { it.trim() }
            val at = LocalDateTime.parse(now.substringBefore(' ')).atZone(ZoneId.of("Asia/Kolkata"))
            val gated = DateGate.apply(tool, emptyMap(), request, null, at)
            listOf(set, i + 1, tool, request, gated.time ?: gated.said).joinToString("\t")
        }
    }

    @Test
    fun theDevSetsReadAsPinned() {
        val now = rows()
        if (System.getenv("FERB_PIN_WRITE") == "1") snapshot.writeText(now.joinToString("\n", postfix = "\n"))
        val pinned = snapshot.readLines().filter { it.isNotEmpty() }
        val changed = now.zip(pinned).filter { (a, b) -> a != b }.map { (a, b) -> "now:    $a\npinned: $b" }
        assertWithMessage("rows changed (${changed.size}):\n" + changed.joinToString("\n")).that(changed).isEmpty()
        assertWithMessage("row count").that(now.size).isEqualTo(pinned.size)
    }
}
