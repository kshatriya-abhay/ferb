package dev.abhay.assistant.tools

import dev.abhay.assistant.reminders.ReminderTime
import dev.abhay.assistant.reminders.Repeat
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * A time as the computer's model says it ("time" version 1, Plan - Dates and times through
 * Gemma): what was said, structured, with no arithmetic done. [Resolve] works out the date and
 * time by Ferb's own rules. [source] is the words it came from, for the grounding check.
 */
sealed interface TimeExpr {
    val source: String

    data class At(val day: Day?, val time: Clock?, override val source: String = "") : TimeExpr
    data class In(val length: Length, override val source: String = "") : TimeExpr
    data class Lasting(val length: Length, override val source: String = "") : TimeExpr
    data class Every(val period: String, val on: List<DayOfWeek>, val time: Clock?, override val source: String = "") : TimeExpr

    sealed interface Day {
        /** 0 today (aaj), 1 tomorrow (kal), 2 the day after (parso). */
        data class Relative(val days: Int) : Day
        data class Weekday(val weekday: DayOfWeek, val next: Boolean) : Day
        data class Date(val day: Int, val month: Int?, val year: Int?) : Day
    }

    /** An hour and minute as said, with am or pm and the part of the day when said. */
    data class Clock(val hour: Int?, val minute: Int?, val meridiem: String?, val part: String?)

    data class Length(val days: Int = 0, val hours: Int = 0, val minutes: Int = 0, val seconds: Int = 0) {
        val total: Duration get() = Duration.ofDays(days.toLong()).plusHours(hours.toLong()).plusMinutes(minutes.toLong()).plusSeconds(seconds.toLong())
        val numbers get() = listOf(days, hours, minutes, seconds).filter { it != 0 }
    }

    /** The integers this time holds, for the grounding check. */
    val numbers: List<Int> get() = when (this) {
        is At -> listOfNotNull((day as? Day.Date)?.day, (day as? Day.Date)?.year) + clock(time)
        is In -> length.numbers
        is Lasting -> length.numbers
        is Every -> clock(time)
    }

    companion object {
        const val VERSION = 1
        private val WEEKDAYS = DayOfWeek.entries.associateBy { it.name.lowercase() }

        private fun clock(time: Clock?) = listOfNotNull(time?.hour, time?.minute?.takeIf { it != 0 })

        /** Version 1 from its JSON (an object or a map, as a tool call's arguments hold it); null if it isn't one. */
        fun parse(value: Any?): TimeExpr? = runCatching {
            val o = when (value) {
                is JSONObject -> value
                is Map<*, *> -> JSONObject(value)
                is String -> JSONObject(value)
                else -> return null
            }
            if (o.optInt("v", VERSION) != VERSION) return null
            val source = o.optString("source")
            when (o.optString("kind")) {
                "at" -> At(day(o.optJSONObject("day")), clock(o.optJSONObject("time")), source)
                "in" -> In(length(o.getJSONObject("length")), source)
                "duration" -> Lasting(length(o.getJSONObject("length")), source)
                "every" -> Every(o.getString("period"), weekdays(o.optJSONArray("on")), clock(o.optJSONObject("time")), source)
                else -> null
            }
        }.getOrNull()

        private fun JSONObject.int(key: String): Int? = if (isNull(key) || !has(key)) null else getInt(key)
        private fun JSONObject.text(key: String): String? = if (isNull(key) || !has(key)) null else getString(key).ifBlank { null }

        private fun day(o: JSONObject?): Day? = when {
            o == null -> null
            o.has("relative") && !o.isNull("relative") -> Day.Relative(o.getInt("relative"))
            o.has("weekday") && !o.isNull("weekday") -> WEEKDAYS[o.getString("weekday")]?.let { Day.Weekday(it, o.text("which") == "next") }
            o.has("day") && !o.isNull("day") -> Day.Date(o.getInt("day"), o.int("month"), o.int("year"))
            else -> null
        }

        internal fun clock(o: JSONObject?): Clock? = o?.let { Clock(it.int("hour"), it.int("minute"), it.text("meridiem"), it.text("part")) }
            ?.takeIf { it.hour != null || it.part != null }

        private fun length(o: JSONObject) = Length(o.optInt("days"), o.optInt("hours"), o.optInt("minutes"), o.optInt("seconds"))

        private fun weekdays(a: JSONArray?) = a?.let { (0 until it.length()).mapNotNull { i -> WEEKDAYS[it.getString(i)] } }.orEmpty()
    }
}

