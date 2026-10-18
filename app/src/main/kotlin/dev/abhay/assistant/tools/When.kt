package dev.abhay.assistant.tools

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.MonthDay
import java.time.temporal.TemporalAdjusters

/**
 * Days and times as requests say them ("tomorrow at 5", "next Friday evening", "12 Oct from 2 to
 * 3:30 pm"), read by the app rather than by Needle 3, which doesn't know today's date and isn't
 * trusted with date arithmetic (as with timer lengths). Phrases are found anywhere in the text, so
 * a whole request can be read, and [strip] takes them out of an event's title.
 */
object When {
    /** What a text says about when: a [day] and a start [time], an [end] or a [length] in minutes; null where it doesn't say. */
    data class Said(val day: LocalDate?, val time: LocalTime?, val end: LocalTime? = null, val length: Int? = null, val allDay: Boolean = false) {
        val isEmpty get() = day == null && time == null && !allDay
    }

    /** The day [text] names, if it names one; without a year, the one nearest [today] (an agenda can look back). */
    fun day(text: String, today: LocalDate): LocalDate? = findDay(normalise(text), today, ahead = false)?.first

    /** Everything [text] says about when, for an event: days without a year are the next one from [now]. */
    fun read(text: String, now: LocalDateTime): Said {
        val t = normalise(text)
        val day = findDay(t, now.toLocalDate(), ahead = true)?.first
        val part = PART_OF_DAY.find(t)?.groupValues?.get(1)
        val range = findRange(t, part)
        val time = range?.first ?: findTime(t, part)?.first ?: part?.let { PARTS.getValue(it) }
        val length = LENGTH.find(t)?.let { Durations.parse(it.groupValues[1])?.div(60) }?.takeIf { it > 0 }
        return Said(day, time, range?.second, length, allDay = ALL_DAY.containsMatchIn(t))
    }

    /** [text] without its day and time phrases, e.g. a title "Dentist tomorrow at 5" → "Dentist". */
    fun strip(text: String): String {
        val original = text.replace(Regex("""\s+"""), " ").trim()
        // Found in a lower-case copy, cut from the original, which keeps its capitals.
        val lower = original.lowercase().takeIf { it.length == original.length } ?: return original
        val spans = STRIPPED.flatMap { it.findAll(sameLength(lower)).map { m -> m.range } }.sortedBy { it.first }
        val kept = StringBuilder()
        var at = 0
        for (span in spans) {
            if (span.first >= at) kept.append(original, at, span.first).append(' ')
            at = maxOf(at, span.last + 1)
        }
        kept.append(original, at, original.length)
        return kept.toString().replace(Regex("""\s+"""), " ").trim()
            .replace(Regex("""(?i)\s+(on|at|for|from|by)$"""), "").replace(Regex("""(?i)^(on|at|for|from)\s+"""), "").trim()
    }

    // --- days ---

    private val WEEKDAYS = mapOf(
        "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY,
    )
    // Whole names, and abbreviations that aren't words of their own ("sat", "wed" and "sun" are).
    private const val WEEKDAY = """(monday|mon|tuesday|tues|tue|wednesday|thursday|thurs|thu|friday|fri|saturday|sunday)"""
    private const val MONTH = """(january|jan|february|feb|march|mar|april|apr|may|june|jun|july|jul|august|aug|september|sept|sep|october|oct|november|nov|december|dec)"""
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private const val ORDINAL = """(\d{1,2})(?:st|nd|rd|th)?"""

