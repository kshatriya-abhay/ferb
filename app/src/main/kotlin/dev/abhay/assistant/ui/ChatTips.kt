package dev.abhay.assistant.ui

import dev.abhay.assistant.engine.Decision
import dev.abhay.assistant.onboarding.Tip
import dev.abhay.assistant.tools.Outcome
import dev.abhay.assistant.ui.AssistantViewModel.Exchange
import dev.abhay.assistant.ui.AssistantViewModel.Status

/** How many replies with no tool, or unsure, before Ferb suggests Ferb Link. */
internal const val SUGGEST_LINK_AFTER = 3

/** Needle 3 found no tool, or wasn't sure enough to run (not because the tool always asks or the request didn't name it). */
private fun Exchange.struggled() = reply.error == null && !reply.fromLaptop &&
    (calls.isEmpty() || (decision == Decision.Confirm && !alwaysConfirm && !unmentioned && !ungrounded))

/** Where a tip shows in the chat: under a reply ([exchange], its id), or by the input box (null). */
internal data class TipPlace(val tip: Tip, val exchange: Long?)

/**
 * The chat's tips whose thing is on screen, each at the newest reply that shows it: the replies
 * ([history], oldest first), the start screen's examples, the Phone/Computer toggle, the mic.
 * [Tip.next] then picks the one to show.
 */
internal fun chatTips(history: List<Exchange>, startScreen: Boolean, answererShown: Boolean, micShown: Boolean, linkOff: Boolean = false): List<TipPlace> {
    val newest = history.asReversed()
    fun at(tip: Tip, shows: (Exchange) -> Boolean) = newest.firstOrNull(shows)?.let { TipPlace(tip, it.id) }
    val done = { e: Exchange -> e.calls.mapNotNull { (it.status as? Status.Finished)?.outcome as? Outcome.Done } }
    return listOfNotNull(
        TipPlace(Tip.TryOne, null).takeIf { startScreen },
        newest.firstOrNull()?.let { TipPlace(Tip.Details, it.id) },
        at(Tip.AskFirst) { e -> e.calls.any { it.status == Status.Waiting } },
        at(Tip.Undo) { e -> done(e).any { it.actions.isNotEmpty() } },
        // After a request no tool could do, or a few requests in: there's more to turn on.
        TipPlace(Tip.MoreTools, null).takeIf { history.size >= 5 || history.any { it.calls.isEmpty() && it.reply.error == null } },
        TipPlace(Tip.Answerer, null).takeIf { answererShown },
        at(Tip.OnPhone) { it.laptopError != null },
        TipPlace(Tip.Mic, null).takeIf { micShown },
        at(Tip.Heard) { it.heard != null },
        // Ferb Link, once: when it's off and Needle 3 has found no tool, or been unsure, a few times.
        TipPlace(Tip.SuggestLink, null).takeIf { linkOff && history.count { it.struggled() } >= SUGGEST_LINK_AFTER },
    )
}