/** What a time resolves to for one tool, or why Ferb should ask. */
sealed interface Resolved {
    /** An alarm's ring, a reminder's time, an event's start. */
    data class At(val at: LocalDateTime) : Resolved
    /** An event on a day, with no time. */
    data class OnDay(val day: LocalDate) : Resolved
    /** A timer's length. */
    data class Lasting(val length: Duration) : Resolved
    /** A repeating reminder: its first time, and how it repeats. */
    data class Every(val first: LocalDateTime, val repeat: Repeat) : Resolved
    /**
     * Ask first, saying [reason]. For an alarm on a day other than its next ring: [ring], the
     * time the Clock app can set, and [wanted], when it was asked for (a reminder could do that).
     */
    data class Ask(val reason: String, val ring: LocalDateTime? = null, val wanted: LocalDateTime? = null) : Resolved
}

/**
 * Turns the computer's time structure ([TimeExpr]) into a date, time or length by Ferb's own rules,
 * the same as Phone mode's text reading: When's weekday, part-of-day, date and event-clock rules,
 * TimeOfDay's alarm hours, ReminderTime's defaults. The model does no arithmetic, so "kal" can't
 * become a Thursday. Deterministic, against [now] in its own zone ("in 2 hours" across a clock
 * change is two real hours).
 */
