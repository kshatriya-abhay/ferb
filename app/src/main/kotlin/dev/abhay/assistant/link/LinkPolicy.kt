package dev.abhay.assistant.link

import dev.abhay.assistant.engine.NeedleReply
import dev.abhay.assistant.tools.ToolConfig
import dev.abhay.assistant.tools.Tools
import dev.abhay.assistant.tools.Verdict
import java.util.Locale

/** When Ferb asks Ferb Link, and when the computer's answer runs. */
object LinkPolicy {
    /** Whether the computer answers first: in [LinkMode.Option] with Ferb Link picked in the chat box. */
    fun laptopFirst(mode: LinkMode, answerer: Answerer) = mode == LinkMode.Option && answerer == Answerer.Link

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
     * "Use Jev to select the tool": Jev's pick counts from here, a tool or "none". In Ferb's bench
     * (2026-10-16, 460 English requests, Jev's tool given alone to the answering model), right
     * answers / wrong runs: Phone 360 / 19 to 426 / 6 (at Needle 3's own bar for the tool, as
     * always), Computer 418 / 21 to 434 / 10 (and the since removed "When Needle 3 is unsure" 409 / 33 to 434 / 12);
     * the computer on Hindi and Hinglish 20 to 22 of 24 right, no wrong runs either way. At 0.7,
     * more answers asked wrongly with no fewer wrong runs. Jev's p isn't proof: two of its wrong
     * picks were at 1.00, so the answering model's checks still decide what runs.
     */
    const val JEV_BAR = 0.5

    /**
     * For the computer, Jev's other tools from this probability join its pick ([JEV_MORE_TOOLS] in
     * all), so a request for two things can still get both: in the bench it brought back "turn on
     * the torch and open the camera" without giving back any of the wrong runs Jev's pick saved.
     * Needle 3 gets Jev's pick alone, whose bar is set for one tool.
     */
    const val JEV_ALSO = 0.25
    const val JEV_MORE_TOOLS = 3

    /**
     * What Jev's [jev] reply means for the answer, among the tools turned on ([enabled]): its tool
     * (and, for the computer, the next ones it rates), no tool, or going on without Jev.
     */
    fun select(jev: JevReply, enabled: Collection<String>): JevPick {
        val p = jev.p ?: return JevPick.Fallback(
            if (jev.errorDetail?.startsWith("Jev reached its spending limit") == true) "Jev's limit reached" else "Jev ${jev.error}",
            jev.errorDetail ?: jev.error ?: "Jev failed")
        return when {
            p < JEV_BAR -> JevPick.Fallback("Jev wasn't sure", String.format(Locale.US, "Jev's best pick had %.2f, below %.2f", p, JEV_BAR))
            jev.choice == JevReply.NONE -> JevPick.NoTool
            jev.choice in enabled -> JevPick.Tool(jev.choice!!, jev.probabilities.entries
                .filter { (name, q) -> name != jev.choice && name in enabled && q >= JEV_ALSO }
                .sortedByDescending { it.value }.map { it.key }.take(JEV_MORE_TOOLS - 1))
            else -> JevPick.Fallback("Jev: unknown tool", "Jev picked ${jev.choice}, which isn't turned on")
        }
    }

    /** Jev's pick in a few words, for the details; null when Ferb didn't ask it. */
    fun jevLine(jev: JevReply?): String? {
        jev ?: return null
        val p = jev.p ?: return when {
            jev.errorDetail?.startsWith("Jev reached its spending limit") == true -> "Jev's spending limit is reached: raise it in Ferb Link"
            else -> "Jev didn't answer: ${jev.errorDetail ?: jev.error}"
        }
        val picked = jev.choice?.let { Tools.byName(it)?.title } ?: "no tool"
        return if (p < JEV_BAR) String.format(Locale.US, "Jev wasn't sure (%s, %.2f)", picked, p)
        else String.format(Locale.US, "Jev picked %s (%.2f)", picked, p)
    }

    // Spoken requests follow the same rules as typed ones. From 2026-10-12 until the same day,
    // spoken alarms and timers asked first while the computer misheard Hindi times; after Ferb
    // Link's fixes the alarm lines were 12/12 typed and right for real Hindi and clean English.
}

/** What happens after Jev answers ([LinkPolicy.select]). */
sealed interface JevPick {
    /** The answering model makes the call with only [name]; the computer also gets [also]. */
    data class Tool(val name: String, val also: List<String> = emptyList()) : JevPick
    /** Jev says no tool fits. */
    data object NoTool : JevPick
    /** As without Jev: [reason] in a few words, for the chat, and [detail] at length. */
    data class Fallback(val reason: String, val detail: String) : JevPick
}
