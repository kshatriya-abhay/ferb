package dev.abhay.assistant.eval

import dev.abhay.assistant.reminders.Repeat
import dev.abhay.assistant.reminders.ReminderTime
import dev.abhay.assistant.tools.DateResolver
import dev.abhay.assistant.tools.Durations
import dev.abhay.assistant.tools.TimeExpr
import dev.abhay.assistant.tools.TimeOfDay
import dev.abhay.assistant.tools.ToolRouter
import dev.abhay.assistant.tools.ToolSettings
import dev.abhay.assistant.tools.Tools
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

    private fun lines() = File(repo, "tool-check/" + (System.getenv("FERB_EVAL_SET") ?: "datetime.txt")).readLines().filter { it.isNotBlank() && !it.startsWith("#") }.mapIndexed { i, raw ->
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

    /**
     * C: Gemini Nano's "time" v1 JSON said back as plain words, which [DateResolver] reads by the
     * set's rules (so C and A differ only in who read the request); a timer takes its length.
     * JSON that doesn't parse, or "none", asks.
     */
    private fun approachC(tool: String, raw: String?, now: LocalDateTime): String {
        val json = raw?.let { Regex("""\{[\s\S]*\}""").find(it)?.value } ?: return "ask"
        val expr = TimeExpr.parse(json) ?: return "ask"
        if (tool == "set_timer") return when (expr) {
            is TimeExpr.Lasting -> length(expr.length.total.seconds.toInt())
            is TimeExpr.In -> length(expr.length.total.seconds.toInt())
            else -> "ask"
        }
        val kind = when (tool) {
            "add_reminder" -> DateResolver.Kind.Reminder
            "set_alarm" -> DateResolver.Kind.Alarm
            "add_event" -> DateResolver.Kind.Event
            "show_agenda" -> DateResolver.Kind.Agenda
            else -> return "ask"
        }
        return DateResolver.format(DateResolver.resolve(words(expr), kind, now))
    }

    private val MONTH_NAMES = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")

    private fun words(expr: TimeExpr): String = when (expr) {
        is TimeExpr.At -> listOfNotNull(expr.day?.let { day(it, expr.time?.part) }, expr.time?.let(::clock)).joinToString(" ")
        is TimeExpr.In, is TimeExpr.Lasting -> {
            val l = (expr as? TimeExpr.In)?.length ?: (expr as TimeExpr.Lasting).length
            if (l.days > 0) "in ${l.days} days"
            else "in " + listOfNotNull(l.hours.takeIf { it > 0 }?.let { "$it hours" }, (l.minutes + (l.seconds + 30) / 60).takeIf { it > 0 }?.let { "$it minutes" }).joinToString(" ")
        }
        is TimeExpr.Every -> {
            val every = when (expr.period) {
                "day" -> "every day"
                "weekdays" -> "every weekday"
                "month" -> "every month"
                else -> if (expr.on.isEmpty()) "every week" else "every " + expr.on.joinToString(" and ") { it.name.lowercase() }
            }
            listOfNotNull(every, expr.time?.let(::clock)).joinToString(" ")
        }
    }

    private fun day(d: TimeExpr.Day, part: String?): String = when (d) {
        is TimeExpr.Day.Relative -> when (d.days) { 0 -> if (part == "night") "tonight" else "today"; 1 -> "tomorrow"; 2 -> "day after tomorrow"; else -> "in ${d.days} days" }
        is TimeExpr.Day.Weekday -> (if (d.next) "next " else "") + d.weekday.name.lowercase()
        is TimeExpr.Day.Date -> when {
            d.month == null -> "the ${d.day}th"
            d.year != null -> "${d.day}/${d.month}/${d.year}"
            else -> "${d.day} ${MONTH_NAMES[d.month - 1]}"
        }
    }

    private fun clock(c: TimeExpr.Clock): String? {
        val part = c.part?.let { if (it == "night") "at night" else "in the $it" }
        val hour = c.hour ?: return part
        if (hour == 12 && (c.minute ?: 0) == 0 && c.meridiem == "am") return "at midnight"
        val at = "at $hour:${"%02d".format(c.minute ?: 0)}" + (c.meridiem?.let { " $it" } ?: "")
        return listOfNotNull(at, part).joinToString(" ")
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
        // C: Gemini Nano's time JSON for each line (phone.py dates-out), resolved by Ferb's rules.
        val slots = System.getenv("FERB_EVAL_SLOTS")?.let(::File)?.readLines()?.filter { it.isNotBlank() }?.associate { l ->
            val o = JSONObject(l)
            o.getInt("i") to o.optString("raw").takeIf { o.has("raw") && !o.isNull("raw") }
        }
        val name = source.nameWithoutExtension.removePrefix("needle-") + if (slots != null) "-slots" else ""
        val lines = lines()
        val answers = answers(source)
        val report = StringBuilder("# Dates and times, Needle 3 on \"$name\" text (${lines.size} lines)\n")
        data class Row(val line: Line, val a: Answer, val base: String, val a2: String, val c: String? = null)
        val rows = lines.map { line ->
            val a = answers.getValue(line.i)
            // The words the resolvers read: what Needle 3 was given (the request, or its rewrite).
            val text = a.text ?: line.request
            Row(line, a, baseline(line, a, text), approachA(line, a, text), slots?.let { approachC(line.tool, slots[line.i], line.now) })
        }
        fun pct(n: Int, of: Int) = if (of == 0) "-" else "$n/$of (${n * 100 / of}%)"
        fun block(title: String, set: List<Row>) {
            val toolRun = set.count { it.a.tool == it.line.tool && !it.a.heldBack }
            val toolAny = set.count { it.a.tool == it.line.tool }
            report.append("\n## $title (${set.size})\n")
            report.append("tool: ${pct(toolRun, set.size)} called; ${pct(toolAny, set.size)} incl. held back\n")
            for ((label, pick) in listOf("baseline" to { r: Row -> r.base }, "A" to { r: Row -> r.a2 }) +
                listOfNotNull(slots?.let { "C" to { r: Row -> r.c!! } })) {
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
        // What Ferb does with each line under a gate: runs a call (right, or wrong: the tool, the
        // time, or a run where it should ask) or asks. A wrong run is the worst; an ask is safe.
        val configs = ToolSettings.defaults()
        val timed = setOf("add_reminder", "set_alarm", "set_timer", "add_event", "show_agenda")
        fun routedAlone(r: Row) = ToolRouter.route(r.a.text ?: r.line.request, Tools.all, configs).map { it.name }.filter { it in timed } == listOf(r.a.tool)
        fun resolvedFor(r: Row): String = r.a.tool?.let { tool -> approachA(r.line.copy(tool = tool), r.a, r.a.text ?: r.line.request) } ?: "ask"
        val gates = listOf<Pair<String, (Row) -> String?>>(
            "today (Needle's bar, today's times)" to { r -> if (r.a.tool != null && !r.a.heldBack) r.base.takeIf { r.a.tool == r.line.tool } ?: "wrong tool" else null },
            "A, Needle's bar" to { r -> if (r.a.tool != null && !r.a.heldBack) resolvedFor(r) else null },
            "E: A, and held back runs when the router routes only that timed tool" to { r ->
                if (r.a.tool != null && (!r.a.heldBack || routedAlone(r))) resolvedFor(r) else null
            },
        ) + listOf<Pair<String, (Row) -> String?>>(
            // The hybrid: the tool from Needle 3 on the (rewritten) text, the time from A on the words
            // as said, and asking when the words as said name other timed tools but not this one.
            "H: E's gate, A on the words as said, the said words' tools agreeing" to { r ->
                val said = ToolRouter.route(r.line.request, Tools.all, configs).map { it.name }.filter { it in timed }
                if (r.a.tool != null && (!r.a.heldBack || routedAlone(r)) && (said.isEmpty() || r.a.tool in said))
                    approachA(r.line.copy(tool = r.a.tool), r.a, r.line.request) else null
            },
        ) + if (slots == null) emptyList() else listOf<Pair<String, (Row) -> String?>>(
            "C, Needle's bar" to { r -> if (r.a.tool != null && !r.a.heldBack) approachC(r.a.tool, slots[r.line.i], r.line.now) else null },
            "E with C's time" to { r -> if (r.a.tool != null && (!r.a.heldBack || routedAlone(r))) approachC(r.a.tool, slots[r.line.i], r.line.now) else null },
        )
        report.append("\n## Decisions: run right / run wrong / asked (when it should ask: asked)\n")
        for ((label, gate) in gates) {
            for ((set, title) in listOf(rows to "all", rows.filter { hindi.containsMatchIn(it.line.request.lowercase()) } to "Hindi")) {
                var right = 0; var wrong = 0; var asked = 0; var askRight = 0
                set.forEach { r ->
                    val did = gate(r)?.takeIf { it != "ask" }
                    val toolRight = r.a.tool == r.line.tool
                    when {
                        did == null -> if (r.line.expected == "ask") askRight++ else asked++
                        r.line.expected != "ask" && toolRight && did == r.line.expected -> right++
                        else -> wrong++
                    }
                }
                val asks = set.count { it.line.expected == "ask" }
                report.append("$label, $title: run right ${pct(right, set.size)}; run wrong $wrong; asked needlessly $asked; asked when it should $askRight/$asks\n")
            }
        }
        report.append("\n## E's wrong runs\n")
        rows.filter { r -> gates[2].second(r)?.let { it != "ask" && !(r.a.tool == r.line.tool && it == r.line.expected) } == true }.forEach { r ->
            report.append("${r.line.now} | want ${r.line.tool} ${r.line.expected} | ran ${r.a.tool} ${gates[2].second(r)} | ${r.line.request}\n")
        }
        gates.firstOrNull { it.first.startsWith("H:") }?.let { (_, gate) ->
            report.append("\n## H's wrong runs\n")
            rows.filter { r -> gate(r)?.let { it != "ask" && !(r.a.tool == r.line.tool && it == r.line.expected) } == true }.forEach { r ->
                report.append("${r.line.now} | want ${r.line.tool} ${r.line.expected} | ran ${r.a.tool} ${gate(r)} | ${r.line.request} | text ${r.a.text}\n")
            }
        }
        // C against A, both reading the line's own tool: Gemini Nano's slots or A's regex on the request as typed.
        if (slots != null) {
            data class Pair2(val line: Line, val c: String, val a: String)
            val both = lines.map { line -> Pair2(line, approachC(line.tool, slots[line.i], line.now), approachA(line, answers.getValue(line.i), line.request)) }
            val differ = both.filter { it.c != it.a }
            report.append("\n## C's slots against A's own reading of the typed request (the line's tool)\n")
            report.append("agree ${both.size - differ.size}/${both.size}; differ ${differ.size}: C right ${differ.count { it.c == it.line.expected }}, " +
                "A right ${differ.count { it.a == it.line.expected }}, neither ${differ.count { it.c != it.line.expected && it.a != it.line.expected }}\n")
            val hi = both.filter { hindi.containsMatchIn(it.line.request.lowercase()) }
            report.append("Hindi: C right ${hi.count { it.c == it.line.expected }}/${hi.size}, A right ${hi.count { it.a == it.line.expected }}/${hi.size}\n")
            report.append("C unreadable (no JSON or not v1): ${lines.count { l -> slots[l.i]?.let { r -> Regex("""\{[\s\S]*\}""").find(r)?.value?.let { TimeExpr.parse(it) } } == null }}\n")
            differ.forEach { report.append("${it.line.now} | ${it.line.tool} | want ${it.line.expected} | C ${it.c} | A ${it.a} | ${it.line.request} | slots ${slots[it.line.i]?.replace("\n", " ")}\n") }
        }
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
     * The main bench (`mise run tool-check` first: build/tool-check/requests.txt and its answers):
     * what E adds (timed calls under their bar that it runs) and whether they're the right tool,
     * and alarm and timer values from A against today's code. The bench has no clock: 04:30 on a
     * Friday, so a bare alarm hour is the morning's, as the bench writes it.
     */
    @Test
    fun mainBench() {
        assumeTrue("FERB_EVAL=1 to run the date and time eval", System.getenv("FERB_EVAL") == "1")
        val requests = File(repo, "build/tool-check/requests.txt")
        val answersFile = File(repo, "app/build/tool-check/all.answers")
        assumeTrue("mise run tool-check first", requests.exists() && answersFile.exists())
        val now = LocalDateTime.of(2026, 10, 16, 4, 30)
        val replies = answersFile.readLines().filter { '\t' in it }.associate { it.substringBefore('\t') to JSONObject(it.substringAfter('\t')) }
        val configs = ToolSettings.defaults()
        val timed = setOf("add_reminder", "set_alarm", "set_timer", "add_event", "show_agenda")
        val report = StringBuilder("# E and A on the main bench (build/tool-check/requests.txt, Needle 3 routed)\n")
        var extraRight = 0; val extraWrong = mutableListOf<String>()
        var alarms = 0; var alarmToday = 0; var alarmA = 0; val alarmDiff = mutableListOf<String>()
        requests.readLines().filter { it.isNotBlank() && !it.startsWith("#") && "|" in it }.forEach { raw ->
            val (spec, request) = raw.split("|", limit = 2).map { it.trim() }
            val want = spec.split(" ")
            val tools = want[0].split("/")
            val args = want.drop(1).associate { it.substringBefore("=") to it.substringAfter("=") }
            val reply = replies[request] ?: return@forEach
            val call = reply.optJSONArray("function_calls")?.optJSONObject(0) ?: return@forEach
            val tool = call.getString("name")
            if (tool !in timed) return@forEach
            val confidence = reply.optDouble("confidence")
            val bar = configs.getValue(tool).runFrom
            val routed = ToolRouter.route(request, Tools.all, configs).map { it.name }.filter { it in timed }
            val kind = mapOf("add_reminder" to DateResolver.Kind.Reminder, "set_alarm" to DateResolver.Kind.Alarm,
                "add_event" to DateResolver.Kind.Event, "show_agenda" to DateResolver.Kind.Agenda)[tool]
            val resolved = kind?.let { DateResolver.resolve(request, it, now) }
            val runsA = resolved != null && resolved !is DateResolver.Resolved.Ask || tool == "set_timer"
            if (confidence < bar && routed == listOf(tool) && runsA) {
                if (tool in tools) extraRight++ else extraWrong += "want ${want[0]} | ran $tool ${"%.2f".format(confidence)} | $request"
            }
            if (tool == "set_alarm" && "set_alarm" in tools && args["time"] != null) {
                alarms++
                val today = call.optJSONObject("arguments")?.optString("time")?.let { TimeOfDay.parse(it) }?.let { (h, m) -> "%02d:%02d".format(h, m) }
                val a = (resolved as? DateResolver.Resolved.At)?.at?.toLocalTime()?.toString()
                if (today == args["time"]) alarmToday++
                if (a == args["time"]) alarmA++
                if (a != args["time"]) alarmDiff += "want ${args["time"]} | today $today | A ${a ?: "ask"} | $request"
            }
        }
        report.append("\nE's extra runs (under the bar, routed only to that timed tool, A resolves): right tool $extraRight, wrong ${extraWrong.size}\n")
        extraWrong.forEach { report.append("  $it\n") }
        report.append("\nAlarm times (lines with time=): today's code $alarmToday/$alarms, A $alarmA/$alarms\n")
        alarmDiff.forEach { report.append("  $it\n") }
        val out = File(repo, "build/datetime/score-main-bench.txt")
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
