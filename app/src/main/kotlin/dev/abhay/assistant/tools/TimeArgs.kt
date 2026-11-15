package dev.abhay.assistant.tools

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Ferb's reading of a call's time ([DateGate]), carried in the call's arguments as one typed value
 * ([FerbTime], under [KEY]): it wins over the model's own value and the request's words. A call
 * that has one was resolved by Ferb; tools read it with [of].
 */
object TimeArgs {
    const val KEY = "_ferb_time"

    /**
     * [at]: an event's or reminder's date and time; [day]: the agenda's or an all-day event's date;
     * [repeat]: a reminder's repeat key; [lengthMinutes]: an event's length from a span said ("6 to
     * 8 pm"); [wanted]: for an alarm on another day than its next ring, when it was wanted (a
     * reminder could do that); [ask]: why to ask first, in a few words, or null to run.
     */
    data class FerbTime(
        val at: LocalDateTime? = null,
        val day: LocalDate? = null,
        val repeat: String? = null,
        val lengthMinutes: Int? = null,
        val wanted: LocalDateTime? = null,
        val ask: String? = null,
    )

    /** Ferb's time for a call with these [arguments], or null when Ferb didn't resolve it. */
    fun of(arguments: Map<String, Any?>): FerbTime? = arguments[KEY] as? FerbTime
}
