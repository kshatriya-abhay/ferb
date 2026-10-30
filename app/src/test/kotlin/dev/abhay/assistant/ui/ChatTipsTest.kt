package dev.abhay.assistant.ui

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.engine.Decision
import dev.abhay.assistant.engine.NeedleReply
import dev.abhay.assistant.engine.ToolCall
import dev.abhay.assistant.onboarding.Tip
import dev.abhay.assistant.tools.FollowUp
import dev.abhay.assistant.tools.Outcome
import dev.abhay.assistant.tools.Prepared
import dev.abhay.assistant.ui.AssistantViewModel.CallState
import dev.abhay.assistant.ui.AssistantViewModel.Exchange
import dev.abhay.assistant.ui.AssistantViewModel.Status
import org.junit.Test

/** Which tips the chat has on screen, and under which reply. */
class ChatTipsTest {
    private val torch = ToolCall("set_flashlight", mapOf("on" to true))
    private fun reply(vararg calls: ToolCall) = NeedleReply(
        calls = calls.toList(), suppressed = emptyList(), reasoning = "", confidence = 0.9, ungrounded = emptyList(),
        negation = false, prefillTps = 0.0, decodeTps = 0.0, peakRamMb = 0.0, error = null,
    )
    private fun exchange(id: Long, vararg calls: CallState, heard: String? = null, fellBack: Boolean = false, nothingAsked: Boolean = false) = Exchange(
        id, "request $id", reply(*calls.map { it.call }.toTypedArray()), Decision.Run, 0.5, false, false, false,
        emptyList(), calls.toList(), 100, heard = heard, nothingAsked = nothingAsked,
        fallbacks = if (fellBack) listOf(dev.abhay.assistant.pipeline.Fallback("Rewrite", "Gemini Nano", "Ferb Link: timed out")) else emptyList(),
    )
    private val ready = Prepared.Ready("Turn on the flashlight") { Outcome.Done("On") }
    private val waiting = CallState(torch, ready, Status.Waiting)
    private val withUndo = CallState(torch, ready, Status.Finished(Outcome.Done("On", actions = listOf(FollowUp("Turn off") { Outcome.Done("Off") }))))

    private fun tips(history: List<Exchange>, start: Boolean = false, mic: Boolean = false) =
        chatTips(history, start, mic)

    @Test
    fun theStartScreenSuggestsTryingAnExample() {
        // The start screen explains nothing: its examples speak for themselves.
        assertThat(tips(emptyList(), start = true)).isEmpty()
    }

    @Test
    fun repliesCarryTheirTipsAtTheNewestThatShowsThem() {
        val history = listOf(exchange(1, withUndo), exchange(2, waiting))
        val places = tips(history)
        // Details, at the newest reply; nothing explains asking first or undo (the buttons say it).
        assertThat(places).containsExactly(TipPlace(Tip.Details, 2))
        assertThat(Tip.next(places.map { it.tip }, emptySet())).isEqualTo(Tip.Details)
    }

    @Test
    fun voiceAndFallbackTipsOnlyWhenTheirThingShows() {
        assertThat(tips(emptyList()).map { it.tip }).containsNoneOf(Tip.Mic, Tip.Fallback)
        val history = listOf(exchange(1, withUndo, heard = "torch on karo"), exchange(2, withUndo, fellBack = true))
        val places = tips(history, mic = true)
        assertThat(places).containsAtLeast(TipPlace(Tip.Mic, null), TipPlace(Tip.Fallback, 2))
    }

    @Test
    fun nothingAskedIsNotStruggling() {
        // NONE from the rewrite is an answer, not Needle 3 failing: no Ferb Link suggestion for it.
        val none = (1L..3L).map { exchange(it, nothingAsked = true) }
        assertThat(chatTips(none, false, false, linkOff = true).map { it.tip }).doesNotContain(Tip.SuggestLink)
    }

    @Test
    fun ferbLinkIsSuggestedAfterAFewRepliesWithNoToolWhileItsOff() {
        val noTool = (1L..3L).map { exchange(it) }
        assertThat(chatTips(noTool, false, false, linkOff = true).map { it.tip }).contains(Tip.SuggestLink)
        // Not while Ferb Link is in use, nor before a few such replies, nor after replies that worked.
        assertThat(chatTips(noTool, false, false, linkOff = false).map { it.tip }).doesNotContain(Tip.SuggestLink)
        assertThat(chatTips(noTool.take(2), false, false, linkOff = true).map { it.tip }).doesNotContain(Tip.SuggestLink)
        assertThat(chatTips((1L..5L).map { exchange(it, withUndo) }, false, false, linkOff = true).map { it.tip }).doesNotContain(Tip.SuggestLink)
    }
}
