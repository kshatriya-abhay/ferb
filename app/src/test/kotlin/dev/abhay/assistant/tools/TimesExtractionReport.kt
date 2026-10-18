package dev.abhay.assistant.tools

import dev.abhay.assistant.reminders.ReminderTime
import java.io.File
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A model evaluation, not a unit test: the computer's time structures for tool-check/times.txt
 * (tool-check/times.py writes them to build/tool-check/times-when.jsonl) put through the app's own
 * [TimeArgs] and [Resolve], as Ferb would on the phone, and compared with each line's expected
 * value. Writes build/tool-check/times-report.txt. Skipped when there are no structures, so
 * `mise run test` never depends on a model.
 */
class TimesExtractionReport {
    private val input = File("build/tool-check/times-when.jsonl")

    /** What Ferb would do with a call, in times.txt's terms. */
    private fun outcome(call: JSONObject?, request: String, now: LocalDateTime, zone: ZoneId): String {
        call ?: return "none"
        if (call.optBoolean("trimmed") || (call.optJSONArray("asks")?.length() ?: 0) > 0) return "ask"
        val raw = call.optJSONObject("arguments")?.let { a -> a.keys().asSequence().associateWith { a.get(it) } }.orEmpty()
        val withWhen = raw + ("when" to call.opt("when")?.takeUnless { it == JSONObject.NULL })
        val args = TimeArgs.apply(call.getString("tool"), withWhen, listOf(request), now.atZone(zone))
        if (args[TimeArgs.ASK] != null) return "ask"
        val name = call.getString("tool")
        val local = now.withSecond(0).withNano(0)
        return when (name) {
            SetAlarm.name -> (args["time"] as? String)?.let(TimeOfDay::parse)?.let { (h, m) ->
                val time = LocalTime.of(h, m)
                val day = if (time.isAfter(local.toLocalTime())) local.toLocalDate() else local.toLocalDate().plusDays(1)
                "alarm ${day.atTime(time)}"
            } ?: "ask"
            SetTimer.name -> (args["duration"] as? String)?.let(Durations::parse)?.let { "timer ${it}s" } ?: "ask"
            AddEvent.name -> AddEvent.plan("x", args, local, zone)?.let { p -> if (p.start == null) "event ${p.day}" else "event ${p.day.atTime(p.start)}" } ?: "ask"
            AddReminder.name -> {
                val at = (args[TimeArgs.AT] as? String)?.let(LocalDateTime::parse)
                    ?: (args["time"] as? String)?.let { ReminderTime.read(it, local) }
                    ?: return "ask"
                "reminder $at" + ((args[TimeArgs.REPEAT] as? String)?.let { " repeat=$it" } ?: "")
            }
            else -> "${name.removePrefix("set_").removePrefix("add_")} (another tool)"
        }
    }

    @Test
    fun report() {
        assumeTrue("no structures to score: run tool-check/times.py", input.exists())
        val rows = input.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
        val out = StringBuilder()
        val tally = sortedMapOf<String, IntArray>()
        for (r in rows) {
            val expected = r.getString("expected")
            val now = LocalDateTime.parse(r.getString("now"))
            val call = r.optJSONObject("call")
            val got = outcome(call, r.getString("request"), now, ZoneId.of(r.getString("zone")))
            val ok = got == expected
            // A request that should do nothing but asks is a lesser miss than one that runs.
            for (key in listOf("all", r.getString("split"), "${r.getString("split")} ${r.getString("category")}")) {
                val t = tally.getOrPut(key) { IntArray(3) }
                t[0]++; if (ok) t[1]++; if (!ok && got == "ask") t[2]++
            }
            if (!ok) out.append("MISS ${r.getString("split")} [${r.getString("category")}] ${r.getString("request")}: expected $expected, got $got; when=${call?.opt("when")}\n")
        }
        val head = StringBuilder("Times: right / lines (asked instead, of the misses)\n")
        tally.forEach { (k, t) -> head.append("  ${k.padEnd(22)} ${t[1]}/${t[0]}  (asked ${t[2]})\n") }
        File("build/tool-check/times-report.txt").writeText(head.toString() + "\n" + out)
    }
}
