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
    private fun exchange(id: Long, vararg calls: CallState, heard: String? = null, laptopError: String? = null) = Exchange(
        id, "request $id", reply(*calls.map { it.call }.toTypedArray()), Decision.Run, 0.5, false, false, false,
        emptyList(), calls.toList(), 100, heard = heard, laptopError = laptopError,
    )
    private val ready = Prepared.Ready("Turn on the flashlight") { Outcome.Done("On") }
    private val waiting = CallState(torch, ready, Status.Waiting)
    private val withUndo = CallState(torch, ready, Status.Finished(Outcome.Done("On", actions = listOf(FollowUp("Turn off") { Outcome.Done("Off") }))))

    private fun tips(history: List<Exchange>, start: Boolean = false, toggle: Boolean = false, mic: Boolean = false) =
        chatTips(history, start, toggle, mic)

    @Test
    fun theStartScreenSuggestsTryingAnExample() {
        assertThat(tips(emptyList(), start = true)).containsExactly(TipPlace(Tip.TryOne, null))
        assertThat(Tip.next(tips(emptyList(), start = true).map { it.tip }, emptySet())).isEqualTo(Tip.TryOne)
    }

    @Test
    fun repliesCarryTheirTipsAtTheNewestThatShowsThem() {
        val history = listOf(exchange(1, withUndo), exchange(2, waiting))
        val places = tips(history)
        assertThat(places).contains(TipPlace(Tip.Details, 2))
        assertThat(places).contains(TipPlace(Tip.AskFirst, 2))
        assertThat(places).contains(TipPlace(Tip.Undo, 1))
        // Details first, then asking first once Details is seen.
        assertThat(Tip.next(places.map { it.tip }, emptySet())).isEqualTo(Tip.Details)
        assertThat(Tip.next(places.map { it.tip }, setOf(Tip.Details))).isEqualTo(Tip.AskFirst)
    }

    @Test
    fun moreToolsAfterNoToolOrAFewRequests() {
        assertThat(tips(listOf(exchange(1, withUndo))).map { it.tip }).doesNotContain(Tip.MoreTools)
        assertThat(tips(listOf(exchange(1))).map { it.tip }).contains(Tip.MoreTools) // "I can't do that yet"
        assertThat(tips((1L..5L).map { exchange(it, withUndo) }).map { it.tip }).contains(Tip.MoreTools)
    }

    @Test
    fun ferbLinkTipsOnlyWithFerbLink() {
        assertThat(tips(emptyList()).map { it.tip }).containsNoneOf(Tip.Answerer, Tip.Mic, Tip.OnPhone, Tip.Heard)
        val history = listOf(exchange(1, withUndo, heard = "torch on karo"), exchange(2, withUndo, laptopError = "timed out"))
        val places = tips(history, toggle = true, mic = true)
        assertThat(places).containsAtLeast(TipPlace(Tip.Answerer, null), TipPlace(Tip.Mic, null), TipPlace(Tip.OnPhone, 2), TipPlace(Tip.Heard, 1))
    }

    @Test
    fun ferbLinkIsSuggestedAfterAFewRepliesWithNoToolWhileItsOff() {
        val noTool = (1L..3L).map { exchange(it) }
        assertThat(chatTips(noTool, false, false, false, linkOff = true).map { it.tip }).contains(Tip.SuggestLink)
        // Not while Ferb Link is in use, nor before a few such replies, nor after replies that worked.
        assertThat(chatTips(noTool, false, false, false, linkOff = false).map { it.tip }).doesNotContain(Tip.SuggestLink)
        assertThat(chatTips(noTool.take(2), false, false, false, linkOff = true).map { it.tip }).doesNotContain(Tip.SuggestLink)
        assertThat(chatTips((1L..5L).map { exchange(it, withUndo) }, false, false, false, linkOff = true).map { it.tip }).doesNotContain(Tip.SuggestLink)
    }
}
