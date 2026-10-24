package dev.abhay.assistant.ui

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.engine.Decision
import dev.abhay.assistant.engine.NeedleReply
import dev.abhay.assistant.engine.ToolCall
import dev.abhay.assistant.link.JevReply
import dev.abhay.assistant.link.LinkCalls
import dev.abhay.assistant.link.LinkStatus
import dev.abhay.assistant.link.RewriteReply
import dev.abhay.assistant.pipeline.AudioStep
import dev.abhay.assistant.pipeline.Pipeline
import dev.abhay.assistant.pipeline.RewriteStep
import dev.abhay.assistant.pipeline.ToolStep
import dev.abhay.assistant.tools.AddReminder
import dev.abhay.assistant.tools.SetAlarm
import dev.abhay.assistant.tools.SetTimer
import dev.abhay.assistant.tools.TimeArgs
import dev.abhay.assistant.tools.ToolConfig
import dev.abhay.assistant.voice.Rewritten
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Timed calls (2026-10-25): Ferb's own time from the words as said and the rewrite, agreeing (DateGate), gates E and H. */
class AnswerFlowDatesTest {
    private val enabled = listOf(SetAlarm, AddReminder, SetTimer)
    private val configs = enabled.associate { it.name to ToolConfig.defaults(it) }
    /** Thursday 8 October 2026, 10:00. */
    private val now = ZonedDateTime.of(2026, 10, 8, 10, 0, 0, 0, ZoneId.of("Asia/Kolkata"))

    private val link = object : LinkCalls {
        override fun status() = LinkStatus.NotSetUp
        override suspend fun rewrite(request: String, instructions: String, maxTokens: Int, timeoutMs: Int) = RewriteReply.failed("unused", "")
        override suspend fun decide(request: String, toolsJson: String, timeoutMs: Int) = JevReply.failed("unused", "")
    }

    private fun reply(confidence: Double, calls: List<ToolCall> = emptyList(), held: List<ToolCall> = emptyList()) = NeedleReply(
        calls = calls, suppressed = held, reasoning = "", confidence = confidence, ungrounded = emptyList(),
        negation = false, prefillTps = 0.0, decodeTps = 0.0, peakRamMb = 0.0, error = null,
    )

    private fun answer(said: String, command: String?, needle: NeedleReply): Answer = runBlocking {
        val steps = Pipeline(AudioStep.Whistle, if (command != null) RewriteStep.Gemini else RewriteStep.Off, ToolStep.Off)
        AnswerFlow(link, needle = { _, _ -> needle }, gemini = { Rewritten(it, command) }, now = { now })
            .answer(said, enabled, configs, steps, null, geminiUsable = true)
    }

    private fun remind(time: String) = ToolCall(AddReminder.name, mapOf("text" to "call the bank", "time" to time))

    @Test
    fun agreeingReadingsRunWhateverNeedlesConfidence() {
        val got = answer("kal subah 9 baje bank ko call karna yaad dilana", "Remind me tomorrow at 9 am to call the bank", reply(0.12, listOf(remind("tomorrow"))))
        assertThat(got.verdict.decision).isEqualTo(Decision.Run)
        val args = got.shown.calls.single().arguments
        assertThat(args[TimeArgs.AT]).isEqualTo("2026-10-09T09:00")
        assertThat(args[TimeArgs.ASK]).isNull()
        assertThat(Step.chain(got.path)).endsWith("Dates: Ferb (Fri 9 Oct, 9:00)")
    }

    @Test
    fun needlesOwnTimeNeverWins() {
        // Needle 3 says 3 pm; the words and the rewrite say 9 am tomorrow.
        val got = answer("remind me tomorrow at 9 am to call the bank", "Remind me tomorrow at 9 am to call the bank", reply(0.9, listOf(remind("3 pm"))))
        assertThat(got.shown.calls.single().arguments[TimeArgs.AT]).isEqualTo("2026-10-09T09:00")
    }

    @Test
    fun disagreeingReadingsAskShowingWhatFerbRead() {
        // The rewrite lost the day: tomorrow in the words, today in the command.
        val got = answer("kal 6 baje yaad dilana bank", "Remind me at 6 pm to call the bank", reply(0.9, listOf(remind("6 pm"))))
        assertThat(got.verdict.decision).isEqualTo(Decision.Confirm)
        val args = got.shown.calls.single().arguments
        assertThat(args[TimeArgs.AT]).isEqualTo("2026-10-09T18:00")
        assertThat(args[TimeArgs.ASK] as String).contains("Fri 9 Oct, 18:00")
    }

    @Test
    fun aHeldBackTimedCallTheRouterNamesAloneComesBack() {
        val got = answer("remind me tomorrow at 9 am to call the bank", null, reply(0.05, held = listOf(remind("tomorrow 9"))))
        assertThat(got.shown.calls.map { it.name }).containsExactly(AddReminder.name)
        assertThat(got.verdict.decision).isEqualTo(Decision.Run)
    }

    @Test
    fun aRewriteThatChangesTheToolAsks() {
        // "remind me" said; the rewrite made an alarm of it (gate H).
        val alarm = ToolCall(SetAlarm.name, mapOf("time" to "7:00"))
        val got = answer("remind me tomorrow at 7 am to go running", "Set an alarm for 7 am tomorrow", reply(0.9, listOf(alarm)))
        assertThat(got.verdict.decision).isEqualTo(Decision.Confirm)
    }

    @Test
    fun timersTakeTheLengthFromTheWords() {
        val got = answer("das minute ka timer laga do", "Set a timer for 10 minutes", reply(0.9, listOf(ToolCall(SetTimer.name, mapOf("duration" to "1 minute")))))
        assertThat(got.shown.calls.single().arguments["duration"]).isEqualTo("600 seconds")
        assertThat(got.verdict.decision).isEqualTo(Decision.Run)
    }
}
