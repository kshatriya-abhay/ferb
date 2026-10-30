package dev.abhay.assistant.tools

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The time of a timed call, worked out by Ferb (the user's decision, 2026-10-25, from the blind date
 * round): [DateResolver] reads the words as said, and the rewritten command (the rewrite prompt) too when
 * there is one; the call runs only when the two agree, else it asks, showing what Ferb read. Needle
 * 3's own time arguments never decide. The resolved value goes in the tool's arguments as
 * [TimeArgs] has them ([TimeArgs.RESOLVED], [TimeArgs.AT], [TimeArgs.DAY], [TimeArgs.REPEAT],
 * [TimeArgs.ASK], [TimeArgs.WANTED]), so the tools use it as they did the computer's.
 * On the blind set (154 lines, tool from Needle 3 on R): 105 right, 9 wrong; the words alone 111/14.
 */
object DateGate {
    /** The timed tools, and the time argument each shows. */
    private val TIMED = mapOf(
        SetAlarm.name to "time", AddReminder.name to "time", AddEvent.name to "when", SetTimer.name to "duration", ShowAgenda.name to "day",
    )

    fun timed(tool: String) = tool in TIMED

    /** What a text says, read for [tool]: a moment (with a repeat), a day, a length, or why to ask. */
    sealed interface Read {
        data class At(val at: LocalDateTime, val rule: DateResolver.Rule?) : Read
        data class Day(val day: LocalDate) : Read
        data class Length(val seconds: Int) : Read
        data class Ask(val why: String) : Read
    }

    fun read(tool: String, text: String, now: LocalDateTime): Read {
        if (tool == SetTimer.name) return length(text)?.let { Read.Length(it) } ?: Read.Ask("How long?")
        val kind = when (tool) {
            SetAlarm.name -> DateResolver.Kind.Alarm
            AddEvent.name -> DateResolver.Kind.Event
            ShowAgenda.name -> DateResolver.Kind.Agenda
            else -> DateResolver.Kind.Reminder
        }
        return when (val r = runCatching { DateResolver.resolve(text, kind, now) }.getOrElse { DateResolver.Resolved.Ask("When?") }) {
            is DateResolver.Resolved.At -> Read.At(r.at, r.rule)
            is DateResolver.Resolved.Day -> Read.Day(r.day)
            is DateResolver.Resolved.Ask -> Read.Ask(r.why)
        }
    }

    private val UNIT = """(?:hours?|hrs?|minutes?|mins?|seconds?|secs?)"""
    private val NUMBER = """(?:\d+(?:\.\d+)?|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|fifty|sixty|ninety|half an?|a quarter of an?)"""
    private val LENGTH = Regex("""$NUMBER(?: and a half)?\s*$UNIT(?:,?\s*(?:and\s+)?$NUMBER\s*$UNIT)*(?: and a half)?""")

    /** A timer's length in [text] (Hindi too: das minute, dedh ghanta), in seconds. */
    fun length(text: String): Int? {
        val t = DateResolver.normalise(text)
            .replace(Regex("""\b(${HINDI.keys.joinToString("|")})\s+(minute|minat|ghante|ghanta|second|sekand)\b""")) { "${HINDI.getValue(it.groupValues[1])} ${UNITS.getValue(it.groupValues[2])}" }
        return LENGTH.find(t)?.value?.let { Durations.parse(it) }?.takeIf { it > 0 }
    }

    private val HINDI = mapOf(
        "ek" to 1, "do" to 2, "teen" to 3, "chaar" to 4, "char" to 4, "paanch" to 5, "panch" to 5, "chhe" to 6, "saat" to 7, "aath" to 8,
        "nau" to 9, "das" to 10, "pandrah" to 15, "bees" to 20, "tees" to 30, "chalis" to 40, "paintalis" to 45, "pachaas" to 50,
    )
    private val UNITS = mapOf("minute" to "minutes", "minat" to "minutes", "ghante" to "hours", "ghanta" to "hours", "second" to "seconds", "sekand" to "seconds")

