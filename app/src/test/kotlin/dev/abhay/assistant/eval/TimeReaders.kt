package dev.abhay.assistant.eval

import dev.abhay.assistant.reminders.ReminderTime
import dev.abhay.assistant.reminders.Repeat
import dev.abhay.assistant.tools.Durations
import dev.abhay.assistant.tools.Meridiem
import dev.abhay.assistant.tools.SaidNumbers
import dev.abhay.assistant.tools.When
import java.time.LocalDateTime

// How the timed tools read Needle 3's time and the request's words before Ferb read its own
// ([dev.abhay.assistant.tools.DateGate], review P2-69): [DateTimeEval]'s baseline.

/** A spoken or written time of day as hour (0–23) and minute. */
object TimeOfDay {
    private val PATTERN = Regex("""^(\d{1,2})(?:\s*[:.h]\s*(\d{2}))?\s*(am|a\.m\.|pm|p\.m\.)?$""")

    // Words around a time that say am or pm, in English and Hindi ("subah 5 baje", "7 in the evening").
    private val MORNING = Regex("""\b(in the morning|morning|subah|subha)\b""")
    private val LATER = Regex("""\b(in the afternoon|afternoon|in the evening|evening|at night|tonight|night|dopahar|shaam|sham|raat)\b""")
    private val NIGHT = Regex("""\b(at night|tonight|night|raat)\b""")
    // Words that add nothing to the time: "5 o'clock", "5 baje", "tomorrow at 7" (the clock app
    // sets the next time it's that time of day, so tomorrow when it's already passed today).
    private val FILLER = Regex("""\b(o'clock|o’clock|oclock|o clock|baje|bje|sharp|at|tomorrow|today|kal|aaj|ka|ki|ke)\b""")

    /**
     * "8:00 AM", "08:00", "7 pm", "19:30", "6.45", "8", "5 o'clock", "five o'clock", "5 baje", "7 in
     * the evening", "subah 5", "tomorrow at 6" → (hour, minute); null if it isn't one. Without am,
     * pm or a word for the time of day, an hour up to 12 is taken as written (so "8" is 8 am, "12"
     * noon). "5 o'clock" came back from the computer for "paanch baje" (2026-10-12) and was refused.
     */
    fun parse(text: String): Pair<Int, Int>? {
        val lower = Meridiem.normalise(text.trim()).lowercase()
        val hint = when {
            MORNING.containsMatchIn(lower) -> "am"
            LATER.containsMatchIn(lower) -> "pm"
            else -> null
        }
        var t = lower.replace(MORNING, " ").replace(LATER, " ").replace(FILLER, " ")
            .replace(SaidNumbers.WORD) { w -> SaidNumbers.WORDS.getValue(w.value).takeIf { it in 1..12 }?.toString() ?: w.value }
            .replace(Regex("""\s+"""), " ").trim()
        t = if (t == "noon") "12" else if (t == "midnight") "0" else t
        // The word gives am or pm when the time doesn't; at night, 12 to 5 are the small hours
        // ("12 at night" is midnight, "raat 2 baje" 2 am), as Ferb Link reads raat too.
        val small = Regex("""^(\d{1,2})""").find(t)?.groupValues?.get(1)?.toInt()?.let { it == 12 || it in 1..5 } == true
        val said = if (hint == "pm" && NIGHT.containsMatchIn(lower) && small) "am" else hint
        if (said != null && Regex("""^\d{1,2}(?:\s*[:.h]\s*\d{2})?$""").matches(t)) t = "$t $said"
        val m = PATTERN.matchEntire(t) ?: return null
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        val period = m.groupValues[3].replace(".", "")
        if (minute > 59) return null
        when (period) {
            "am" -> { if (hour !in 1..12) return null; if (hour == 12) hour = 0 }
            "pm" -> { if (hour !in 1..12) return null; if (hour != 12) hour += 12 }
            else -> if (hour > 23) return null
        }
        return hour to minute
    }
}

/** A reminder's time and repeat as the request says them. */
object ReminderWords {
    private val RELATIVE = Regex("""\bin (an?|\d+(?:\.\d+)?|half an?) ?(minutes?|mins?|hours?|hrs?|h)\b(?: and (\d+) ?(?:minutes?|mins?))?""")

    /**
     * When a spoken or typed reminder is due: "in 20 minutes" from [now]; else a day and a time
     * ("tomorrow at 9", "Friday", "at 6 pm"), a day alone at 09:00, a time alone the next time it
     * comes. Null if [text] says no time.
     */
    fun time(text: String, now: LocalDateTime): LocalDateTime? {
        val t = text.lowercase()
        RELATIVE.find(t)?.let { m ->
            val seconds = Durations.parse(m.value.removePrefix("in ").trim()) ?: return@let
            return now.plusSeconds(seconds.toLong()).withNano(0)
        }
        val said = When.read(text, now)
        if (said.isEmpty) return null
        val time = said.time ?: ReminderTime.DEFAULT_TIME
        val day = said.day ?: if (time.isAfter(now.toLocalTime())) now.toLocalDate() else now.toLocalDate().plusDays(1)
        return day.atTime(time)
    }

    /** The repeat a request says: "every day", "daily", "every Monday" (weekly), "every month"…; null if it says none. */
    fun repeat(text: String): Repeat? {
        val t = text.lowercase()
        return when {
            Regex("""\b(every ?day|daily|each day|every morning|every evening|every night)\b""").containsMatchIn(t) -> Repeat.Daily
            Regex("""\b(every|each) (week|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b|\bweekly\b""").containsMatchIn(t) -> Repeat.Weekly
            Regex("""\b(every|each) month\b|\bmonthly\b""").containsMatchIn(t) -> Repeat.Monthly
            Regex("""\b(every|each) year\b|\byearly\b|\bannually\b""").containsMatchIn(t) -> Repeat.Yearly
            else -> null
        }
    }
}
