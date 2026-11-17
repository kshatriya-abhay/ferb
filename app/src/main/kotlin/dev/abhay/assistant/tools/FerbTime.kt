package dev.abhay.assistant.tools

import dev.abhay.assistant.reminders.Repeat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Ferb's reading of a call's time ([DateGate]): the only time a timed tool
 * reads, never the model's own value. [at]: an event's or reminder's date and time; [day]: the
 * agenda's or an all-day event's date; [repeat]: a reminder's repeat; [lengthMinutes]: an
 * event's length from a span said ("6 to 8 pm"); [wanted]: for an alarm on another day than its
 * next ring, when it was wanted (a reminder could do that); [ask]: why to ask first, or null to run;
 * [clock]: an alarm's time of day; [seconds]: a timer's length.
 */
data class FerbTime(
    val at: LocalDateTime? = null,
    val day: LocalDate? = null,
    val repeat: Repeat? = null,
    val lengthMinutes: Int? = null,
    val wanted: LocalDateTime? = null,
    val ask: String? = null,
    val clock: LocalTime? = null,
    val seconds: Int? = null,
)
