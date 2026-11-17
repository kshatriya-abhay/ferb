package dev.abhay.assistant.eval

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * tool-check's sets, by name (review P2-166, P2-178). The rule: a test may read a [CLOSED] set
 * (blind or held out) only to prove nothing leaked into Ferb, and never prints a line from it;
 * evals score [OPEN] sets by default. Every .txt in tool-check must be named here.
 */
object EvalSets {
    /** Dev and bench sets: tuned on, scored, printed. */
    val OPEN = listOf("audio.txt", "datetime.txt", "datetime-v2.txt", "models.txt", "requests.txt", "suite.txt")
    /** Blind and held-out sets: never tuned on, never printed. */
    val CLOSED = listOf("content-blind.txt", "content-blind-2.txt", "datetime-blind.txt", "datetime-blind-2.txt", "rewrite-heldout.txt", "times.txt")

    val dir = File("../tool-check")
    fun file(name: String) = File(dir, name)

    /** For an eval: [name] may be scored and printed only if it's open. */
    fun requireOpen(name: String) = require(name in OPEN) { "$name isn't an open set (EvalSets): closed sets are never scored line by line" }
}

class EvalSetsTest {
    @Test
    fun everySetInToolCheckIsNamedOpenOrClosed() {
        // File names only: no set is read here.
        val names = EvalSets.dir.list { _, n -> n.endsWith(".txt") }!!.toList()
        assertThat(names).isNotEmpty()
        assertThat(EvalSets.OPEN.intersect(EvalSets.CLOSED.toSet())).isEmpty()
        assertThat(names.filter { it !in EvalSets.OPEN && it !in EvalSets.CLOSED }).isEmpty()
    }
}
