package dev.abhay.assistant.eval

import dev.abhay.assistant.eval.frozen.DateResolverA0
import dev.abhay.assistant.eval.frozen.DateResolverA1
import dev.abhay.assistant.tools.DateResolver
import dev.abhay.assistant.tools.Durations
import dev.abhay.assistant.tools.ToolRouter
import dev.abhay.assistant.tools.ToolSettings
import dev.abhay.assistant.tools.Tools
import io.github.ggutim.parser.NaturalDateParser
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The independent date readers (2026-10-24, frozen before the blind set was run): each reads a
 * text, with the tool it's for and the moment it was said, as a value in the date sets' format
 * ("2026-10-31T09:00", "+PT10M", "2026-10-25") or "ask". Scored on a set (FERB_EVAL_SET), for the
 * words as said and for prompt R's rewrite, alone and in agreeing pairs. See [DateReadersEval].
 */
object DateReaders {
    fun interface Reader {
        fun read(text: String, tool: String, now: LocalDateTime): String
    }

    private val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    private val DEFAULT_TIME: LocalTime = LocalTime.of(9, 0)

    fun length(seconds: Long): String {
        val h = seconds / 3600; val m = seconds % 3600 / 60; val s = seconds % 60
        return "+PT" + (if (h > 0) "${h}H" else "") + (if (m > 0) "${m}M" else "") + (if (s > 0) "${s}S" else "")
    }

    /** A timer's length in [t] (already normalised): the run of number-and-unit words, as Durations reads them. */
    private fun lengthIn(t: String): String {
        val unit = """(?:hours?|hrs?|minutes?|mins?|seconds?|secs?)"""
        val num = """(?:\d+(?:\.\d+)?|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|fifty|sixty|ninety|half an?|a quarter of an?)"""
        val m = Regex("""$num(?: and a half)?\s*$unit(?:,?\s*(?:and\s+)?$num\s*$unit)*(?: and a half)?""").find(t) ?: return "ask"
        return Durations.parse(m.value)?.let { length(it.toLong()) } ?: "ask"
    }

    val A0 = Reader { text, tool, now ->
        val kind = when (tool) {
            "add_reminder" -> DateResolverA0.Kind.Reminder; "set_alarm" -> DateResolverA0.Kind.Alarm
            "add_event" -> DateResolverA0.Kind.Event; "show_agenda" -> DateResolverA0.Kind.Agenda
            "set_timer" -> return@Reader lengthIn(DateResolverA0.normalise(text))
            else -> return@Reader "ask"
        }
        runCatching { DateResolverA0.format(DateResolverA0.resolve(text, kind, now)) }.getOrDefault("ask")
    }

    val A1 = Reader { text, tool, now ->
        val kind = when (tool) {
            "add_reminder" -> DateResolverA1.Kind.Reminder; "set_alarm" -> DateResolverA1.Kind.Alarm
            "add_event" -> DateResolverA1.Kind.Event; "show_agenda" -> DateResolverA1.Kind.Agenda
            "set_timer" -> return@Reader lengthIn(DateResolverA1.normalise(text))
            else -> return@Reader "ask"
        }
        runCatching { DateResolverA1.format(DateResolverA1.resolve(text, kind, now)) }.getOrDefault("ask")
    }

    /**
     * A moment a parser found, with whether it has a clock time, as Ferb's rules use it: an agenda
     * takes the day; a reminder or event with no time is 09:00 on a later day and asks today; an
     * alarm with no time asks; a moment not after now asks. No repeats or lengths.
     */
    fun moment(at: LocalDateTime?, timed: Boolean, tool: String, now: LocalDateTime): String {
        at ?: return "ask"
        if (tool == "show_agenda") return at.toLocalDate().toString()
        if (tool == "set_timer") return "ask"
        val value = if (timed) at else {
            if (tool == "set_alarm" || at.toLocalDate() == now.toLocalDate()) return "ask"
            at.toLocalDate().atTime(DEFAULT_TIME)
        }
        return if (value.isAfter(now)) value.toString().take(16) else "ask"
    }

    private val ndp = NaturalDateParser.builder().build()

    /**
     * natural-date-parser: it answers `now` when it finds nothing, and keeps now's clock time
     * when no time is said; so "the same moment as now" is nothing, and "now's clock time" no time.
     */
    val NDP = Reader { text, tool, now ->
        val at = runCatching { ndp.parse(text, now) }.getOrNull()?.withSecond(0)?.withNano(0)
        if (at == null || at == now) "ask" else moment(at, at.toLocalTime() != now.toLocalTime(), tool, now)
    }

