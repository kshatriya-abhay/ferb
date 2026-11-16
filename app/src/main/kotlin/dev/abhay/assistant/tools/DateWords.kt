package dev.abhay.assistant.tools

import java.time.DayOfWeek
import java.time.LocalTime

/**
 * The words for days and times that Ferb's two date readers share ([DateResolver] for a call's
 * moment, [When] for an event's span and the agenda's day), once (review P2-45: their copies had
 * drifted). The rules that read them stay in each reader; a word added here is read by both.
 */
object DateWords {
    /** A month's name or its abbreviation, as a regex group. */
    const val MONTH = """(january|jan|february|feb|march|mar|april|apr|may|june|jun|july|jul|august|aug|september|sept|sep|october|oct|november|nov|december|dec)"""

    /** A weekday's whole name, as a regex group. */
    const val WEEKDAY_NAME = """(monday|tuesday|wednesday|thursday|friday|saturday|sunday)"""

    /** A weekday's name, or an abbreviation that isn't a word of its own ("sat", "wed" and "sun" are), as a regex group. */
    const val WEEKDAY = """(monday|mon|tuesday|tues|tue|wednesday|thursday|thurs|thu|friday|fri|saturday|sunday)"""

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val WEEKDAYS = DayOfWeek.entries.associateBy { it.name.take(3).lowercase() }

    /** The month (1-12) a word matched by [MONTH] names. */
    fun month(word: String): Int = MONTHS.indexOf(word.take(3).lowercase()) + 1

    /** The day a word matched by [WEEKDAY] or [WEEKDAY_NAME] names. */
    fun weekday(word: String): DayOfWeek = WEEKDAYS.getValue(word.take(3).lowercase())

    /** Parts of the day, and the time each means when no time is said ("tomorrow morning" is 9:00). */
    val PART_TIMES = mapOf(
        "morning" to LocalTime.of(9, 0), "noon" to LocalTime.NOON, "lunch" to LocalTime.of(13, 0), "afternoon" to LocalTime.of(14, 0),
        "evening" to LocalTime.of(18, 0), "dinner" to LocalTime.of(20, 0), "tonight" to LocalTime.of(20, 0), "night" to LocalTime.of(20, 0),
        "breakfast" to LocalTime.of(8, 0),
    )
}
