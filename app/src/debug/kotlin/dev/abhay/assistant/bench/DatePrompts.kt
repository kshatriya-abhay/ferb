package dev.abhay.assistant.bench

import dev.abhay.assistant.voice.GeminiRewrite
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Debug builds only: the date and time approaches through Gemini Nano (2026-10-24), written once,
 * before any run on tool-check/datetime.txt; their examples are in no set.
 * B: the shipped rewrite prompt and a 14-day calendar, so the command says the date.
 * C: the request's time as "time" v1 JSON ([dev.abhay.assistant.tools.TimeExpr]), no arithmetic.
 */
object DatePrompts {
    private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.US)
    private val NOW = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.US)

    /** Today and the 13 days after it, one a line: "today: Saturday 24 October". */
    fun calendar(now: LocalDateTime): String = (0L until 14L).joinToString("\n") { i ->
        val day = now.toLocalDate().plusDays(i)
        val label = when (i) { 0L -> "today: "; 1L -> "tomorrow: "; 2L -> "day after tomorrow: "; else -> "" }
        label + day.format(DAY)
    }

    fun calendarPrompt(now: LocalDateTime): String = GeminiRewrite.PROMPT_V2 + """

It is now ${now.format(NOW)}. The next 14 days:
${calendar(now)}
When the request names a day (today, tonight, tomorrow, kal, parso, a weekday, a date), write that day in the command as its weekday and date from this list, such as "on Thursday 12 March"; a weekday alone, or with "next", is the first such day after today. Keep times as said; never add a day the request doesn't say."""

    const val SLOTS = """You read when a request to a phone assistant says something should happen. The request may be English, Hindi or Hinglish.
Reply with one line of JSON and nothing else:
{"kind":"at","day":DAY,"time":TIME} for a moment;
{"kind":"in","length":LEN} for a length from now, such as in 20 minutes or 2 ghante mein;
{"kind":"duration","length":LEN} for a timer's length;
{"kind":"every","period":"day" or "week" or "weekdays" or "month","on":[weekdays],"time":TIME} for a repeat;
{"kind":"none"} when the request says no time.
DAY: null; {"relative":0} for today, aaj or tonight; {"relative":1} for tomorrow or kal; {"relative":2} for the day after tomorrow or parso; {"weekday":"friday","which":"next" or "this" or null}; {"day":14,"month":2,"year":null} for a date, month null for "the 14th".
TIME: null, or {"hour":7,"minute":30,"meridiem":"am" or "pm" or null,"part":"morning" or "afternoon" or "evening" or "night" or null}. Copy the hour as said and never add am or pm the request doesn't say. subah is morning, dopahar afternoon, shaam evening, raat night; tonight is part night. noon is hour 12 pm, midnight hour 12 am. sava 7 is 7:15, saade 5 is 5:30, paune 8 is 7:45.
LEN: {"days":0,"hours":1,"minutes":30,"seconds":0}.
Examples:
remind me next thursday at 4 pm to renew the lease -> {"kind":"at","day":{"weekday":"thursday","which":"next"},"time":{"hour":4,"minute":0,"meridiem":"pm","part":null}}
parso dopahar 3 baje meeting -> {"kind":"at","day":{"relative":2},"time":{"hour":3,"minute":0,"meridiem":null,"part":"afternoon"}}
15 minute mein yaad dilana -> {"kind":"in","length":{"days":0,"hours":0,"minutes":15,"seconds":0}}
remind me every tuesday at 10 to water the hedge -> {"kind":"every","period":"week","on":["tuesday"],"time":{"hour":10,"minute":0,"meridiem":null,"part":null}}
torch on please -> {"kind":"none"}"""

    /** JSON for one moment takes about 40 tokens; room for a repeat's. */
    const val SLOTS_MAX_TOKENS = 100
}