    /**
     * What a device reader found ([dev.abhay.datebench], ML Kit Entity Extraction or the system
     * TextClassifier), from its file: per line and text, the first date or time span's moment
     * (epoch ms, read with the line's now as reference) and whether it has a clock time.
     */
    fun device(file: File): DeviceReader? {
        if (!file.exists()) return null
        return DeviceReader(file.readLines().filter { it.isNotBlank() }.associate { l ->
            val o = JSONObject(l)
            (o.getInt("i") to o.getString("source")) to o
        })
    }

    class DeviceReader(private val found: Map<Pair<Int, String>, JSONObject>) {
        fun read(i: Int, source: String, tool: String, now: LocalDateTime): String {
            val o = found[i to source] ?: return "ask"
            if (o.isNull("millis")) return "ask"
            val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(o.getLong("millis")), ZONE).withSecond(0).withNano(0)
            return moment(at, o.optBoolean("timed"), tool, now)
        }
        fun ms(): List<Long> = found.values.mapNotNull { it.optLong("ms", -1).takeIf { ms -> ms >= 0 } }
    }
}

/**
 * The blind round (2026-10-24): every reader on the words as said and on R's rewrite, under H's
 * gate (the tool from Needle 3 on R's text; a held-back call runs when the router routes only that
 * timed tool; asks when the words as said name other timed tools), alone and as agreeing pairs
 * (run only when both give the same value). FERB_EVAL=1, FERB_EVAL_SET, FERB_EVAL_NEEDLE_R (Needle
 * 3 on R's rewrites, from dates.py), FERB_EVAL_DEVICE (a directory of reader-<name>.jsonl).
 */
class DateReadersEval {
    private val repo = File("..").canonicalFile
    private data class Line(val i: Int, val now: LocalDateTime, val tool: String, val expected: String, val request: String, val section: String)
    private data class Answer(val text: String?, val tool: String?, val heldBack: Boolean)

