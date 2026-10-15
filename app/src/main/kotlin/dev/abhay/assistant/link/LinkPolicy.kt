package dev.abhay.assistant.link

import dev.abhay.assistant.engine.NeedleReply
import dev.abhay.assistant.engine.Decision
import dev.abhay.assistant.tools.ToolConfig
import dev.abhay.assistant.tools.Tools
import dev.abhay.assistant.tools.Verdict
import java.util.Locale

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

    /**
     * The probability from which Jev's pick counts (its top probability, not TypeSafe's own
     * "confidence"). In Ferb's bench (2026-10-15, the 217 requests Needle 3 was unsure of) it
     * cut the computer's wrong runs from 14 to 6 with no right run lost; 0.5 lost one, and higher
     * bars let more wrong runs through. Jev's p isn't proof: two of its 11 wrong picks were at 1.00.
     */
    const val JEV_BAR = 0.7

    /**
     * [verdict] for [shown] (the computer's answer, or Needle 3's unsure one when the computer
     * couldn't answer) with Jev's second opinion [jev], in Fallback mode:
     * - Jev picks the same tool at [JEV_BAR] or more: the computer's call runs; Needle 3's runs
     *   only for tools that may ([dev.abhay.assistant.tools.Tool.jevMayRun]), and otherwise asks;
     * - Jev picks another tool, or none, at [JEV_BAR] or more: it asks;
     * - Jev below the bar, failed or not asked, or a check already asks (always asks, not
     *   mentioned, a value not the request's): as without Jev, so an unsure Jev on Hinglish
     *   doesn't make the computer's answers ask, as agreeing with Needle 3 once did.
     */
    fun jev(verdict: Verdict, shown: NeedleReply, jev: JevReply?): Decision {
        val p = jev?.p ?: return verdict.decision
        if (shown.calls.isEmpty() || p < JEV_BAR || verdict.alwaysConfirm || verdict.unmentioned || verdict.ungrounded) return verdict.decision
        return when {
            shown.calls.any { it.name != jev.choice } -> Decision.Confirm
            shown.fromLaptop -> Decision.Run
            shown.calls.all { Tools.byName(it.name)?.jevMayRun == true } -> Decision.Run
            else -> verdict.decision
        }
    }

    /** Jev's opinion on [calls] (the shown answer's tool names) in a few words, for the details; null when Ferb didn't ask it. */
    fun jevLine(jev: JevReply?, calls: List<String>): String? {
        jev ?: return null
        val p = jev.p ?: return when {
            jev.errorDetail?.startsWith("Jev reached its spending limit") == true -> "Jev's spending limit is reached: raise it in Ferb Link"
            else -> "Jev didn't answer: ${jev.errorDetail ?: jev.error}"
        }
        val picked = jev.choice?.let { Tools.byName(it)?.title } ?: "no tool"
        return when {
            p < JEV_BAR -> String.format(Locale.US, "Jev wasn't sure (%s, %.2f)", picked, p)
            calls.isNotEmpty() && calls.all { it == jev.choice } -> String.format(Locale.US, "Jev agreed (%.2f)", p)
            else -> String.format(Locale.US, "Jev suggested %s (%.2f)", picked, p)
        }
    }

    // Spoken requests follow the same rules as typed ones. From 2026-10-12 until the same day,
    // spoken alarms and timers asked first while the computer misheard Hindi times; after Ferb
    // Link's fixes the alarm lines were 12/12 typed and right for real Hindi and clean English.
}
