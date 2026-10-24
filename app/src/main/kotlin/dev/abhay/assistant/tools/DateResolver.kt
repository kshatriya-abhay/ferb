package dev.abhay.assistant.tools

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * When a request means, worked out by Ferb from its words and the phone's clock (approach A of the
 * date and time work, 2026-10-24): no model, the rules in tool-check/datetime.txt's header. English,
 * and Hindi or Hinglish day and time words (kal, parso, agle shanivaar, subah saat baje, shaam 6
 * baje). [resolve] gives a moment (with a repeat), a day (the agenda), or [Resolved.Ask] when Ferb
 * should ask rather than guess. Needle 3's own reading of a date isn't used: it doesn't know the date.
 */
object DateResolver {
    enum class Kind { Reminder, Alarm, Event, Agenda }

    enum class Rule(val key: String) { Daily("daily"), Weekdays("weekdays"), Weekly("weekly"), Monthly("monthly") }

    sealed interface Resolved {
        data class At(val at: LocalDateTime, val rule: Rule? = null) : Resolved
        data class Day(val day: LocalDate) : Resolved
        data class Ask(val why: String) : Resolved
    }

    /** The parts of the day without a time, and what an hour said with them means. */
    private enum class Part(val default: LocalTime, val pm: Boolean?) {
        Morning(LocalTime.of(9, 0), false), Afternoon(LocalTime.of(14, 0), true), Evening(LocalTime.of(18, 0), true), Night(LocalTime.of(20, 0), null)
    }

    private val DEFAULT_TIME: LocalTime = LocalTime.of(9, 0)

    fun resolve(text: String, kind: Kind, now: LocalDateTime): Resolved {
        val t = normalise(text)
        val today = now.toLocalDate()
        val rule = rule(t)

        // "in 20 minutes", "in 2 hours", "in half an hour": exactly from now. "in 3 days": that day.
        inLength(t)?.let { (seconds, days) ->
            if (days == null) return Resolved.At(now.plusSeconds(seconds.toLong()).withSecond(0).withNano(0), rule)
            val time = time(t)
            return Resolved.At(today.plusDays(days.toLong()).atTime(time?.let { it.at ?: it.part?.default } ?: DEFAULT_TIME), rule)
        }

        // A weekend, or two weekdays ("Monday and Wednesday"): which day isn't one Ferb can pick.
        if (kind != Kind.Agenda) {
            if (Regex("""\bweekends?\b""").containsMatchIn(t)) return Resolved.Ask("Saturday or Sunday?")
            if (WEEKDAY.findAll(t).map { it.value }.toSet().size > 1) return Resolved.Ask("Which day?")
        }

        val day = day(t, today)
        // A date said that doesn't exist (2026-02-30, 31/11): ask, never another day.
        if (day?.noSuchDate == true) return Resolved.Ask("That date doesn't exist")
        // Waking words make a bare hour the morning's ("wake me up at 8"; v2 of the set, 2026-10-24).
        val said = time(t)?.let { s -> if (s.bare && s.at != null && WAKE.containsMatchIn(t)) SaidTime(LocalTime.of(s.at.hour % 12, s.at.minute), false, s.part, s.midnight, s.thisPart) else s }

        if (kind == Kind.Agenda) {
            return Resolved.Day(day?.date ?: today)
        }

        // A repeat: its first time after now.
        if (rule != null) {
            val at = said?.let { clock(it, day != null && day.date != today) } ?: DEFAULT_TIME
            var first = (day?.date ?: today).atTime(at)
            fun fits(d: LocalDate) = when (rule) {
                Rule.Weekdays -> d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY
                Rule.Weekly -> day?.weekday == null || d.dayOfWeek == day.weekday
                else -> true
            }
            while (!first.isAfter(now) || !fits(first.toLocalDate())) first = first.plusDays(1)
            return Resolved.At(first, rule)
        }

        if (said == null && day == null) return Resolved.Ask(if (kind == Kind.Alarm) "When should the alarm ring?" else "When? Say a day or a time")
        // An alarm needs a clock time: "morning" alone isn't one ("my alarm didn't go off this morning").
        if (kind == Kind.Alarm && said?.at == null) return Resolved.Ask("What time should the alarm ring?")

        // A weekday said on that weekday: today while its time is ahead, else a week on ("this" and
        // bare ones; "next" is a week on already).
        if (day != null) {
            // A bare hour today: whichever half of the clock is still ahead ("at 7 today" at 8 am is 7 pm).
            if (said != null && said.bare && day.date == today && day.later == null) {
                val ahead = candidates(said).map { today.atTime(it) }.filter { it.isAfter(now) }.minOrNull()
                return ahead?.let { Resolved.At(it) } ?: Resolved.Ask("That time today has passed")
            }
            val time = said?.let { clock(it, day.date != today || day.later != null) } ?: day.tonight.takeIf { it }?.let { Part.Night.default }
            if (time == null) {
                // No time: a later day at 09:00; today, ask.
                if (day.date == today && day.later == null) return Resolved.Ask("What time today?")
                val at = day.date.atTime(DEFAULT_TIME)
                return Resolved.At(if (day.later != null && !at.isAfter(now)) day.later.atTime(DEFAULT_TIME) else at)
            }
            var at = day.date.atTime(time)
            if (day.later != null && !at.isAfter(now)) at = day.later.atTime(time)
            // "midnight" on a day is the start of the next one.
            if (said?.midnight == true) at = day.date.plusDays(1).atStartOfDay()
            if (!at.isAfter(now)) return Resolved.Ask(if (day.tonight) "It's past tonight's time" else "That time today has passed")
            return Resolved.At(at)
        }

        // A time without a day: the next time it comes ("at 6" at 8 pm is 6 tomorrow morning).
        val s = said!!
        if (s.midnight) return Resolved.At(today.plusDays(1).atStartOfDay())
        if (s.thisPart && s.at != null) {
            val at = today.atTime(clock(s, false))
            return if (at.isAfter(now)) Resolved.At(at) else Resolved.Ask("That time today has passed")
        }
        val candidates = candidates(s).map { today.atTime(it) }.flatMap { listOf(it, it.plusDays(1)) }.filter { it.isAfter(now) }
        return candidates.minOrNull()?.let { Resolved.At(it) } ?: Resolved.Ask("When?")
    }