    /**
     * An event's length in minutes when [text] says a span ("6 to 8 pm", "from 3 till 5", "kal 6 se
     * 8 baje", "for 30 minutes"), as [When] reads it; Hindi "se ... tak" read as "to", Devanagari
     * in Latin letters first. Null when it says none.
     */
    fun span(text: String, now: LocalDateTime): Int? {
        val t = DateResolver.latin(text.replace("से", "se").replace("तक", "tak")).replace(Regex("""(?i)(\d{1,2}(?:[:.]\d{2})?)\s+se\s+(\d{1,2}(?:[:.]\d{2})?)(\s+baje)?(\s+tak)?"""), "from $1 to $2")
        val said = When.read(t, now)
        val start = said.time
        val end = said.end
        return when {
            start != null && end != null -> java.time.Duration.between(start, end).toMinutes().let { if (it <= 0) it + 24 * 60 else it }.toInt()
            else -> said.length
        }?.takeIf { it in 1..(24 * 60) }
    }

    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)
    private val CLOCK = DateTimeFormatter.ofPattern("H:mm", Locale.US)

    /** As the details and the card show it: "Sat 31 Oct, 9:00", "Sat 31 Oct", "10 min". */
    fun shown(r: Read): String = when (r) {
        is Read.At -> r.at.format(DAY) + ", " + r.at.format(CLOCK) + (r.rule?.let { ", ${it.key}" } ?: "")
        is Read.Day -> r.day.format(DAY)
        is Read.Length -> SetTimer.duration(r.seconds)
        is Read.Ask -> r.why
    }

    /** The outcome for one call: its arguments with Ferb's time, and whether the two readings agreed. */
    data class Gated(val arguments: Map<String, Any?>, val agreed: Boolean, val said: Read, val rewritten: Read?)

    /**
     * [arguments] of a [tool] call with Ferb's time: read from [said], and from [command] (the
     * rewrite) when there is one. Agreeing, the value is set and the call may run; else the value
     * read from the words as said (or the rewrite's, when the words say nothing) is shown, and the
     * call asks first ([TimeArgs.ASK]); with neither, the time is left out and the tool asks when.
     */
    fun apply(tool: String, arguments: Map<String, Any?>, said: String, command: String?, now: ZonedDateTime): Gated {
        val param = TIMED[tool] ?: return Gated(arguments, true, Read.Ask(""), null)
        val local = now.toLocalDateTime().withSecond(0).withNano(0)
        val fromSaid = read(tool, said, local)
        val fromCommand = command?.let { read(tool, it, local) }
        val agreed = fromSaid !is Read.Ask && (fromCommand == null || fromCommand == fromSaid)
        val pick = if (fromSaid !is Read.Ask) fromSaid else fromCommand?.takeIf { it !is Read.Ask }
        val out = arguments.toMutableMap()
        out.remove(param)
        out[TimeArgs.RESOLVED] = true
        when (pick) {
            null -> { out[TimeArgs.ASK] = (fromSaid as Read.Ask).why; return Gated(out, false, fromSaid, fromCommand) }
            is Read.At -> when (tool) {
                SetAlarm.name -> {
                    out[param] = pick.at.toLocalTime().toString()
                    // The Clock app rings at the next time it's that time of day: another day asks,
                    // offering that ring or a reminder on the day asked for.
                    val ring = local.toLocalDate().atTime(pick.at.toLocalTime()).let { if (it.isAfter(local)) it else it.plusDays(1) }
                    if (ring.toLocalDate() != pick.at.toLocalDate()) {
                        out[TimeArgs.WANTED] = pick.at.toString()
                        out[TimeArgs.ASK] = "the Clock app can only set the next ${pick.at.toLocalTime()} (${ring.format(DAY)})"
                    }
                    if (pick.rule != null) out[TimeArgs.ASK] = "repeating alarms aren't supported yet"
                }
                // Reminders repeat daily, weekly, monthly or yearly; not on weekdays only, yet.
                else -> if (pick.rule == DateResolver.Rule.Weekdays) {
                    out[TimeArgs.AT] = pick.at.toString(); out[param] = shown(pick)
                    out[TimeArgs.ASK] = "reminders can't repeat on weekdays only yet"
                } else {
                    out[TimeArgs.AT] = pick.at.toString(); pick.rule?.let { out[TimeArgs.REPEAT] = it.key }; out[param] = shown(pick)
                }
            }
            is Read.Day -> { out[TimeArgs.DAY] = pick.day.toString(); out[param] = pick.day.toString() }
            is Read.Length -> out[param] = "${pick.seconds} seconds"
            is Read.Ask -> {}
        }
        // An event's span: its length, from the words as said or the rewrite, put on the agreed start.
        if (tool == AddEvent.name && pick is Read.At) (span(said, local) ?: command?.let { span(it, local) })?.let { out[TimeArgs.LENGTH] = it }
        if (!agreed) out[TimeArgs.ASK] = when {
            fromSaid is Read.Ask -> "your words don't say when; the rewrite says ${shown(pick)}"
            else -> "your words say ${shown(fromSaid)}, the rewrite ${shown(fromCommand!!)}"
        }
        return Gated(out, agreed && out[TimeArgs.ASK] == null, fromSaid, fromCommand)
    }
}