    private val RELATIVE = Regex("""\b(day after tomorrow|today|tonight|tomorrow|yesterday)\b""")
    private val IN_DAYS = Regex("""\bin (\d+|a) (day|week)s?\b""")
    private val NAMED_WEEKDAY = Regex("""\b(?:(this|next|coming) )?$WEEKDAY\b""")
    private val DAY_MONTH = Regex("""\b$ORDINAL(?: of)? $MONTH(?: (\d{4}))?\b""")
    private val MONTH_DAY = Regex("""\b$MONTH $ORDINAL(?:,? (\d{4}))?\b""")
    private val NUMERIC = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b|\b(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?\b""")
    private val THE_NTH = Regex("""\b(?:on )?the $ORDINAL\b""")

    /**
     * The first day [t] names and where. Weekdays are the coming one ("Friday", "this Friday" and
     * "next Friday" alike, the way most people mean them), today's being today, except "next"
     * on that very day, which is a week on. Slashed dates are day first, as in India.
     */
    private fun findDay(t: String, today: LocalDate, ahead: Boolean): Pair<LocalDate, IntRange>? {
        val found = mutableListOf<Pair<LocalDate, IntRange>>()
        RELATIVE.find(t)?.let { m ->
            val day = when (m.groupValues[1]) {
                "day after tomorrow" -> today.plusDays(2)
                "tomorrow" -> today.plusDays(1)
                "yesterday" -> today.minusDays(1)
                else -> today
            }
            found += day to m.range
        }
        IN_DAYS.find(t)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: 1
            found += today.plusDays(if (m.groupValues[2] == "week") 7L * n else n.toLong()) to m.range
        }
        NAMED_WEEKDAY.find(t)?.let { m ->
            found += weekday(today, WEEKDAYS.getValue(m.groupValues[2].take(3)), next = m.groupValues[1] == "next") to m.range
        }
        DAY_MONTH.find(t)?.let { m -> dated(m.groupValues[1], m.groupValues[2], m.groupValues[3], today, ahead)?.let { found += it to m.range } }
        MONTH_DAY.find(t)?.let { m -> dated(m.groupValues[2], m.groupValues[1], m.groupValues[3], today, ahead)?.let { found += it to m.range } }
        NUMERIC.find(t)?.let { m ->
            val g = m.groupValues
            val day = if (g[1].isNotEmpty()) runCatching { LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt()) }.getOrNull()
            else monthDay(g[4].toInt(), g[5].toInt(), g[6].ifEmpty { null }?.toInt(), today, ahead)
            day?.let { found += it to m.range }
        }
        if (found.isEmpty()) THE_NTH.find(t)?.let { m ->
            nth(m.groupValues[1].toInt(), today, ahead)?.let { found += it to m.range }
        }
        return found.minByOrNull { it.second.first }
    }

    private fun dated(day: String, month: String, year: String, today: LocalDate, ahead: Boolean): LocalDate? =
        monthDay(day.toInt(), MONTHS.indexOf(month.take(3)) + 1, year.ifEmpty { null }?.toInt(), today, ahead)

    // --- the rules, shared with [Resolve] (the computer's time structure) ---

    /** [weekday] from [today]: today itself if it's that day ("Friday" on a Friday), a week on for "next" said on that day, else the coming one. */
    fun weekday(today: LocalDate, weekday: DayOfWeek, next: Boolean): LocalDate {
        val coming = today.with(TemporalAdjusters.nextOrSame(weekday))
        return if (next && coming == today) coming.plusWeeks(1) else coming
    }

    /** "the 12th": this month's, or next month's if it has passed and [ahead]. */
    fun nth(n: Int, today: LocalDate, ahead: Boolean): LocalDate? {
        val month = if (ahead && n < today.dayOfMonth) today.plusMonths(1) else today
        return runCatching { month.withDayOfMonth(n) }.getOrNull()
    }

    /** The time a part of the day means when no time is said ("tomorrow morning" is 9:00); null for an unknown part. */
    fun partTime(part: String): LocalTime? = PARTS[part]

    /** A day of a month; without a [year], the next one from [today] if [ahead], else the nearest. */
    fun monthDay(day: Int, month: Int, year: Int?, today: LocalDate, ahead: Boolean): LocalDate? {
        val md = runCatching { MonthDay.of(month, day) }.getOrNull() ?: return null
        if (year != null) {
            val y = if (year < 100) 2000 + year else year
            return runCatching { md.atYear(y) }.getOrNull()
        }
        val candidates = listOf(today.year - 1, today.year, today.year + 1).filter { md.isValidYear(it) }.map { md.atYear(it) }
        return if (ahead) candidates.firstOrNull { !it.isBefore(today) }
        else candidates.minByOrNull { kotlin.math.abs(it.toEpochDay() - today.toEpochDay()) }
    }

    // --- times ---

    /** Parts of the day, and the time each means when no time is said ("tomorrow morning" is 9:00). */
    private val PARTS = mapOf(
        "morning" to LocalTime.of(9, 0), "noon" to LocalTime.NOON, "lunch" to LocalTime.of(13, 0), "afternoon" to LocalTime.of(14, 0),
        "evening" to LocalTime.of(18, 0), "dinner" to LocalTime.of(20, 0), "tonight" to LocalTime.of(20, 0), "night" to LocalTime.of(20, 0),
        "breakfast" to LocalTime.of(8, 0),
    )
    private val PART_OF_DAY = Regex("""\b(morning|breakfast|noon|lunch|afternoon|evening|dinner|tonight|night)\b""")
    private val LATER_PARTS = setOf("afternoon", "evening", "dinner", "tonight", "night")
    private val ALL_DAY = Regex("""\ball[- ]day\b|\bwhole day\b""")

    private const val CLOCK = """(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm)?"""
    /** A time: "at 5", "5pm", "5:30", "17:30", "noon"; a bare number needs "at" (or the "from" of a range). */
    private val TIME = Regex("""\b(?:(at|by) )?$CLOCK\b|\b(noon|midnight)\b""")
    private val RANGE = Regex("""\b(?:from |between )?$CLOCK ?(?:-|–|to|till|until|and) ?$CLOCK\b""")
    private val LENGTH = Regex("""\bfor ((?:an?|\d+(?:\.\d+)?|half an?) ?(?:hours?|hrs?|h|minutes?|mins?|m)(?: (?:and )?\d+ ?(?:minutes?|mins?|m))?)\b""")

    /** The first time [t] says, and where; [part] ("evening") decides am or pm when it isn't said. */
    private fun findTime(t: String, part: String?): Pair<LocalTime, IntRange>? {
        for (m in TIME.findAll(t)) {
            val g = m.groupValues
            if (g[5].isNotEmpty()) return (if (g[5] == "noon") LocalTime.NOON else LocalTime.MIDNIGHT) to m.range
            val hasMinutes = g[3].isNotEmpty()
            // "5" alone could be anything ("5 people"); "at 5", "5pm" and "5:30" are times.
            if (g[1].isEmpty() && g[4].isEmpty() && !hasMinutes) continue
            // Not part of a date ("12/10") or a length ("for 2 hours").
            val after = t.substring(m.range.last + 1).trimStart()
            if (after.startsWith("/") || Regex("""^(hours?|hrs?|minutes?|mins?|days?|weeks?|people|st|nd|rd|th)\b""").containsMatchIn(after)) continue
            if (Regex("""(\d/|-)$""").containsMatchIn(t.substring(0, m.range.first))) continue
            clock(g[2], g[3], g[4], part)?.let { return it to m.range }
        }
        return null
    }

    /** "from 2 to 3:30 pm", "5-6pm", "between 10 and 11": start and end; am or pm said once applies to both. */
    private fun findRange(t: String, part: String?): Pair<LocalTime, LocalTime>? = RANGE.findAll(t).firstNotNullOfOrNull { range(it, part) }

    private fun range(m: MatchResult, part: String?): Pair<LocalTime, LocalTime>? {
        val g = m.groupValues
        // Two plain numbers ("2 to 3 people") need "from", "between", a colon or am/pm.
        if (!Regex("""^(from|between)""").containsMatchIn(m.value) && (g[2] + g[3] + g[5] + g[6]).isEmpty()) return null
        val endPeriod = g[6].ifEmpty { null }
        val end = clock(g[4], g[5], g[6], part) ?: return null
        var start = clock(g[1], g[2], g[3].ifEmpty { endPeriod ?: "" }, part) ?: return null
        // "11 to 1 pm": the start is the morning one.
        if (g[3].isEmpty() && start.isAfter(end)) start = clock(g[1], g[2], "am", part) ?: start
        // "10 pm to 1 am" runs past midnight; otherwise an end before the start isn't a range.
        if (!start.isBefore(end) && (g[3].isEmpty() || g[6].isEmpty())) return null
        return start to end
    }

    /**
     * An hour, minutes and am/pm as a time. Without am or pm, [part] decides ("6 in the morning"),
     * else 1–6 is the afternoon or evening (few events start at 3 am) and 7–11 the morning.
     */
    private fun clock(hour: String, minute: String, period: String, part: String?): LocalTime? =
        clock(hour.toIntOrNull() ?: return null, minute.ifEmpty { "0" }.toInt(), period.ifEmpty { null }, part)

    /** [clock]'s rule for an event's hour: am or pm as said, else [part] decides, else 1–6 are pm and 7–11 am. */
    fun clock(hour: Int, minute: Int, meridiem: String?, part: String?): LocalTime? {
        var h = hour
        val min = minute
        val period = meridiem.orEmpty()
        if (min !in 0..59) return null
        when {
            period == "am" -> { if (h !in 1..12) return null; if (h == 12) h = 0 }
            period == "pm" -> { if (h !in 1..12) return null; if (h != 12) h += 12 }
            h > 23 -> return null
            h in 1..11 && part in LATER_PARTS -> h += 12
            h in 1..11 && (part == "morning" || part == "breakfast") -> {}
            h in 1..6 -> h += 12
        }
        return LocalTime.of(h, min)
    }

    // --- helpers ---

    /** Lower case, "a.m." as "am", single spaces. Numbers in words aren't read yet ("at five"). */
    private fun normalise(text: String) = sameLength(text.lowercase()).replace(Regex("""\s+"""), " ")

    /** "a.m." as "am  ": the same length, so positions found in it are the original's. */
    private fun sameLength(lower: String) = lower.replace(Regex("""\ba\.m\."""), "am  ").replace(Regex("""\bp\.m\."""), "pm  ")

    /** What [strip] takes out: day and time phrases, with the little words that lead into them. */
    private val STRIPPED = listOf(
        Regex("""\b(?:on |for |at |by )?(?:the )?(?:day after tomorrow|today|tonight|tomorrow)(?: (?:morning|afternoon|evening|night))?\b"""),
        Regex("""\b(?:on |for )?(?:this |next |coming )?$WEEKDAY(?: (?:morning|afternoon|evening|night))?\b"""),
        Regex("""\b(?:on |for )?(?:the )?$ORDINAL(?: of)? $MONTH(?: \d{4})?\b"""),
        Regex("""\b(?:on |for )?$MONTH $ORDINAL(?:,? \d{4})?\b"""),
        Regex("""\b(?:on )?(?:\d{4}-\d{2}-\d{2}|\d{1,2}/\d{1,2}(?:/\d{2,4})?)\b"""),
        Regex("""\b(?:from |between )?$CLOCK ?(?:-|–|to|till|until) ?$CLOCK\b"""),
        Regex("""\b(?:at|by) $CLOCK\b"""),
        Regex("""\b\d{1,2}(?:[:.]\d{2})? ?(?:am|pm)\b|\b\d{1,2}[:.]\d{2}\b"""),
        Regex("""\b(?:at )?(?:noon|midnight)\b"""),
        Regex("""\b(?:in the |this )?(?:morning|afternoon|evening)\b"""),
        Regex("""\bin (?:\d+|a) (?:day|week)s?\b"""),
        Regex("""\b(?:for )?(?:the )?(?:all[- ]day|whole day)\b"""),
        Regex("""\bfor (?:an?|\d+(?:\.\d+)?|half an?) ?(?:hours?|hrs?|minutes?|mins?)\b"""),
    )
}
