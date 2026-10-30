package dev.abhay.assistant.ui

import dev.abhay.assistant.engine.Decision
import dev.abhay.assistant.onboarding.Tip
import dev.abhay.assistant.tools.Outcome
import dev.abhay.assistant.ui.AssistantViewModel.Exchange
import dev.abhay.assistant.ui.AssistantViewModel.Status

/** How many replies with no tool, or unsure, before Ferb suggests Ferb Link. */
internal const val SUGGEST_LINK_AFTER = 3

/** Needle 3 found no tool, or wasn't sure enough to run (not because the tool always asks or the request didn't name it). */
private fun Exchange.struggled() = reply.error == null && !nothingAsked &&
    (calls.isEmpty() || (decision == Decision.Confirm && !alwaysConfirm && !unmentioned && !ungrounded))

/** Where a tip shows in the chat: under a reply ([exchange], its id), or by the input box (null). */
internal data class TipPlace(val tip: Tip, val exchange: Long?)

/**
 * The chat's tips whose thing is on screen, each at the newest reply that shows it: the replies
 * ([history], oldest first), the start screen's examples, the mic. [Tip.next] then picks the one
 * to show. [linkOff]: Ferb Link isn't installed and paired.
 */
internal fun chatTips(history: List<Exchange>, startScreen: Boolean, micShown: Boolean, linkOff: Boolean = false, micTip: Boolean = micShown): List<TipPlace> {
    val newest = history.asReversed()
    fun at(tip: Tip, shows: (Exchange) -> Boolean) = newest.firstOrNull(shows)?.let { TipPlace(tip, it.id) }
    val done = { e: Exchange -> e.calls.mapNotNull { (it.status as? Status.Finished)?.outcome as? Outcome.Done } }
    return listOfNotNull(
        newest.firstOrNull()?.let { TipPlace(Tip.Details, it.id) },
        at(Tip.Fallback) { it.fallbacks.isNotEmpty() },
        TipPlace(Tip.Mic, null).takeIf { micShown && micTip },
        // Ferb Link, once: when it's off and Needle 3 has found no tool, or been unsure, a few times.
        TipPlace(Tip.SuggestLink, null).takeIf { linkOff && history.count { it.struggled() } >= SUGGEST_LINK_AFTER },
    )
}
