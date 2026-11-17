package dev.abhay.assistant.link

import dev.abhay.assistant.tools.Tools
import java.util.Locale

/** Jev's pick (the tool selection step, through Ferb Link): when it counts, and what it means. */
object LinkPolicy {
    /**
     * The tool selection step (Jev): Jev's pick counts from here, a tool or "none". In Ferb's bench
     * (2026-10-16, 460 English requests, Jev's tool given alone to the answering model), right
     * answers / wrong runs: Phone 360 / 19 to 426 / 6 (at Needle 3's own bar for the tool, as
     * always), Computer 418 / 21 to 434 / 10 (and the since removed "When Needle 3 is unsure" 409 / 33 to 434 / 12);
     * the computer on Hindi and Hinglish 20 to 22 of 24 right, no wrong runs either way. At 0.7,
     * more answers asked wrongly with no fewer wrong runs. Jev's p isn't proof: two of its wrong
     * picks were at 1.00, so the answering model's checks still decide what runs.
     */
    const val JEV_BAR = 0.5

    /**
     * What Jev's [jev] reply means for the answer, among the tools turned on ([enabled]): its tool,
     * no tool, or going on without Jev.
     */
    fun select(jev: JevReply, enabled: Collection<String>): JevPick {
        val p = jev.p ?: return JevPick.Fallback(
            named(jev.error ?: if (capped(jev)) "Jev's limit reached" else "failed"),
            jev.errorDetail ?: jev.error ?: "Jev failed")
        return when {
            p < JEV_BAR -> JevPick.Fallback("Jev wasn't sure", String.format(Locale.US, "Jev's best pick had %.2f, below %.2f", p, JEV_BAR))
            jev.choice == JevReply.NONE -> JevPick.NoTool
            jev.choice in enabled -> JevPick.Tool(jev.choice!!)
            else -> JevPick.Fallback("Jev: unknown tool", "Jev picked ${jev.choice}, which isn't turned on")
        }
    }

    /** Jev's pick in a few words, for the details; null when Ferb didn't ask it. */
    fun jevLine(jev: JevReply?): String? {
        jev ?: return null
        val p = jev.p ?: return when {
            capped(jev) -> jev.errorDetail ?: "Jev's spending limit is reached: raise it in Ferb Link"
            else -> "Jev didn't answer: ${jev.errorDetail ?: jev.error}"
        }
        val picked = jev.choice?.let { Tools.byName(it)?.title } ?: "no tool"
        return if (p < JEV_BAR) String.format(Locale.US, "Jev wasn't sure (%s, %.2f)", picked, p)
        else String.format(Locale.US, "Jev picked %s (%.2f)", picked, p)
    }

    /**
     * Whether Jev's spending limit is why it didn't answer, at it or near it: Ferb Link's jev_capped
     * (d588b50); from an older Ferb Link, which doesn't say (its kind is "off", as when Jev is turned
     * off), its detail is read.
     */
    private fun capped(jev: JevReply) = jev.capped ?: (jev.errorDetail?.startsWith("Jev reached its spending limit") == true)

    /** [short], one of Ferb Link's few words, with Jev's name once: "Jev off" and "can't reach Jev" as they are, "timed out" as "Jev timed out". */
    private fun named(short: String) = if (Regex("""(?i)\bjev\b""").containsMatchIn(short)) short else "Jev $short"
}

/** What happens after Jev answers ([LinkPolicy.select]). */
sealed interface JevPick {
    /** The answering model makes the call with only [name]. */
    data class Tool(val name: String) : JevPick
    /** Jev says no tool fits. */
    data object NoTool : JevPick
    /** As without Jev: [reason] in a few words, for the chat, and [detail] at length. */
    data class Fallback(val reason: String, val detail: String) : JevPick
}