object Resolve {
    enum class For { Alarm, Timer, Event, Reminder }

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)

    fun resolve(expr: TimeExpr, tool: For, now: ZonedDateTime): Resolved {
        val local = now.toLocalDateTime().withSecond(0).withNano(0)
        val today = local.toLocalDate()
        return when (tool) {
            For.Timer -> when (expr) {
                is TimeExpr.Lasting -> Resolved.Lasting(expr.length.total)
                is TimeExpr.In -> Resolved.Lasting(expr.length.total)
                else -> Resolved.Ask("a timer needs a length")
            }
            For.Alarm -> when (expr) {
                is TimeExpr.In, is TimeExpr.Lasting -> Resolved.At(later(now, expr))
                is TimeExpr.Every -> Resolved.Ask("repeating alarms aren't supported yet")
                is TimeExpr.At -> alarm(expr, local)
            }
            For.Event -> when (expr) {
                is TimeExpr.In, is TimeExpr.Lasting -> Resolved.At(later(now, expr))
                is TimeExpr.Every -> Resolved.Ask("repeating events aren't supported")
                is TimeExpr.At -> event(expr, local)
            }
            For.Reminder -> when (expr) {
                is TimeExpr.In, is TimeExpr.Lasting -> Resolved.At(later(now, expr))
                is TimeExpr.Every -> every(expr, local)
                is TimeExpr.At -> reminder(expr, local)
            }
        }.let { if (it is Resolved.At && expr is TimeExpr.At && weekendOnly(expr)) Resolved.Ask("which day this weekend?") else it }
    }

    /** "this weekend" said with no time: ask (the user's decision). */
    private fun weekendOnly(expr: TimeExpr.At) = "weekend" in expr.source.lowercase() && expr.time?.hour == null

    private fun later(now: ZonedDateTime, expr: TimeExpr): LocalDateTime {
        val length = when (expr) { is TimeExpr.In -> expr.length; is TimeExpr.Lasting -> expr.length; else -> error("no length") }
        return now.plus(length.total).toLocalDateTime().withSecond(0).withNano(0)
    }

    private fun day(day: TimeExpr.Day, today: LocalDate): LocalDate? = when (day) {
        is TimeExpr.Day.Relative -> today.plusDays(day.days.toLong())
        is TimeExpr.Day.Weekday -> When.weekday(today, day.weekday, day.next)
        is TimeExpr.Day.Date -> if (day.month == null) When.nth(day.day, today, ahead = true)
            else When.monthDay(day.day, day.month, day.year, today, ahead = true)
    }

    /** An hour above 12 is the 24-hour clock, whatever am or pm says. */
    private fun meridiem(c: TimeExpr.Clock) = c.meridiem?.takeIf { (c.hour ?: 0) in 0..12 }

    /** Events and reminders: When's clock (1–6 pm, 7–11 am unless a part of the day or am/pm says), or the part's own time. */
    private fun eventTime(c: TimeExpr.Clock?): LocalTime? = when {
        c == null -> null
        c.hour != null -> When.clock(c.hour, c.minute ?: 0, meridiem(c), if (meridiem(c) != null) null else c.part)
        else -> c.part?.let { When.partTime(it) }
    }

    private fun bad(c: TimeExpr.Clock?) = c?.hour != null && eventTime(c) == null

    private fun alarm(expr: TimeExpr.At, now: LocalDateTime): Resolved {
        val c = expr.time ?: return Resolved.Ask("an alarm needs a time")
        val (hour, minute) = c.hour?.let { TimeOfDay.of(it, c.minute ?: 0, meridiem(c), c.part) }
            ?: c.part?.let { p -> When.partTime(p)?.let { it.hour to it.minute } }
            ?: return Resolved.Ask("isn't a time of day")
        val time = LocalTime.of(hour, minute)
        // The Clock app rings at the next time it's that time of day; it can't take a date.
        val ring = (if (time.isAfter(now.toLocalTime())) now.toLocalDate() else now.toLocalDate().plusDays(1)).atTime(time)
        val wanted = expr.day?.let { day(it, now.toLocalDate()) ?: return Resolved.Ask("isn't a date") } ?: return Resolved.At(ring)
        if (wanted == ring.toLocalDate()) return Resolved.At(ring)
        return Resolved.Ask("the Clock app can only set the next ${ring.format(CLOCK)} (${ring.format(DAY)})", ring, wanted.atTime(time))
    }

    private fun event(expr: TimeExpr.At, now: LocalDateTime): Resolved {
        if (bad(expr.time)) return Resolved.Ask("isn't a time of day")
        val day = expr.day?.let { day(it, now.toLocalDate()) ?: return Resolved.Ask("isn't a date") }
        val time = eventTime(expr.time)
        return when {
            day == null && time == null -> Resolved.Ask("when?")
            time == null -> if (weekendOnly(expr)) Resolved.Ask("which day this weekend?") else Resolved.OnDay(day!!)
            // A time without a day: the next time it comes, as AddEvent.plan does.
            else -> Resolved.At((day ?: if (time.isBefore(now.toLocalTime())) now.toLocalDate().plusDays(1) else now.toLocalDate()).atTime(time))
        }
    }

    /** Reminders: a day alone at 09:00, a time alone the next time it comes (ReminderTime's rules). */
    private fun reminder(expr: TimeExpr.At, now: LocalDateTime): Resolved {
        if (bad(expr.time)) return Resolved.Ask("isn't a time of day")
        val day = expr.day?.let { day(it, now.toLocalDate()) ?: return Resolved.Ask("isn't a date") }
        val time = eventTime(expr.time)
        if (day == null && time == null) return Resolved.Ask("when?")
        if (time == null && weekendOnly(expr)) return Resolved.Ask("which day this weekend?")
        val at = time ?: ReminderTime.DEFAULT_TIME
        return Resolved.At((day ?: next(at, now)).atTime(at))
    }

    private fun every(expr: TimeExpr.Every, now: LocalDateTime): Resolved {
        if (bad(expr.time)) return Resolved.Ask("isn't a time of day")
        val at = eventTime(expr.time) ?: ReminderTime.DEFAULT_TIME
        return when (expr.period) {
            "day" -> Resolved.Every(next(at, now).atTime(at), Repeat.Daily)
            "week" -> when (expr.on.size) {
                0 -> Resolved.Every(next(at, now).atTime(at), Repeat.Weekly)
                1 -> {
                    var first = When.weekday(now.toLocalDate(), expr.on.single(), next = false).atTime(at)
                    if (!first.isAfter(now)) first = first.plusWeeks(1)
                    Resolved.Every(first, Repeat.Weekly)
                }
                else -> Resolved.Ask("reminders can't repeat on several weekdays yet")
            }
            "weekdays" -> Resolved.Ask("reminders can't repeat on weekdays only yet")
            "month" -> Resolved.Every(next(at, now).atTime(at), Repeat.Monthly)
            "year" -> Resolved.Every(next(at, now).atTime(at), Repeat.Yearly)
            else -> Resolved.Ask("how often?")
        }
    }

    /** Today if [time] is still ahead, else tomorrow. */
    private fun next(time: LocalTime, now: LocalDateTime): LocalDate =
        if (time.isAfter(now.toLocalTime())) now.toLocalDate() else now.toLocalDate().plusDays(1)
}
