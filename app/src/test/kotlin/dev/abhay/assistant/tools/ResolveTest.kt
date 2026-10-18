package dev.abhay.assistant.tools

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.reminders.Repeat
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.json.JSONObject
import org.junit.Test

/**
 * [Resolve] on every line of tool-check/times.txt: its gold structure (the "time" v1 shape in
 * short) resolved for its tool against its "now" gives its expected value. The same file scores
 * the computer's extraction (a model evaluation); here the structure is given, so this is Ferb's
 * rules alone.
 */
class ResolveTest {
    private class Line(val now: ZonedDateTime, val expected: String, val request: String, val gold: String)

    private val lines: List<Line> = run {
        var now: ZonedDateTime? = null
        File("../tool-check/times.txt").readLines().mapNotNull { raw ->
            val l = raw.trim()
            Regex("""^# now=(\S+) (\S+)""").find(l)?.let { now = LocalDateTime.parse(it.groupValues[1]).atZone(ZoneId.of(it.groupValues[2])) }
            if (l.isEmpty() || l.startsWith("#")) null
            else l.split("|").map { it.trim() }.let { (expected, request, gold) -> Line(now!!, expected, request, gold) }
        }
    }

    private val days = mapOf("mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thu" to DayOfWeek.THURSDAY, "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY)

    /** "1h30m", "2m30s", "3d" as a length. */
    private fun length(text: String): TimeExpr.Length {
        val parts = Regex("""(\d+)([dhms])""").findAll(text).associate { it.groupValues[2] to it.groupValues[1].toInt() }
        return TimeExpr.Length(parts["d"] ?: 0, parts["h"] ?: 0, parts["m"] ?: 0, parts["s"] ?: 0)
    }

    /** "7:15am" as a clock, with the part of the day. */
    private fun clock(time: String?, part: String?): TimeExpr.Clock? {
        if (time == null && part == null) return null
        val m = time?.let { Regex("""^(\d{1,2})(?::(\d{2}))?(am|pm)?$""").matchEntire(it)!! }
        return TimeExpr.Clock(m?.groupValues?.get(1)?.toInt(), m?.groupValues?.get(2)?.ifEmpty { null }?.toInt() ?: m?.let { 0 },
            m?.groupValues?.get(3)?.ifEmpty { null }, part)
    }

    /** The gold structure in short ("at day=+1 time=7:15am part=morning") as a [TimeExpr]; null for none. */
    private fun gold(text: String): TimeExpr? {
        val words = text.split(" ")
        val keys = words.drop(1).filter { "=" in it }.associate { it.substringBefore("=") to it.substringAfter("=") }
        val source = if (keys["src"] == "weekend") "this weekend" else ""
        return when (words[0]) {
            "none" -> null
            "in" -> TimeExpr.In(length(words[1]))
            "duration" -> TimeExpr.Lasting(length(words[1]))
            "every" -> TimeExpr.Every(words[1], keys["on"]?.split(",")?.map { days.getValue(it) }.orEmpty(), clock(keys["time"], keys["part"]))
            "at" -> TimeExpr.At(keys["day"]?.let { d ->
                when {
                    d.startsWith("+") -> TimeExpr.Day.Relative(d.drop(1).toInt())
                    d.startsWith("next:") -> TimeExpr.Day.Weekday(days.getValue(d.drop(5)), next = true)
                    d.startsWith("this:") -> TimeExpr.Day.Weekday(days.getValue(d.drop(5)), next = false)
                    d in days -> TimeExpr.Day.Weekday(days.getValue(d), next = false)
                    else -> d.split("/").map { it.toInt() }.let { TimeExpr.Day.Date(it[0], it.getOrNull(1), it.getOrNull(2)) }
                }
            }, clock(keys["time"], keys["part"]), source)
            else -> error("unknown gold structure: $text")
        }
    }

    /** A resolved value in times.txt's terms ("alarm 2026-10-17T07:15", "timer 150s", "ask"). */
    private fun shown(kind: String, resolved: Resolved): String = when (resolved) {
        is Resolved.Ask -> "ask"
        is Resolved.At -> "$kind ${resolved.at}"
        is Resolved.OnDay -> "$kind ${resolved.day}"
        is Resolved.Lasting -> "$kind ${resolved.length.seconds}s"
        is Resolved.Every -> "$kind ${resolved.first} repeat=${resolved.repeat.key}"
    }

    @Test
    fun theSetIsThere() {
        assertThat(lines.size).isAtLeast(150)
    }

    @Test
    fun everyGoldStructureResolvesToItsExpectedValue() {
        val wrong = lines.mapNotNull { line ->
            val expr = gold(line.gold)
            val got = when {
                expr == null -> "none"
                else -> {
                    // An "ask" line's tool, from what it asks for.
                    val kind = line.expected.substringBefore(" ").takeUnless { it == "ask" } ?: when {
                        "alarm" in line.request || "wake" in line.request -> "alarm"
                        "remind" in line.request -> "reminder"
                        else -> "event"
                    }
                    val tool = Resolve.For.valueOf(kind.replaceFirstChar { it.uppercase() })
                    shown(kind, Resolve.resolve(expr, tool, line.now))
                }
            }
            "${line.request}: expected ${line.expected}, got $got".takeIf { got != line.expected }
        }
        assertThat(wrong).isEmpty()
    }

    @Test
    fun anAlarmOnAnotherDayAsksAndSaysWhatTheClockCanSet() {
        val friday9 = LocalDateTime.parse("2026-10-16T09:00").atZone(ZoneId.of("Asia/Kolkata"))
        val monday = TimeExpr.At(TimeExpr.Day.Weekday(DayOfWeek.MONDAY, next = false), TimeExpr.Clock(7, 0, "am", null))
        val ask = Resolve.resolve(monday, Resolve.For.Alarm, friday9) as Resolved.Ask
        assertThat(ask.ring).isEqualTo(LocalDateTime.parse("2026-10-17T07:00"))
        assertThat(ask.wanted).isEqualTo(LocalDateTime.parse("2026-10-19T07:00"))
        assertThat(ask.reason).isEqualTo("the Clock app can only set the next 07:00 (Sat 17 Oct)")
    }

    @Test
    fun theStructureIsReadFromItsJson() {
        val json = JSONObject("""{"v":1,"kind":"at","day":{"relative":1},"time":{"hour":7,"minute":15,"meridiem":"am","part":"morning"},"source":"kal subah sava saat baje"}""")
        assertThat(TimeExpr.parse(json)).isEqualTo(TimeExpr.At(TimeExpr.Day.Relative(1), TimeExpr.Clock(7, 15, "am", "morning"), "kal subah sava saat baje"))
        val every = JSONObject("""{"v":1,"kind":"every","period":"week","on":["monday"],"time":{"hour":8,"minute":0,"meridiem":null,"part":null},"source":"every Monday at 8"}""")
        assertThat(TimeExpr.parse(every)).isEqualTo(TimeExpr.Every("week", listOf(DayOfWeek.MONDAY), TimeExpr.Clock(8, 0, null, null), "every Monday at 8"))
        // Another version, or not a time at all: none.
        assertThat(TimeExpr.parse(JSONObject("""{"v":2,"kind":"at"}"""))).isNull()
        assertThat(TimeExpr.parse("7:15 am")).isNull()
        assertThat(Repeat.Weekly.key).isEqualTo("weekly")
    }
}