    @Test
    fun score() {
        assumeTrue("FERB_EVAL=1", System.getenv("FERB_EVAL") == "1")
        val set = System.getenv("FERB_EVAL_SET") ?: "datetime-v2.txt"
        val needleR = System.getenv("FERB_EVAL_NEEDLE_R")?.let(::File)
        assumeTrue("FERB_EVAL_NEEDLE_R", needleR?.exists() == true)
        val lines = File(repo, "tool-check/$set").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.let { raws ->
            // Sections again, in order, for the rows.
            var sec = ""
            val all = File(repo, "tool-check/$set").readLines()
            val out = mutableListOf<Line>()
            for (raw in all) {
                if (raw.startsWith("# ") && raw.length < 30) sec = raw.removePrefix("# ").trim()
                if (raw.isBlank() || raw.startsWith("#")) continue
                val (now, tool, expected, request) = raw.split(" | ", limit = 4).map { it.trim() }
                out += Line(out.size, LocalDateTime.parse(now.substringBefore(' ')), tool, expected, request, sec)
            }
            check(out.size == raws.size)
            out
        }
        val answers = needleR!!.readLines().filter { it.isNotBlank() }.associate { l ->
            val o = JSONObject(l)
            val raw = o.optString("raw").takeIf { o.has("raw") && !o.isNull("raw") }?.let(::JSONObject)
            val calls = raw?.optJSONArray("function_calls")
            val held = raw?.optJSONArray("suppressed_calls")
            val call = calls?.optJSONObject(0) ?: held?.optJSONObject(0)
            o.getInt("i") to Answer(o.optString("text").takeIf { o.has("text") && !o.isNull("text") }, call?.optString("name"), calls?.optJSONObject(0) == null && call != null)
        }
        val configs = ToolSettings.defaults()
        val timed = setOf("add_reminder", "set_alarm", "set_timer", "add_event", "show_agenda")
        fun routed(text: String) = ToolRouter.route(text, Tools.all, configs).map { it.name }.filter { it in timed }

        // The tool H runs for a line, or null when it asks.
        fun tool(l: Line): String? {
            val a = answers[l.i] ?: return null
            val t = a.tool ?: return null
            if (a.heldBack && routed(a.text ?: l.request) != listOf(t)) return null
            val said = routed(l.request)
            return t.takeIf { said.isEmpty() || it in said }
        }

        val device = System.getenv("FERB_EVAL_DEVICE")?.let(::File)
        val laptop = mapOf("A0" to DateReaders.A0, "A1" to DateReaders.A1, "NDP" to DateReaders.NDP)
        val devices = listOf("MLKIT", "TC").mapNotNull { n -> device?.let { DateReaders.device(File(it, "reader-${n.lowercase()}.jsonl")) }?.let { n to it } }.toMap()

        // Each reader on each text, timed (laptop readers).
        val ms = mutableMapOf<String, MutableList<Long>>()
        val values = mutableMapOf<String, Map<Int, String>>()
        for (l in lines) {
            val t = tool(l) ?: continue
            val texts = mapOf("said" to l.request, "R" to (answers[l.i]?.text ?: l.request))
            for ((source, text) in texts) {
                for ((name, reader) in laptop) {
                    val started = System.nanoTime()
                    val v = reader.read(text, t, l.now)
                    ms.getOrPut(name) { mutableListOf() } += (System.nanoTime() - started) / 1000
                    values["$name($source)"] = (values["$name($source)"] ?: emptyMap()) + (l.i to v)
                }
                for ((name, reader) in devices) values["$name($source)"] = (values["$name($source)"] ?: emptyMap()) + (l.i to reader.read(l.i, source, t, l.now))
            }
        }

        fun hindi(l: Line) = l.request.any { it in 'ऀ'..'ॿ' } ||
            Regex("""\b(kal|parso|narso|aaj|agle|subah|shaam|raat|dopahar|baje|yaad|dilana|utha|jaga)\b""").containsMatchIn(l.request.lowercase())
        val groups = listOf<Pair<String, (Line) -> Boolean>>("all" to { _ -> true }, "Hindi (Latin or Devanagari)" to ::hindi,
            "Devanagari" to { l -> l.request.any { it in 'ऀ'..'ॿ' } }) +
            lines.map { it.section }.filter { it.isNotEmpty() }.distinct().map { s -> "section $s" to { l: Line -> l.section == s } }

        fun pct(n: Int, of: Int) = if (of == 0) "-" else "$n/$of (${n * 100 / of}%)"
        fun score(decide: (Line) -> String?): Map<String, String> = groups.associate { (g, pick) ->
            val set2 = lines.filter(pick)
            var right = 0; var wrong = 0; var needless = 0; var askRight = 0
            for (l in set2) {
                val t = tool(l)
                val v = if (t == null) null else decide(l)?.takeIf { it != "ask" }
                when {
                    v == null -> if (l.expected == "ask") askRight++ else needless++
                    l.expected != "ask" && t == l.tool && v == l.expected -> right++
                    else -> wrong++
                }
            }
            g to "run right ${pct(right, set2.size)}; run wrong $wrong; needless ask $needless; asks right $askRight/${set2.count { it.expected == "ask" }}"
        }

        val report = StringBuilder("# Date readers on $set (${lines.size} lines), tool from Needle 3 on R under H's gate\n")
        report.append("\nH's tool alone (before any reader): right tool on ${lines.count { tool(it) == it.tool }}; no tool (asks) on ${lines.count { tool(it) == null }}\n")
        val keys = values.keys.sorted()
        report.append("\n## One reader\n")
        for (k in keys) { score { l -> values[k]?.get(l.i) }.forEach { (g, s) -> report.append("$k | $g | $s\n") } }
        report.append("\n## Two readers agreeing (run when both give the same value; else ask)\n")
        for (i in keys.indices) for (j in i + 1 until keys.size) {
            val (x, y) = keys[i] to keys[j]
            score { l -> values[x]?.get(l.i)?.takeIf { it == values[y]?.get(l.i) } }.forEach { (g, s) -> report.append("$x + $y | $g | $s\n") }
        }
        report.append("\n## Latency per read (laptop, µs median / 90th)\n")
        ms.forEach { (n, list) -> val s = list.sorted(); report.append("$n: ${s[s.size / 2]} / ${s[s.size * 9 / 10]}\n") }
        devices.forEach { (n, r) -> val s = r.ms().sorted(); if (s.isNotEmpty()) report.append("$n (phone, ms): ${s[s.size / 2]} / ${s[s.size * 9 / 10]}\n") }

        val out = File(repo, "build/datetime/readers-${set.removeSuffix(".txt")}.txt")
        out.parentFile.mkdirs()
        out.writeText(report.toString())
        println(report)
    }
}
