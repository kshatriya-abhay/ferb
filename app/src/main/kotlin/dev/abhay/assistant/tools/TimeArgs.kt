package dev.abhay.assistant.tools

import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A call's time from the computer's structure ([TimeExpr], in the call's "when", or as the time
 * argument itself), resolved by Ferb ([Resolve]) and put in the tool's arguments, where it wins
 * over the model's own value and the request's words. Before that, a grounding check: the
 * structure's words must be the request's (typed, or heard), and its numbers ones they say. Ferb
 * asks first ([ASK]) when they aren't, when the model's own value disagrees with the resolved one,
 * or when the resolver says to (an alarm on another day than its next ring, "this weekend").
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

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    /** Each timed tool's time argument, and what it's resolved for. */
    private val TIMED = mapOf(
        SetAlarm.name to ("time" to Resolve.For.Alarm),
        SetTimer.name to ("duration" to Resolve.For.Timer),
        AddEvent.name to ("when" to Resolve.For.Event),
        AddReminder.name to ("time" to Resolve.For.Reminder),
    )

    /**
     * [arguments] with the call's time resolved, for [tool]; unchanged when it has no structure.
     * [texts]: what the user said (the request, and a spoken one's heard words).
     */
    fun apply(tool: String, arguments: Map<String, Any?>, texts: List<String>, now: ZonedDateTime): Map<String, Any?> {
        val (param, kind) = TIMED[tool] ?: return arguments
        val parsed = TimeExpr.parse(arguments["when"]?.takeIf { tool != AddEvent.name || it !is String })
            ?: TimeExpr.parse(arguments[param]) ?: return arguments
        // Only what was said: a day or part of the day the user didn't say is dropped, and a
        // structure that then says nothing is no structure (the model's own value stands).
        val expr = said(parsed, texts) ?: return arguments
        val own = (arguments[param] as? String)?.takeIf { TimeExpr.parse(it) == null }
        val out = arguments.toMutableMap()
        out[RESOLVED] = true
        val doubt = if (!grounded(expr, texts)) "the time isn't what you said" else doubt(expr, kind, texts)
        if (doubt != null) {
            out[ASK] = doubt
            own?.let { out[param] = it } ?: out.remove(param)
            return out
        }
        val resolved = Resolve.resolve(expr, kind, now)
        val local = now.toLocalDateTime().withSecond(0).withNano(0)
        when (resolved) {
            is Resolved.Ask -> {
                out[ASK] = resolved.reason
                resolved.ring?.let { out[param] = it.format(CLOCK) }
                resolved.wanted?.let { out[WANTED] = it.toString() }
                if (resolved.ring == null) own?.let { out[param] = it } ?: out.remove(param)
                return out
            }
            is Resolved.At -> when (kind) {
                Resolve.For.Alarm -> out[param] = resolved.at.format(CLOCK)
                else -> { out[AT] = resolved.at.toString(); out[param] = describe(resolved.at) }
            }
            is Resolved.OnDay -> { out[DAY] = resolved.day.toString(); out[param] = "on ${resolved.day}" }
            is Resolved.Lasting -> out[param] = "${resolved.length.seconds} seconds"
            is Resolved.Every -> { out[AT] = resolved.first.toString(); out[REPEAT] = resolved.repeat.key; out[param] = describe(resolved.first) }
        }
        // The model's own value, when it wrote one, must agree; else ask (the plan: safer while
        // the time set builds trust).
        if (own != null && !agrees(kind, own, resolved, local)) out[ASK] = "the computer's “$own” isn't ${shown(resolved)}"
        return out
    }

    private fun describe(at: LocalDateTime) = "on ${at.toLocalDate()} at ${at.format(CLOCK)}"

    private fun shown(resolved: Resolved) = when (resolved) {
        is Resolved.At -> describe(resolved.at)
        is Resolved.OnDay -> "on ${resolved.day}"
        is Resolved.Lasting -> "${resolved.length.seconds} seconds"
        is Resolved.Every -> describe(resolved.first)
        is Resolved.Ask -> resolved.reason
    }

    /** Whether the model's own value says the same as the resolved one (where Ferb can read it). */
    private fun agrees(kind: Resolve.For, own: String, resolved: Resolved, now: LocalDateTime): Boolean = when (kind) {
        Resolve.For.Alarm -> (resolved as? Resolved.At)?.let { at -> TimeOfDay.parse(own)?.let { it == at.at.hour to at.at.minute } } ?: true
        Resolve.For.Timer -> (resolved as? Resolved.Lasting)?.let { l -> Durations.parse(own)?.let { it.toLong() == l.length.seconds } } ?: true
        else -> {
            val said = When.read(own, now)
            when (resolved) {
                is Resolved.At -> (said.day == null || said.day == resolved.at.toLocalDate()) && (said.time == null || said.time == resolved.at.toLocalTime())
                is Resolved.OnDay -> said.day == null || said.day == resolved.day
                else -> true
            }
        }
    }

    /** Hindi number words, as Ferb Link's Amounts has them, for numbers said in Hindi. */
    private val HINDI = mapOf(
        "ek" to 1, "do" to 2, "teen" to 3, "chaar" to 4, "char" to 4, "paanch" to 5, "panch" to 5, "chhe" to 6, "chhah" to 6,
        "chah" to 6, "che" to 6, "saat" to 7, "aath" to 8, "nau" to 9, "das" to 10, "gyarah" to 11, "barah" to 12,
        "pandrah" to 15, "bees" to 20, "pachchees" to 25, "tees" to 30, "chalees" to 40, "chalis" to 40, "pachaas" to 50,
    )

    /** Fraction words and the numbers they account for ("sava saat" is 7:15, "paune aath" 7:45, "dedh ghanta" 1 h 30). */
    private val FRACTIONS = mapOf(
        "sava" to listOf(15), "quarter" to listOf(15, 45), "saade" to listOf(30), "sadhe" to listOf(30), "half" to listOf(30, 1),
        "paune" to listOf(45), "dedh" to listOf(1, 30), "derh" to listOf(1, 30), "dhai" to listOf(2, 30), "dhaai" to listOf(2, 30),
    )

    private fun norm(t: String) = t.lowercase().replace(Regex("""(\d)([a-z])"""), "$1 $2").replace(Regex("""[^a-z0-9:]+"""), " ").trim()

    private val MONTH_WORDS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    /** Words that name a day, whatever the structure says: a weekday, a relative day, a date. */
    private fun saysADay(words: Set<String>) = words.any { w ->
        WEEKDAY_WORDS.values.any { w in it } || RELATIVE_WORDS.values.any { w in it && w != "this" } || MONTH_WORDS.any { w.startsWith(it) } ||
            w in setOf("st", "nd", "rd", "th", "next", "agle", "agla")
    }
    /** "in 20 minutes", "an hour from now", "10 minute mein", "do ghante baad": a length from now. */
    private val FROM_NOW = Regex("""\bin (an?|\d+|half|one|two|three|four|five|ten|twenty|thirty|forty|fifty)\b|\bfrom now\b|\b(minutes?|mins?|ghante|ghanta|ghanton|seconds?|din) (mein|baad)\b""")

    /** Words that say a day, by what they say; English and Hindi in Latin letters. */
    private val RELATIVE_WORDS = mapOf(
        0 to setOf("today", "tonight", "aaj", "this"), 1 to setOf("tomorrow", "kal"), 2 to setOf("parso", "parson", "parsoon", "after"),
    )
    private val WEEKDAY_WORDS = mapOf(
        java.time.DayOfWeek.MONDAY to setOf("monday", "mon", "somvar", "somwar"),
        java.time.DayOfWeek.TUESDAY to setOf("tuesday", "tue", "tues", "mangalvar", "mangalwar"),
        java.time.DayOfWeek.WEDNESDAY to setOf("wednesday", "wed", "budhvar", "budhwar"),
        java.time.DayOfWeek.THURSDAY to setOf("thursday", "thu", "thurs", "guruvar", "guruwar", "brihaspativar"),
        java.time.DayOfWeek.FRIDAY to setOf("friday", "fri", "shukravar", "shukrawar"),
        java.time.DayOfWeek.SATURDAY to setOf("saturday", "sat", "shanivar", "shaniwar", "weekend"),
        java.time.DayOfWeek.SUNDAY to setOf("sunday", "sun", "ravivar", "raviwar", "itvaar", "itwar", "weekend"),
    )
    private val PART_WORDS = mapOf(
        "morning" to setOf("morning", "subah", "subha", "breakfast", "am"),
        "afternoon" to setOf("afternoon", "dopahar", "noon", "lunch", "pm"),
        "evening" to setOf("evening", "shaam", "sham", "pm"),
        "night" to setOf("night", "tonight", "raat", "pm"),
    )

    /**
     * [expr] with only what [texts] say: a day ([TimeExpr.At.day]) without a word for it and a
     * part of the day without one are dropped, as the model fills them in unasked ("wake me up
     * at 6:45" came back as this Friday, 2026-10-18). Null when nothing is left to say when.
     */
    private fun said(expr: TimeExpr, texts: List<String>): TimeExpr? {
        val words = texts.flatMap { norm(it).split(" ") }.toSet()
        fun part(c: TimeExpr.Clock?) = c?.let { clock ->
            clock.copy(part = clock.part?.takeIf { p -> PART_WORDS[p].orEmpty().any { it in words } },
                // am or pm only when said ("saade paanch baje" came back as 5:30 pm).
                meridiem = clock.meridiem?.takeIf { m ->
                    m in words || (m == "am" && "midnight" in words) || (m == "pm" && "noon" in words) ||
                        // Implied by a part of the day the user said ("raat 9 baje" is 9 pm, "subah 6" 6 am).
                        PART_WORDS.any { (part, said) ->
                            said.any { it in words && it !in setOf("am", "pm") } && m == when {
                                part == "morning" -> "am"
                                // At night 12 and 1–5 are the small hours ("raat 2 baje" is 2 am), as TimeOfDay reads them.
                                part == "night" && ((clock.hour ?: 0) == 12 || (clock.hour ?: 0) in 1..5) -> "am"
                                else -> "pm"
                            }
                        }
                })
        }?.takeIf { it.hour != null || it.part != null }
        return when (expr) {
            is TimeExpr.At -> {
                val day = when (val d = expr.day) {
                    is TimeExpr.Day.Relative -> d.takeIf { RELATIVE_WORDS[d.days].orEmpty().any { it in words } }
                    is TimeExpr.Day.Weekday -> d.takeIf { WEEKDAY_WORDS.getValue(d.weekday).any { it in words } }
                    // A month or year nobody said is dropped ("the 12th" came back as 12 October 2026).
                    is TimeExpr.Day.Date -> d.takeIf { d.day.toString() in words }?.copy(
                        month = d.month?.takeIf { m -> words.any { it.startsWith(MONTH_WORDS[(m - 1).coerceIn(0, 11)]) } ||
                            texts.any { t -> Regex("""\b${d.day}/0?$m\b""").containsMatchIn(t) } },
                        year = d.year?.takeIf { y -> y.toString() in words })
                    null -> null
                }
                expr.copy(day = day, time = part(expr.time)).takeIf { it.day != null || it.time != null }
            }
            is TimeExpr.Every -> expr.copy(time = part(expr.time))
            else -> expr
        }
    }

    /**
     * Words that say what the structure must hold, checked after the model (they never correct
     * it, only ask): "parso" is the day after tomorrow, and "every", "har", "roz" or "daily" is a
     * repeat a reminder must have. Both were the computer's misses in ferb-link's check (2026-10-18).
     */
    private fun doubt(expr: TimeExpr, kind: Resolve.For, texts: List<String>): String? {
        val words = texts.flatMap { norm(it).split(" ") }.toSet()
        val day = (expr as? TimeExpr.At)?.day
        if (words.any { it in PARSO } && !(day is TimeExpr.Day.Relative && day.days == 2)) return "parso is the day after tomorrow"
        if (kind == Resolve.For.Reminder && words.any { it in REPEATS } && expr !is TimeExpr.Every) return "you asked for it to repeat"
        if (expr is TimeExpr.Every) {
            // A repeat nobody asked for ("remind me next Sunday" came back weekly), or on a weekday not said ("har ravivar" as Saturdays).
            if (words.none { it in REPEATS }) return "you didn't ask for it to repeat"
            if (expr.on.any { day -> WEEKDAY_WORDS.getValue(day).none { it in words } }) return "the day isn't what you said"
        }
        // A length from now must be said as one ("kal subah 7 baje" came back as in 1 day); timers are lengths anyway.
        if (kind != Resolve.For.Timer && (expr is TimeExpr.In || expr is TimeExpr.Lasting) && texts.none { FROM_NOW.containsMatchIn(norm(it)) }) return "that isn't a time from now"
        // A length from now ("in an hour") must be one, not a clock time the model worked out.
        if (texts.any { FROM_NOW.containsMatchIn(norm(it)) } && expr is TimeExpr.At) return "that's a time from now"
        // The user named a day, and the structure has none left that matches.
        if (expr is TimeExpr.At && expr.day == null && saysADay(words)) return "the day isn't what you said"
        return null
    }

    private val PARSO = setOf("parso", "parson", "parsoon")
    private val REPEATS = setOf("every", "har", "roz", "daily", "weekly", "monthly", "yearly", "each")

    /**
     * Whether [expr] is what the user said: its source, when it has one, in one of [texts] word for
     * word; and each of its numbers one the source (or, without one, the user's words) says, as a
     * digit, an English or Hindi number word, or a fraction word's part, an hour also as its pm
     * hour, or one less after "paune" / "to".
     */
    fun grounded(expr: TimeExpr, texts: List<String>): Boolean {
        val source = norm(expr.source)
        if (source.isNotEmpty() && texts.none { " ${norm(it)} ".contains(" $source ") }) return false
        val words = (if (source.isNotEmpty()) source else texts.joinToString(" ") { norm(it) }).split(" ", ":")
        val said = words.mapNotNull { it.toIntOrNull() ?: SaidNumbers.WORDS[it] ?: HINDI[it] }.toMutableSet()
        words.forEach { w -> FRACTIONS[w]?.let { said += it } }
        if ("noon" in words || "midnight" in words) said += 12
        // "three quarters" is 45 minutes, never 15 ("timer for three quarters of an hour" came back
        // as 15 minutes on the held-out bench, 2026-10-18).
        if (Regex("""\bthree quarters?\b""").containsMatchIn(words.joinToString(" "))) { said -= 15; said -= 3; said += 45 }
        val before = words.any { it == "paune" || it == "to" }
        return expr.numbers.all { n ->
            val as12 = listOfNotNull(n, (n - 12).takeIf { n in 13..23 })
            n == 0 || as12.any { it in said || (before && it + 1 in said) }
        }
    }
}
