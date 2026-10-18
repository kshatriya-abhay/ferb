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
        val expr = TimeExpr.parse(arguments["when"]?.takeIf { tool != AddEvent.name || it !is String })
            ?: TimeExpr.parse(arguments[param]) ?: return arguments
        val own = (arguments[param] as? String)?.takeIf { TimeExpr.parse(it) == null }
        val out = arguments.toMutableMap()
        out[RESOLVED] = true
        if (!grounded(expr, texts)) {
            out[ASK] = "the time isn't what you said"
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

    /**
     * Whether [expr] is what the user said: its source in one of [texts], word for word, and each
     * of its numbers one the source says (as a digit, an English or Hindi number word, or a
     * fraction word's part), an hour also as its pm hour, or one less after "paune" / "to".
     */
    fun grounded(expr: TimeExpr, texts: List<String>): Boolean {
        fun norm(t: String) = t.lowercase().replace(Regex("""[^a-z0-9:]+"""), " ").trim()
        val source = norm(expr.source)
        if (source.isEmpty() || texts.none { " ${norm(it)} ".contains(" $source ") }) return false
        val words = source.split(" ", ":")
        val said = words.mapNotNull { it.toIntOrNull() ?: SaidNumbers.WORDS[it] ?: HINDI[it] }.toMutableSet()
        words.forEach { w -> FRACTIONS[w]?.let { said += it } }
        val before = words.any { it == "paune" || it == "to" }
        return expr.numbers.all { n ->
            val as12 = listOfNotNull(n, (n - 12).takeIf { n in 13..23 })
            n == 0 || as12.any { it in said || (before && it + 1 in said) }
        }
    }
}
