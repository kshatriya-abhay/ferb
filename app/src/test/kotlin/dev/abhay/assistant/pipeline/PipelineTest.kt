package dev.abhay.assistant.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The three steps: their defaults, the carry-over from the old settings, and what each option needs. */
class PipelineTest {
    @Test
    fun theDefaultsFollowWhatGeminiNanoCanDoHere() {
        assertThat(Pipeline.resolve(Saved(), geminiSpeech = true, geminiRewrite = true))
            .isEqualTo(Pipeline(AudioStep.Gemini, RewriteStep.Gemini, ToolStep.Off))
        assertThat(Pipeline.resolve(Saved(), geminiSpeech = false, geminiRewrite = false))
            .isEqualTo(Pipeline(AudioStep.Whistle, RewriteStep.Off, ToolStep.Off))
        // Before AICore has answered: the phone-only fallbacks.
        assertThat(Pipeline.resolve(Saved(), null, null)).isEqualTo(Pipeline(AudioStep.Whistle, RewriteStep.Off, ToolStep.Off))
        // What the user chose stays chosen.
        assertThat(Pipeline.resolve(Saved(AudioStep.Off, RewriteStep.Link, ToolStep.Link), geminiSpeech = true, geminiRewrite = true))
            .isEqualTo(Pipeline(AudioStep.Off, RewriteStep.Link, ToolStep.Link))
    }

    @Test
    fun theOldSettingsCarryOver() {
        assertThat(Pipeline.migrate(Pipeline.Old())).isEqualTo(Saved())
        assertThat(Pipeline.migrate(Pipeline.Old(voiceOn = false))).isEqualTo(Saved(audio = AudioStep.Off))
        assertThat(Pipeline.migrate(Pipeline.Old(ear = "gemini"))).isEqualTo(Saved(audio = AudioStep.Gemini))
        assertThat(Pipeline.migrate(Pipeline.Old(ear = "nano-hi"))).isEqualTo(Saved(audio = AudioStep.Gemini))
        assertThat(Pipeline.migrate(Pipeline.Old(ear = "whistle"))).isEqualTo(Saved())
        // Computer picked in the chat: Gemma heard and rewrote there.
        assertThat(Pipeline.migrate(Pipeline.Old(linkChosen = true, jev = true)))
            .isEqualTo(Saved(AudioStep.Link, RewriteStep.Link, ToolStep.Link))
        // Voice off wins over Computer picked.
        assertThat(Pipeline.migrate(Pipeline.Old(voiceOn = false, linkChosen = true)).audio).isEqualTo(AudioStep.Off)
    }

    @Test
    fun ferbLinksOptionsNeedItAndGeminisNeedAiCore() {
        val noLink = Availability(linkReason = Availability.LINK_NOT_PAIRED, geminiSpeech = true, geminiRewrite = false)
        assertThat(noLink.reason(AudioStep.Link)).isEqualTo(Availability.LINK_NOT_PAIRED)
        assertThat(noLink.reason(RewriteStep.Link)).isEqualTo(Availability.LINK_NOT_PAIRED)
        assertThat(noLink.reason(ToolStep.Link)).isEqualTo(Availability.LINK_NOT_PAIRED)
        assertThat(noLink.reason(AudioStep.Gemini)).isNull()
        assertThat(noLink.reason(RewriteStep.Gemini)).isEqualTo(Availability.GEMINI_UNAVAILABLE)
        assertThat(noLink.reason(AudioStep.Whistle)).isNull()
        assertThat(noLink.reason(AudioStep.Off)).isNull()
        // Not checked yet: not held back.
        assertThat(Availability().reason(RewriteStep.Gemini)).isNull()
    }

    @Test
    fun aFailedStepSaysWhatWasUsed() {
        assertThat(AudioStep.Link.fallback).isEqualTo(AudioStep.Whistle)
        assertThat(AudioStep.Gemini.fallback).isNull()
        assertThat(Fallback("Rewrite", "Gemini Nano", "computer away").line).isEqualTo("Rewrite: Gemini Nano instead (computer away)")
        assertThat(RewriteStep.HINT).isEqualTo("Helps with other languages and improves accuracy.")
        assertThat(ToolStep.HINT).isEqualTo("Improves accuracy by narrowing the tool choice.")
    }
}
