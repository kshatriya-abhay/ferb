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

    /**
     * In Jev mode, Jev's pick counts from here, a tool or "none". Lower than [JEV_BAR]: whether the
     * call runs also needs Needle 3's own bar for that tool and the usual checks ([Verdict]). In
     * Ferb's bench (2026-10-16, 460 requests, no Hindi) Jev at 0.5 with Needle 3's bar got 426
     * right with 6 wrong runs; at 0.7, 420 and 6 (more asked wrongly); Phone 360 and 19,
     * Computer 418 and 21.
     */
    const val JEV_ANSWER_BAR = 0.5

    /** What Jev mode does with Jev's [jev] reply, among the tools turned on ([enabled]). */
    fun pick(jev: JevReply, enabled: Collection<String>): JevPick {
        val p = jev.p ?: return JevPick.Fallback(
            if (jev.errorDetail?.startsWith("Jev reached its spending limit") == true) "Jev's limit reached" else "Jev: ${jev.error}",
            jev.errorDetail ?: jev.error ?: "Jev failed")
        return when {
            p < JEV_ANSWER_BAR -> JevPick.Fallback("Jev wasn't sure", String.format(Locale.US, "Jev's best pick had %.2f, below %.2f", p, JEV_ANSWER_BAR))
            jev.choice == JevReply.NONE -> JevPick.NoTool
            jev.choice in enabled -> JevPick.Tool(jev.choice!!)
            else -> JevPick.Fallback("Jev: unknown tool", "Jev picked ${jev.choice}, which isn't turned on")
        }
    }

    /** Jev's opinion on [calls] (the shown answer's tool names) in a few words, for the details; null when Ferb didn't ask it. */
    fun jevLine(jev: JevReply?, calls: List<String>, answered: Boolean = false): String? {
        jev ?: return null
        val p = jev.p ?: return when {
            jev.errorDetail?.startsWith("Jev reached its spending limit") == true -> "Jev's spending limit is reached: raise it in Ferb Link"
            else -> "Jev didn't answer: ${jev.errorDetail ?: jev.error}"
        }
        val picked = jev.choice?.let { Tools.byName(it)?.title } ?: "no tool"
        return when {
            answered -> String.format(Locale.US, "Jev picked %s (%.2f)", picked, p)
            p < JEV_BAR -> String.format(Locale.US, "Jev wasn't sure (%s, %.2f)", picked, p)
            calls.isNotEmpty() && calls.all { it == jev.choice } -> String.format(Locale.US, "Jev agreed (%.2f)", p)
            else -> String.format(Locale.US, "Jev suggested %s (%.2f)", picked, p)
        }
    }

    // Spoken requests follow the same rules as typed ones. From 2026-10-12 until the same day,
    // spoken alarms and timers asked first while the computer misheard Hindi times; after Ferb
    // Link's fixes the alarm lines were 12/12 typed and right for real Hindi and clean English.
}

/** Jev mode's next step after Jev answers ([LinkPolicy.pick]). */
sealed interface JevPick {
    /** Needle 3 makes the call with only this tool. */
    data class Tool(val name: String) : JevPick
    /** Jev says no tool fits. */
    data object NoTool : JevPick
    /** Phone answers instead: [reason] in a few words, for the chat, and [detail] at length. */
    data class Fallback(val reason: String, val detail: String) : JevPick
}
