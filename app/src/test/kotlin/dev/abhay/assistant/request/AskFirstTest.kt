package dev.abhay.assistant.request

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
import dev.abhay.assistant.tools.AskFirst
import dev.abhay.assistant.tools.SetAlarm
import dev.abhay.assistant.tools.ToolConfig
import dev.abhay.assistant.voice.Rewritten
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Review P2-121: why a reply asks first, as the Details sheet says it. */
class AskFirstTest {
    private val enabled = listOf(SetAlarm, AddReminder)
    private val configs = enabled.associate { it.name to ToolConfig.defaults(it) }
    private val now = ZonedDateTime.of(2026, 10, 8, 10, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
    private val link = object : LinkCalls {
        override fun status() = LinkStatus.NotInstalled
        override suspend fun rewrite(request: String, instructions: String, maxTokens: Int, timeoutMs: Int, jsonSchema: String?) = RewriteReply.failed("unused", "")
        override suspend fun decide(request: String, toolsJson: String, timeoutMs: Int) = JevReply.failed("unused", "")
    }

    @Test
    fun aDayAskOnARequestWhoseNumbersWereAllSaidIsNotCalledUngrounded() {
        val said = "set an alarm for tomorrow night at 2"
        val needle = NeedleReply(calls = listOf(ToolCall(SetAlarm.name, mapOf("time" to "2:00"))), suppressed = emptyList(),
            reasoning = "", confidence = 0.95, prefillTps = 0.0, decodeTps = 0.0, error = null)
        val got = runBlocking {
            AnswerFlow(link, needle = { _, _ -> needle }, gemini = { Rewritten(it, null) }, now = { now }, content = dev.abhay.assistant.tools.ContentMapTest.icu())
                .answer(said, enabled, configs, Pipeline(AudioStep.Whistle, RewriteStep.Off, ToolStep.Off), null, geminiUsable = true, linkStatus = LinkStatus.NotInstalled)
        }
        // Ferb asks which day (the user's rule for a night hour after a day word), so the call asks first.
        assertThat(got.shown.calls.single().time?.ask).isNotNull()
        assertThat(got.verdict.decision).isEqualTo(Decision.Confirm)
        assertThat(got.verdict.alwaysConfirm).isFalse()
        assertThat(got.verdict.unmentioned).isFalse()
        // Details said "Asked first: you didn't say these numbers."; the user said "2". It asks which night.
        assertThat(got.verdict.ungrounded).isFalse()
        assertThat(got.verdict.asks).isInstanceOf(AskFirst.Time::class.java)
    }
}
