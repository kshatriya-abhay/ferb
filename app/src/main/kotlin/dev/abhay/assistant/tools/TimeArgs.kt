package dev.abhay.assistant.tools

/**
 * Where a call's time, worked out by Ferb ([DateGate]), goes in the tool's arguments: it wins over
 * the model's own value and the request's words. Ferb asks first ([ASK]) when the readings
 * disagree, or when the tool can't do what was asked (an alarm on another day than its next ring).
 */
object TimeArgs {
    /** Set when the time was resolved here: tools use their resolved value, not the request's words. */
    const val RESOLVED = "_time_resolved"
    /** Why to ask first about the time, in a few words. */
    const val ASK = "_time_ask"
    /** Resolved values: an event's or reminder's date and time, an all-day event's date, a reminder's repeat. */
    const val AT = "_at"
    const val DAY = "_day"
    const val REPEAT = "_repeat"
    /** For an alarm on another day than its next ring: when it was wanted (a reminder could do that). */
    const val WANTED = "_wanted"
    /** An event's length in minutes, from a span said ("6 to 8 pm", "kal 6 se 8 baje", "for 30 minutes"). */
    const val LENGTH = "_length"
}
