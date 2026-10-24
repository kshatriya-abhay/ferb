package dev.abhay.assistant.eval

import dev.abhay.assistant.reminders.Repeat
import dev.abhay.assistant.reminders.ReminderTime
import dev.abhay.assistant.tools.DateResolver
import dev.abhay.assistant.tools.Durations
import dev.abhay.assistant.tools.TimeOfDay
import dev.abhay.assistant.tools.When
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The date and time eval (tool-check/datetime.txt), on the laptop: Needle 3's answers from
 * tool-check/dates.py (FERB_EVAL_NEEDLE, default build/datetime/needle-typed.jsonl), resolved by
 * today's code (baseline) and by [DateResolver] (A), each line against its expected time. Runs only
 * with FERB_EVAL=1 (not part of `mise run test`); the report goes to build/datetime/.
 */
class DateTimeEval {
    private data class Line(val i: Int, val now: LocalDateTime, val tool: String, val expected: String, val request: String)
    private data class Answer(val text: String?, val tool: String?, val heldBack: Boolean, val args: JSONObject, val confidence: Double?)

    private val repo = File("..").canonicalFile

    private fun lines() = File(repo, "tool-check/datetime.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.mapIndexed { i, raw ->
        val (now, tool, expected, request) = raw.split("|", limit = 4).map { it.trim() }
        Line(i, LocalDateTime.parse(now.substringBefore(' ')), tool, expected, request)
    }

    private fun answers(file: File): Map<Int, Answer> = file.readLines().filter { it.isNotBlank() }.associate { l ->
        val o = JSONObject(l)
        val raw = o.optString("raw").takeIf { o.has("raw") && !o.isNull("raw") }?.let(::JSONObject)
        val calls = raw?.optJSONArray("function_calls")
        val held = raw?.optJSONArray("suppressed_calls")
        val call = calls?.optJSONObject(0) ?: held?.optJSONObject(0)
        o.getInt("i") to Answer(o.optString("text").takeIf { o.has("text") && !o.isNull("text") }, call?.optString("name"),
            calls?.optJSONObject(0) == null && call != null, call?.optJSONObject("arguments") ?: JSONObject(),
            raw?.takeIf { it.has("confidence") }?.optDouble("confidence"))
    }

    private fun Repeat?.key() = when (this) {
        Repeat.Daily -> "daily"; Repeat.Weekly -> "weekly"; Repeat.Monthly -> "monthly"; Repeat.Yearly -> "yearly"; null -> null
    }

    private fun at(t: LocalDateTime, rule: String? = null) = t.toString().take(16) + (rule?.let { " $it" } ?: "")

    /** Today's code, as the tools read time now. */
    private fun baseline(line: Line, a: Answer, text: String): String {
        val now = line.now
        return when (line.tool) {
            "add_reminder" -> {
                val r = ReminderTime.read(text, now) ?: a.args.optString("time").takeIf { it.isNotBlank() }?.let { ReminderTime.read(it, now) }
                if (r == null || !r.isAfter(now)) "ask" else at(r, Repeat.said(text).key())
            }
            "set_alarm" -> {
                val (h, m) = a.args.optString("time").takeIf { it.isNotBlank() }?.let { TimeOfDay.parse(it) } ?: return "ask"
                val ring = now.toLocalDate().atTime(LocalTime.of(h, m)).let { if (it.isAfter(now)) it else it.plusDays(1) }
                at(ring)
            }
            "set_timer" -> a.args.optString("duration").takeIf { it.isNotBlank() }?.let { Durations.parse(it) }?.let(::length) ?: "ask"
            "add_event" -> {
                val said = When.read(text, now)
                when {
                    said.isEmpty -> "ask"
                    said.time == null -> (said.day ?: now.toLocalDate()).toString()
                    else -> {
                        val day = said.day ?: if (said.time.isAfter(now.toLocalTime())) now.toLocalDate() else now.toLocalDate().plusDays(1)
                        at(day.atTime(said.time))
                    }
                }
            }
            "show_agenda" -> (When.day(text, now.toLocalDate()) ?: now.toLocalDate()).toString()
            else -> "ask"
        }
    }

    /** A: [DateResolver] on the words; a timer's length from Needle 3's argument, as before. */
    private fun approachA(line: Line, a: Answer, text: String): String {
        val kind = when (line.tool) {
            "add_reminder" -> DateResolver.Kind.Reminder
            "set_alarm" -> DateResolver.Kind.Alarm
            "add_event" -> DateResolver.Kind.Event
            "show_agenda" -> DateResolver.Kind.Agenda
            else -> return a.args.optString("duration").takeIf { it.isNotBlank() }?.let { Durations.parse(it) }?.let(::length)
                ?: Durations.parse(text.substringAfter(" for ", text))?.let(::length) ?: "ask"
        }
        return DateResolver.format(DateResolver.resolve(text, kind, line.now))
    }

    private fun length(seconds: Int): String {
        val h = seconds / 3600; val m = seconds % 3600 / 60; val s = seconds % 60
        return "+PT" + (if (h > 0) "${h}H" else "") + (if (m > 0) "${m}M" else "") + (if (s > 0) "${s}S" else "")
    }

    private val hindi = Regex("""\b(kal|parso|aaj|agle|subah|shaam|raat|dopahar|baje|yaad)\b""")

