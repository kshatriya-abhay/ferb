package dev.abhay.assistant.eval

import dev.abhay.assistant.tools.SaidNumbers
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/** How often AnswerFlow's made-up-number check flags the phone bench's Gemini rewrites (FERB_EVAL_INVENTS: request<TAB>command lines). */
class InventsReport {
    @Test
    fun report() {
        val path = System.getenv("FERB_EVAL_INVENTS")
        assumeTrue(path != null)
        val pairs = File(path!!).readLines().filter { '\t' in it }.map { it.substringBefore('\t') to it.substringAfter('\t') }
        val flagged = pairs.filter { (said, command) -> SaidNumbers.invents(command, said) }
        println("INVENTS ${flagged.size} of ${pairs.size}")
        flagged.forEach { println("INVENTS  ${it.first}  ->  ${it.second}") }
    }
}
