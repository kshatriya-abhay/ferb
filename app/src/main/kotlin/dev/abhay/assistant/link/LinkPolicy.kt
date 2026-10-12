package dev.abhay.assistant.link

import dev.abhay.assistant.engine.NeedleReply
import dev.abhay.assistant.tools.ToolConfig
import dev.abhay.assistant.tools.Verdict

/** When Ferb asks Ferb Link, and when the computer's answer runs. */
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
     * The computer's answer runs without asking unless the usual checks say to ask: the tool is set
     * to always ask, the request doesn't mention the tool, or a value isn't the request's. Its
     * model has no calibrated confidence (no stand-in was safe in Ferb's bench), so there's no bar.
     * Calls, messages and new events ask by default; most other tools have Undo or are harmless
     * (an alarm can't be undone from here). Until 2026-10-12 it also had to agree with Needle 3,
     * which made nearly every Hinglish answer ask, as Needle 3 doesn't understand Hinglish.
     */
    fun verdict(laptop: NeedleReply, configs: Map<String, ToolConfig>, request: String): Verdict =
        Verdict.of(laptop, configs, request, bar = 0.0)

    // Spoken requests follow the same rules as typed ones. From 2026-10-12 until the same day,
    // spoken alarms and timers asked first while the computer misheard Hindi times; after Ferb
    // Link's fixes the alarm lines were 12/12 typed and right for real Hindi and clean English.
}
