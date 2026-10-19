package dev.abhay.assistant.tools

import dev.abhay.assistant.reminders.ReminderTime
import dev.abhay.assistant.reminders.Repeat
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
        val name = call.getString("tool")
        // As the app does: the call's time resolved ([TimeArgs]), the request added (tools read
        // days and times from it, `fromRequest`), and the tool's own checks, which ask.
        val resolved = TimeArgs.apply(name, withWhen, listOf(request), now.atZone(zone))
        if (resolved[TimeArgs.ASK] != null) return "ask"
        val tool = Tools.byName(name) ?: return "${name.removePrefix("set_").removePrefix("add_")} (another tool)"
        val args = tool.fromRequest(resolved, request)
        if (!tool.grounded(args, request)) return "ask"
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
                // AddReminder.prepare's order: the resolved time; else the request's words, then the model's "time".
                val resolvedHere = args[TimeArgs.RESOLVED] == true
                val at = (args[TimeArgs.AT] as? String)?.let(LocalDateTime::parse)
                    ?: (if (resolvedHere) null else ReminderTime.read(request, local) ?: (args["time"] as? String)?.let { ReminderTime.read(it, local) })
                    ?: return "ask"
                val repeat = if (resolvedHere) args[TimeArgs.REPEAT] as? String else Repeat.said(request)?.key
                "reminder $at" + (repeat?.let { " repeat=$it" } ?: "")
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
                val t = tally.getOrPut(key) { IntArray(4) }
                t[0]++; if (ok) t[1]++; if (!ok && got == "ask") t[2]++; if (!ok && got != "ask") t[3]++
            }
            if (!ok) out.append("MISS ${r.getString("split")} [${r.getString("category")}] ${r.getString("request")}: expected $expected, got $got; when=${call?.opt("when")}\n")
        }
        val head = StringBuilder("Times: right / lines; of the misses, asked and wrong runs (a wrong value, or a tool where none was wanted)\n")
        tally.forEach { (k, t) -> head.append("  ${k.padEnd(22)} ${t[1]}/${t[0]}  asked ${t[2]}  wrong ${t[3]}\n") }
        File("build/tool-check/times-report.txt").writeText(head.toString() + "\n" + out)
    }
}