    @Test
    fun score() {
        assumeTrue("FERB_EVAL=1 to run the date and time eval", System.getenv("FERB_EVAL") == "1")
        val source = File(System.getenv("FERB_EVAL_NEEDLE") ?: File(repo, "build/datetime/needle-typed.jsonl").path)
        val name = source.nameWithoutExtension.removePrefix("needle-")
        val lines = lines()
        val answers = answers(source)
        val report = StringBuilder("# Dates and times, Needle 3 on \"$name\" text (${lines.size} lines)\n")
        data class Row(val line: Line, val a: Answer, val base: String, val a2: String)
        val rows = lines.map { line ->
            val a = answers.getValue(line.i)
            // The words the resolvers read: what Needle 3 was given (the request, or its rewrite).
            val text = a.text ?: line.request
            Row(line, a, baseline(line, a, text), approachA(line, a, text))
        }
        fun pct(n: Int, of: Int) = if (of == 0) "-" else "$n/$of (${n * 100 / of}%)"
        fun block(title: String, set: List<Row>) {
            val toolRun = set.count { it.a.tool == it.line.tool && !it.a.heldBack }
            val toolAny = set.count { it.a.tool == it.line.tool }
            report.append("\n## $title (${set.size})\n")
            report.append("tool: ${pct(toolRun, set.size)} called; ${pct(toolAny, set.size)} incl. held back\n")
            for ((label, pick) in listOf("baseline" to { r: Row -> r.base }, "A" to { r: Row -> r.a2 })) {
                val right = set.count { pick(it) == it.line.expected }
                val asks = set.filter { it.line.expected == "ask" }
                val wrongAsk = set.count { it.line.expected != "ask" && pick(it) == "ask" }
                val wrongTime = set.count { it.line.expected != "ask" && pick(it) != "ask" && pick(it) != it.line.expected }
                val endToEnd = set.count { pick(it) == it.line.expected && it.a.tool == it.line.tool }
                report.append("$label: time right ${pct(right, set.size)}; end to end (tool and time) ${pct(endToEnd, set.size)}; " +
                    "asks right ${pct(asks.count { pick(it) == "ask" }, asks.size)}; asked when it needn't $wrongAsk; wrong time $wrongTime\n")
            }
        }
        block("all", rows)
        block("Hindi and Hinglish", rows.filter { hindi.containsMatchIn(it.line.request.lowercase()) })
        rows.groupBy { it.line.tool }.forEach { (tool, set) -> block(tool, set) }
        // Needle 3's confidence on the timed tools it got right.
        report.append("\n## Needle 3's confidence on the right tool (median / min, called or held back)\n")
        rows.filter { it.a.tool == it.line.tool && it.a.confidence != null }.groupBy { it.line.tool }.forEach { (tool, set) ->
            val c = set.mapNotNull { it.a.confidence }.sorted()
            report.append("$tool: ${"%.2f".format(c[c.size / 2])} / ${"%.2f".format(c.first())} (n=${c.size}, held back ${set.count { it.a.heldBack }})\n")
        }
        report.append("\n## Lines A gets wrong\n")
        rows.filter { it.a2 != it.line.expected }.forEach {
            report.append("${it.line.now} | ${it.line.tool} | want ${it.line.expected} | A ${it.a2} | base ${it.base} | ${it.line.request}\n")
        }
        val out = File(repo, "build/datetime/score-$name.txt")
        out.parentFile.mkdirs()
        out.writeText(report.toString())
        println(report)
    }

    /**
     * A on tool-check/times.txt (2026-10-16), a set it was never debugged against, from the
     * request text alone (no model). Two rules differ there and are counted apart: an alarm with
     * a day was "ask" (the alarm tool then took only a time), and an event with no time was a day.
     */
    @Test
    fun timesTxt() {
        assumeTrue("FERB_EVAL=1 to run the date and time eval", System.getenv("FERB_EVAL") == "1")
        var now = LocalDateTime.of(2026, 10, 16, 9, 0)
        val rows = mutableListOf<Triple<String, String, String>>()
        File(repo, "tool-check/times.txt").readLines().forEach { raw ->
            Regex("""^# now=(\S+)""").find(raw)?.let { now = LocalDateTime.parse(it.groupValues[1]); return@forEach }
            if (raw.isBlank() || raw.startsWith("#")) return@forEach
            val parts = raw.split("|").map { it.trim() }
            val head = parts[0].split(" ")
            val kind = when (head[0]) {
                "alarm" -> DateResolver.Kind.Alarm
                "event" -> DateResolver.Kind.Event
                "reminder" -> DateResolver.Kind.Reminder
                "ask" -> if (Regex("alarm|wake").containsMatchIn(parts[1])) DateResolver.Kind.Alarm else DateResolver.Kind.Reminder
                else -> return@forEach // timers (lengths, read from Needle's argument) and "none"
            }
            val want = if (head[0] == "ask") "ask" else head.drop(1).joinToString(" ").replace("repeat=", "")
            rows += Triple(want, DateResolver.format(DateResolver.resolve(parts[1], kind, now)), "${now.toString().take(16)} | ${head[0]} | ${parts[1]}")
        }
        val right = rows.count { it.first == it.second }
        val wrong = rows.filter { it.first != it.second }
        fun rule(r: Triple<String, String, String>) = when {
            r.first == "ask" && r.third.contains("| ask |") && Regex("alarm|wake").containsMatchIn(r.third) -> "alarm with a day (old tool)"
            r.third.contains("| event |") && r.first.length == 10 && r.second.startsWith(r.first) && r.second.endsWith("T09:00") -> "event with no time (all day then)"
            else -> null
        }
        val report = StringBuilder("# A on tool-check/times.txt (no model)\n\n")
        report.append("Lines: ${rows.size}; A matches $right (${right * 100 / rows.size}%)\n")
        wrong.groupBy { rule(it) }.forEach { (why, set) ->
            report.append("\n## ${why ?: "Misses"} (${set.size})\n")
            set.forEach { report.append("want ${it.first} | A ${it.second} | ${it.third}\n") }
        }
        val out = File(repo, "build/datetime/score-times-txt.txt")
        out.parentFile.mkdirs()
        out.writeText(report.toString())
        println(report)
    }
}
