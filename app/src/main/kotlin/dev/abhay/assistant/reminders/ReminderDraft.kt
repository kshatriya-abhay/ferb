package dev.abhay.assistant.reminders

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The repeat choices on the New reminder page; [Weekly]'s label names the date's weekday. */
enum class RepeatChoice(val repeat: Repeat?) {
    None(null), Daily(Repeat.Daily), Weekly(Repeat.Weekly), Monthly(Repeat.Monthly), Yearly(Repeat.Yearly);

    fun label(date: LocalDate): String = when (this) {
        None -> "Doesn't repeat"
        Daily -> "Daily"
        Weekly -> "Weekly on " + date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
        Monthly -> "Monthly"
        Yearly -> "Yearly"
    }
}

/**
 * A reminder made by hand on the New reminder page: no note, like `add_reminder` without one. It
 * saves through [ReminderBook.add], as the tool does, so the store, the alarms and the Reminders
 * tab all match.
 */
data class ReminderDraft(val title: String, val date: LocalDate, val time: LocalTime, val repeat: RepeatChoice = RepeatChoice.None) {
    val at: LocalDateTime get() = date.atTime(time)

    /** Save is offered once there's a title and a time still to come. */
    fun valid(now: LocalDateTime): Boolean = title.isNotBlank() && at.isAfter(now)

    /** Why Save is off, said under the time: only for a time gone by (a missing title speaks for itself). */
    fun problem(now: LocalDateTime): String? = if (!at.isAfter(now)) "That time has passed" else null

    /** Saves it; null if it isn't [valid]. Then schedule the alarms again, as the tool does. */
    fun save(book: ReminderBook, now: LocalDateTime): Reminder? =
        if (!valid(now)) null else book.add(title.trim(), at, repeat.repeat)

    companion object {
        /** A new draft: no title, at the next whole hour (14:00 at 13:00 or 13:20), not repeating. */
        fun new(now: LocalDateTime): ReminderDraft {
            val next = now.truncatedTo(ChronoUnit.HOURS).plusHours(1)
            return ReminderDraft("", next.toLocalDate(), next.toLocalTime())
        }
    }
}
