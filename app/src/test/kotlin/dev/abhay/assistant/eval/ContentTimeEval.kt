package dev.abhay.assistant.eval

import dev.abhay.assistant.tools.DateGate
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The time Ferb would set for the content sets' timed calls (content-rules.md, Time): [DateGate]
 * reads the words as said and the rewritten command, and sets the time only when they agree.
 * In: FERB_EVAL_TIMES_IN, JSON lines {key, tool, said, command (or null), now}. Out:
 * FERB_EVAL_TIMES_OUT, {key, said, command, ferb}: each reading as the eval sets write times
 * ("2026-10-07T09:00", "... weekdays", "2026-10-07", "ask"), and what Ferb sets ("ask" when the two
 * disagree). FERB_EVAL=1.
 */
class ContentTimeEval {
    @Test
    fun times() {
        assumeTrue("FERB_EVAL=1", System.getenv("FERB_EVAL") == "1")
        // Skipped, not passed, with no input (review P2-184).
        assumeTrue("FERB_EVAL_TIMES_IN", System.getenv("FERB_EVAL_TIMES_IN") != null)
        val input = File(System.getenv("FERB_EVAL_TIMES_IN")!!)
        val out = StringBuilder()
        for (line in input.readLines().filter { it.isNotBlank() }) {
            val o = JSONObject(line)
            val tool = o.getString("tool")
            val now = LocalDateTime.parse(o.getString("now"))
            val command = if (o.isNull("command")) null else o.getString("command")
            val g = DateGate.apply(tool, emptyMap(), o.getString("said"), command, now.atZone(ZoneId.of("Asia/Kolkata")))
            out.append(JSONObject().put("key", o.getString("key")).put("said", format(g.said))
                .put("command", g.rewritten?.let(::format) ?: JSONObject.NULL)
                .put("ferb", if (g.agreed) format(g.said) else "ask").toString()).append('\n')
        }
        File(System.getenv("FERB_EVAL_TIMES_OUT")).writeText(out.toString())
    }

    private fun format(r: DateGate.Read): String = when (r) {
        is DateGate.Read.At -> r.at.toString().take(16) + (r.rule?.let { " ${it.key}" } ?: "")
        is DateGate.Read.Day -> r.day.toString()
        is DateGate.Read.Length -> "${r.seconds} s"
        is DateGate.Read.Ask -> "ask"
    }
}
