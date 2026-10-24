package dev.abhay.assistant.eval

import dev.abhay.assistant.ui.AnswerFlow
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/** How often AnswerFlow's made-up-number check flags the phone bench's Gemini rewrites (FERB_EVAL_INVENTS: request<TAB>command lines). */
class InventsReport {
    @Test
    fun report() {
        val path = System.getenv("FERB_EVAL_INVENTS")
        assumeTrue(path != null)
        val flow = AnswerFlow(link = object : dev.abhay.assistant.link.LinkCalls {
            override fun status() = error("unused")
            override suspend fun rewrite(request: String, instructions: String, maxTokens: Int, timeoutMs: Int) = error("unused")
            override suspend fun decide(request: String, toolsJson: String, timeoutMs: Int) = error("unused")
        }, needle = { _, _ -> error("unused") }, gemini = { error("unused") })
        val pairs = File(path!!).readLines().filter { '\t' in it }.map { it.substringBefore('\t') to it.substringAfter('\t') }
        val flagged = pairs.filter { (said, command) -> flow.invents(command, said) }
        println("INVENTS ${flagged.size} of ${pairs.size}")
        flagged.forEach { println("INVENTS  ${it.first}  ->  ${it.second}") }
    }
}
