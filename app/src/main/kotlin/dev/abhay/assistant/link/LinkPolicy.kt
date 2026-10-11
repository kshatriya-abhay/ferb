package dev.abhay.assistant.link

import dev.abhay.assistant.engine.NeedleReply
import dev.abhay.assistant.engine.ToolCall
import dev.abhay.assistant.tools.ToolConfig
import dev.abhay.assistant.tools.Verdict

/** When Ferb asks Ferb Link, and whose answer it keeps. */
object LinkPolicy {
    /** Whether the computer answers first: in [LinkMode.Option] with Ferb Link picked in the chat box. */
    fun laptopFirst(mode: LinkMode, answerer: Answerer) = mode == LinkMode.Option && answerer == Answerer.Link

    /**
     * In [LinkMode.Fallback], whether to ask the computer after Needle 3's [reply]: when Needle 3
     * found no tool (or failed), or has calls below the bar they needed ([verdict]'s runAt). Not
     * when it would only ask because a tool is set to always ask, or a value isn't the request's:
     * the computer's answer would ask too.
     */
    fun askLaptopAfter(reply: NeedleReply, verdict: Verdict): Boolean =
        reply.calls.isEmpty() || (reply.confidence ?: 0.0) < verdict.runAt

    /**
     * Which answer to show when both answered: the computer's whenever it answered, including "no
     * tool fits" (its strength in Ferb's bench: Gemma 4 E2B turned down 30 of 36 near misses,
     * Needle 3 14); Needle 3's when the computer failed.
     */
    fun keep(needle: NeedleReply, laptop: NeedleReply): NeedleReply = if (laptop.error == null) laptop else needle

    /**
     * Whether the computer's answer and Needle 3's make the same calls: the same tools, with the
     * same arguments (text compared without case or surrounding spaces, numbers by value), in any
     * order. No calls on both sides isn't agreement: there's nothing to run.
     */
    fun agrees(laptop: NeedleReply, needle: NeedleReply?): Boolean {
        if (needle == null || laptop.calls.isEmpty()) return false
        fun key(call: ToolCall) = call.name to call.arguments.mapValues { (_, v) -> normal(v) }
        return laptop.calls.map(::key).sortedBy { it.toString() } == needle.calls.map(::key).sortedBy { it.toString() }
    }

    private fun normal(value: Any?): Any? = when (value) {
        is String -> value.trim().lowercase().let { it.toDoubleOrNull() ?: it.toBooleanStrictOrNull() ?: it }
        is Number -> value.toDouble()
        else -> value
    }

    /**
     * The computer's answer runs without asking only when Needle 3 made the same calls ([agrees])
     * and the usual checks pass (the tools' "always ask", their keywords, values the request
     * says): no stand-in for its confidence was safe in Ferb's bench, and on the requests Needle 3
     * wouldn't run, agreement ran 24 right calls and 5 wrong against a first-token bar's 49 and 11.
     * Otherwise it asks.
     */
    fun verdict(laptop: NeedleReply, needle: NeedleReply?, configs: Map<String, ToolConfig>, request: String): Verdict =
        Verdict.of(laptop, configs, request, bar = if (agrees(laptop, needle)) 0.0 else NEVER)

    /** A bar no confidence reaches: always ask. */
    const val NEVER = 2.0
}
