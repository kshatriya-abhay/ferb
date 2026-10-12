package dev.abhay.assistant.link

import dev.abhay.assistant.engine.NeedleReply
import dev.abhay.assistant.engine.ToolCall
import dev.abhay.assistant.tools.Prepared
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
     * Whether the computer's answer and Needle 3's do the same: the same calls, in any order, each
     * compared by its [meaning]. No calls on both sides isn't agreement: there's nothing to run;
     * nor is a call with no meaning (one that can't run).
     *
     * By default calls mean their arguments ([literal]); the app passes what each would do once
     * prepared ([prepared]), as answers that differ in words often do the same: for "Am I free on
     * Friday?" Gemma 4 E2B said day "Friday" and Needle 3 "2026-10-13" (it doesn't know the date),
     * and both show Friday, as the agenda reads the day from the request.
     */
    fun agrees(laptop: NeedleReply, needle: NeedleReply?, meaning: (ToolCall) -> Any? = ::literal): Boolean {
        if (needle == null || laptop.calls.isEmpty()) return false
        val ours = laptop.calls.map(meaning)
        val theirs = needle.calls.map(meaning)
        if (null in ours || null in theirs) return false
        return ours.sortedBy { it.toString() } == theirs.sortedBy { it.toString() }
    }

    /** A call as written: its tool and arguments (text without case or surrounding spaces, numbers by value). */
    fun literal(call: ToolCall): Any = call.name to call.arguments.mapValues { (_, v) -> normal(v) }

    private fun normal(value: Any?): Any? = when (value) {
        is String -> value.trim().lowercase().let { it.toDoubleOrNull() ?: it.toBooleanStrictOrNull() ?: it }
        is Number -> value.toDouble()
        else -> value
    }

    /**
     * What a call would do, as the user would be asked to approve it: its tool, the prepared
     * summary and detail (e.g. "Show Fri 16 Oct", a message's text) and the choices (e.g. the
     * contact it calls). Null when it can't run.
     */
    fun prepared(name: String, prepared: Prepared): Any? = (prepared as? Prepared.Ready)?.let { ready ->
        listOf(name, ready.summary, ready.detail, ready.choices.map { it.label to it.detail })
    }

    /**
     * The computer's answer runs without asking only when Needle 3's does the same ([agrees])
     * and the usual checks pass (the tools' "always ask", their keywords, values the request
     * says): no stand-in for its confidence was safe in Ferb's bench, and on the requests Needle 3
     * wouldn't run, agreement ran 24 right calls and 5 wrong against a first-token bar's 49 and 11.
     * Otherwise it asks.
     */
    fun verdict(laptop: NeedleReply, needle: NeedleReply?, configs: Map<String, ToolConfig>, request: String, meaning: (ToolCall) -> Any? = ::literal): Verdict =
        Verdict.of(laptop, configs, request, bar = if (agrees(laptop, needle, meaning)) 0.0 else NEVER)

    /** A bar no confidence reaches: always ask. */
    const val NEVER = 2.0
}