    // --- words ---

    private val HINDI = listOf(
        Regex("""\bagl[eia]\s+""") to "next ",
        Regex("""\bis\s+(?=(mon|tues|wednes|thurs|fri|satur|sun)day)""") to "this ",
        Regex("""\bsom\s?vaa?r\b""") to "monday", Regex("""\bmangal\s?vaa?r\b""") to "tuesday", Regex("""\bbudh\s?vaa?r\b""") to "wednesday",
        Regex("""\b(guru|brihaspati)\s?vaa?r\b""") to "thursday", Regex("""\bshukra\s?vaa?r\b""") to "friday",
        Regex("""\bshani\s?vaa?r\b""") to "saturday", Regex("""\b(ravi|it)\s?vaa?r\b""") to "sunday",
        Regex("""\bparso[n]?\b""") to "day after tomorrow", Regex("""\bkal\b""") to "tomorrow", Regex("""\baaj\b""") to "today",
        Regex("""\bsubah|\bsubha\b""") to "morning", Regex("""\bdopahar\b""") to "afternoon", Regex("""\bshaam|\bsham\b""") to "evening",
        Regex("""\braat\b""") to "night",
        Regex("""\bhar (?=(mon|tues|wednes|thurs|fri|satur|sun)day)""") to "every ",
    )
    private val HINDI_NUMBERS = mapOf(
        "ek" to 1, "do" to 2, "teen" to 3, "chaar" to 4, "char" to 4, "paanch" to 5, "panch" to 5, "chhe" to 6, "chhah" to 6, "che" to 6,
        "saat" to 7, "aath" to 8, "nau" to 9, "das" to 10, "gyarah" to 11, "barah" to 12,
    )
    private val ENGLISH_NUMBERS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12,
    )

    /** Lower case, Hindi day and time words in English, "saade paanch baje" as "5:30". */
    internal fun normalise(text: String): String {
        var t = " " + text.lowercase().replace(Regex("""[,!?]"""), " ") + " "
        // Hindi amounts with an hour: saade (+30), sava (+15), paune (-15).
        val hours = (HINDI_NUMBERS.keys + ENGLISH_NUMBERS.keys).joinToString("|") + """|\d{1,2}"""
        fun hour(w: String) = w.toIntOrNull() ?: HINDI_NUMBERS[w] ?: ENGLISH_NUMBERS.getValue(w)
        t = t.replace(Regex("""\bsaa?de\s+($hours)\b""")) { "${hour(it.groupValues[1])}:30" }
            .replace(Regex("""\bsava\s+($hours)\b""")) { "${hour(it.groupValues[1])}:15" }
            .replace(Regex("""\bpaune\s+($hours)\b""")) { val h = hour(it.groupValues[1]) - 1; "${if (h == 0) 12 else h}:45" }
        // "saat baje": the number words with baje.
        t = t.replace(Regex("""\b(${HINDI_NUMBERS.keys.joinToString("|")})\s+baje\b""")) { "${HINDI_NUMBERS.getValue(it.groupValues[1])} baje" }
        // Lengths: "10 minute mein", "do ghante mein", "3 din mein" as "in 10 minutes".
        t = t.replace(Regex("""\b(\d+|${HINDI_NUMBERS.keys.joinToString("|")}|aadhe) (minute|minat|ghante|ghanta|din) (?:mein|me|main)\b""")) {
            val n = it.groupValues[1].let { w -> if (w == "aadhe") "half an" else (w.toIntOrNull() ?: HINDI_NUMBERS.getValue(w)).toString() }
            val unit = when (it.groupValues[2]) { "din" -> "days"; "ghante", "ghanta" -> "hours"; else -> "minutes" }
            "in $n ${if (n == "half an") unit.dropLast(1) else unit}"
        }
        for ((pattern, english) in HINDI) t = t.replace(pattern, english)
        return t.replace(Regex("""\s+"""), " ").trim()
    }

    // --- repeats ---

    private fun rule(t: String): Rule? = when {
        Regex("""\b(every weekday|weekdays|every working day)\b""").containsMatchIn(t) -> Rule.Weekdays
        Regex("""\b(every day|daily|everyday|har roz|roz|every (morning|afternoon|evening|night))\b""").containsMatchIn(t) -> Rule.Daily
        Regex("""\b(every (monday|tuesday|wednesday|thursday|friday|saturday|sunday)|every week|weekly)\b""").containsMatchIn(t) -> Rule.Weekly
        Regex("""\b(every month|monthly)\b""").containsMatchIn(t) -> Rule.Monthly
        else -> null
    }

    // --- lengths ---

    /** "in N minutes/hours" (seconds, null) or "in N days/weeks" (0, days). */
    private fun inLength(t: String): Pair<Int, Int?>? {
        Regex("""\bin (\d+|a|an|one|two|three|four|five|six|seven) (day|week)s?\b""").find(t)?.let { m ->
            val n = m.groupValues[1].let { it.toIntOrNull() ?: ENGLISH_NUMBERS[it] ?: 1 }
            return 0 to n * if (m.groupValues[2] == "week") 7 else 1
        }
        val m = Regex("""\bin ((?:a quarter of an|half an|an|a|\d+(?:\.\d+)?|[a-z]+)(?: and a half)? (?:hour|minute|min|second)s?(?: and a half)?(?:,? (?:and )?\d+ (?:minute|min)s?)?)""").find(t) ?: return null
        val phrase = m.groupValues[1].replace("a quarter of an hour", "15 minutes")
        val seconds = Durations.parse(phrase) ?: return null
        return seconds to null
    }

    // --- days ---

    /**
     * A day said: [date], and [later], the day it means instead when it's today and its time has
     * passed ("Saturday" on a Saturday afternoon is next week's; "the 30th" on the 30th, next month's).
     */
    private class SaidDay(val date: LocalDate, val weekday: DayOfWeek? = null, val later: LocalDate? = null, val tonight: Boolean = false, val noSuchDate: Boolean = false)

    private val WEEKDAYS = mapOf(
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY, "thursday" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY, "sunday" to DayOfWeek.SUNDAY,
    )
    private val WAKE = Regex("""\b(wake|waking|get up|getting up|utha|uthana|utha dena|jaga|jagana|jaga dena)\b""")
    private val WEEKDAY = Regex("""\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private const val MONTH = """(january|jan|february|feb|march|mar|april|apr|may|june|jun|july|jul|august|aug|september|sept|sep|october|oct|november|nov|december|dec)"""

    private fun day(t: String, today: LocalDate): SaidDay? {
        if (Regex("""\bday after tomorrow\b""").containsMatchIn(t)) return SaidDay(today.plusDays(2))
        if (Regex("""\btomorrow\b""").containsMatchIn(t)) return SaidDay(today.plusDays(1))
        if (Regex("""\btonight\b""").containsMatchIn(t)) return SaidDay(today, tonight = true)
        if (Regex("""\btoday\b""").containsMatchIn(t)) return SaidDay(today)
        // A date said wins over a weekday with it ("on Saturday 31 October", as a rewrite with the
        // calendar says it): "Nov 5", "5 November", "5/11" (day/month), "2026-10-31".
        Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""").find(t)?.let { m ->
            // A date that doesn't exist (2026-02-30) is no day, as for the other forms.
            return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
                ?.let { SaidDay(it) } ?: SaidDay(today, noSuchDate = true)
        }
        fun ahead(month: Int, dayOfMonth: Int): LocalDate? {
            val thisYear = runCatching { LocalDate.of(today.year, month, dayOfMonth) }.getOrNull() ?: return null
            return if (thisYear.isBefore(today)) thisYear.plusYears(1) else thisYear
        }
        Regex("""\b$MONTH (\d{1,2})(?:st|nd|rd|th)?\b""").find(t)?.let { m ->
            return ahead(MONTHS.indexOf(m.groupValues[1].take(3)) + 1, m.groupValues[2].toInt())?.let { SaidDay(it) } ?: SaidDay(today, noSuchDate = true)
        }
        Regex("""\b(\d{1,2})(?:st|nd|rd|th)?(?: of)? $MONTH\b""").find(t)?.let { m ->
            return ahead(MONTHS.indexOf(m.groupValues[2].take(3)) + 1, m.groupValues[1].toInt())?.let { SaidDay(it) } ?: SaidDay(today, noSuchDate = true)
        }
        Regex("""\b(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?\b""").find(t)?.let { m ->
            val d = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
            val date = if (y != null) runCatching { LocalDate.of(y, mo, d) }.getOrNull() else ahead(mo, d)
            return date?.let { SaidDay(it) } ?: SaidDay(today, noSuchDate = true)
        }
        Regex("""\b(?:(this|next|coming) )?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""").find(t)?.let { m ->
            if (Regex("""\bevery ${m.groupValues[2]}""").containsMatchIn(t)) return SaidDay(today.with(TemporalAdjusters.nextOrSame(WEEKDAYS.getValue(m.groupValues[2]))), WEEKDAYS.getValue(m.groupValues[2]))
            val weekday = WEEKDAYS.getValue(m.groupValues[2])
            return if (m.groupValues[1] == "next") SaidDay(today.with(TemporalAdjusters.next(weekday)), weekday)
            else {
                val coming = today.with(TemporalAdjusters.nextOrSame(weekday))
                SaidDay(coming, weekday, later = coming.plusWeeks(1).takeIf { coming == today })
            }
        }
        Regex("""\bthe (\d{1,2})(?:st|nd|rd|th)\b""").find(t)?.let { m ->
            val d = m.groupValues[1].toInt()
            var date = runCatching { today.withDayOfMonth(d) }.getOrNull()
            if (date == null || date.isBefore(today)) date = runCatching { today.plusMonths(1).withDayOfMonth(d) }.getOrNull()
            date?.let { return SaidDay(it, later = runCatching { it.plusMonths(1).withDayOfMonth(d) }.getOrNull().takeIf { _ -> it == today }) }
        }
        return null
    }

    // --- times ---

    /**
     * A time said: [at] the clock time as said (hour 0–23 when am, pm or 24 hours says it; else
     * 1–12 with [bare]), or only a [part] of the day; [midnight] and noon are kept apart.
     */
    private class SaidTime(val at: LocalTime?, val bare: Boolean, val part: Part?, val midnight: Boolean = false, val thisPart: Boolean = false)

    private fun time(t: String): SaidTime? {
        val part = when {
            Regex("""\b(morning)\b""").containsMatchIn(t) -> Part.Morning
            Regex("""\b(afternoon)\b""").containsMatchIn(t) -> Part.Afternoon
            Regex("""\b(evening)\b""").containsMatchIn(t) -> Part.Evening
            Regex("""\b(night|tonight)\b""").containsMatchIn(t) -> Part.Night
            else -> null
        }
        val thisPart = Regex("""\bthis (morning|afternoon|evening)\b""").containsMatchIn(t)
        if (Regex("""\bmidnight\b""").containsMatchIn(t)) return SaidTime(LocalTime.MIDNIGHT, false, null, midnight = true)
        if (Regex("""\bnoon\b""").containsMatchIn(t)) return SaidTime(LocalTime.NOON, false, null)
        fun num(w: String) = w.toIntOrNull() ?: ENGLISH_NUMBERS[w]
        val hourWord = """(\d{1,2}|${ENGLISH_NUMBERS.keys.joinToString("|")})"""
        Regex("""\bhalf past $hourWord\b""").find(t)?.let { m -> num(m.groupValues[1])?.let { return withPart(it, 30, null, part, thisPart) } }
        Regex("""\bquarter past $hourWord\b""").find(t)?.let { m -> num(m.groupValues[1])?.let { return withPart(it, 15, null, part, thisPart) } }
        Regex("""\bquarter to $hourWord\b""").find(t)?.let { m -> num(m.groupValues[1])?.let { return withPart(if (it == 1) 12 else it - 1, 45, null, part, thisPart) } }
        // "7:30 pm", "19:30", "6.45 am", "7 am", "7pm".
        Regex("""\b(\d{1,2})(?:[:.](\d{2}))?\s*(am|a\.m\.|pm|p\.m\.)""").find(t)?.let { m ->
            return withPart(m.groupValues[1].toInt(), m.groupValues[2].ifEmpty { "0" }.toInt(), m.groupValues[3].startsWith("p"), part, thisPart)
        }
        Regex("""\b(\d{1,2})[:.](\d{2})\b""").find(t)?.let { m ->
            val h = m.groupValues[1].toInt()
            return withPart(h, m.groupValues[2].toInt(), if (h > 12) true else null, part, thisPart)
        }
        // "at 7", "7 o'clock", "7 baje", "for 8" (an alarm), "at seven".
        Regex("""\b(?:at|for|by) $hourWord\b(?! (?:minute|min|hour|second|day|week)s?\b)(?![/:.]\d)""").find(t)?.let { m -> num(m.groupValues[1])?.let { return withPart(it, 0, null, part, thisPart) } }
        Regex("""\b$hourWord (?:o'clock|oclock|baje)\b""").find(t)?.let { m -> num(m.groupValues[1])?.let { return withPart(it, 0, null, part, thisPart) } }
        // "tomorrow at 7" said as "7 tomorrow", "alarm at 6": a lone hour next to a day or a part.
        Regex("""\b$hourWord (?:tomorrow|today|in the (?:morning|evening))\b""").find(t)?.let { m -> num(m.groupValues[1])?.let { return withPart(it, 0, null, part, thisPart) } }
        return part?.let { SaidTime(null, false, it, thisPart = thisPart) }
    }

    private fun withPart(hour: Int, minute: Int, pm: Boolean?, part: Part?, thisPart: Boolean): SaidTime? {
        if (hour !in 0..23 || minute !in 0..59) return null
        val isPm = pm ?: part?.let { p -> if (p == Part.Night) !(hour == 12 || hour in 1..4) else p.pm }
        if (isPm == null || hour > 12 || hour == 0) return SaidTime(LocalTime.of(hour % 24, minute), bare = isPm == null && hour in 1..12, part = part, thisPart = thisPart)
        val h = when {
            isPm && hour != 12 -> hour + 12
            !isPm && hour == 12 -> 0
            else -> hour
        }
        return SaidTime(LocalTime.of(h, minute), bare = false, part = part, thisPart = thisPart)
    }

    /** The clock time of [s]: as said; a bare hour on another day 7–11 am, 12 noon, 1–6 pm; a part alone its default. */
    private fun clock(s: SaidTime, otherDay: Boolean): LocalTime {
        val at = s.at ?: return s.part!!.default
        if (!s.bare) return at
        return if (otherDay) (if (at.hour in 1..6) at.plusHours(12) else at) else at
    }

    /** A bare hour without a day: either half of the clock; anything else as said. */
    private fun candidates(s: SaidTime): List<LocalTime> {
        val at = s.at ?: return listOf(s.part!!.default)
        return if (s.bare) listOf(at, at.plusHours(12)) else listOf(at)
    }

    /** The resolved time as the eval set writes it ("2026-10-31T09:00", "2026-10-26T07:00 weekdays", "2026-10-25", "ask"). */
    fun format(r: Resolved): String = when (r) {
        is Resolved.At -> r.at.toString().take(16) + (r.rule?.let { " ${it.key}" } ?: "")
        is Resolved.Day -> r.day.toString()
        is Resolved.Ask -> "ask"
    }
}
