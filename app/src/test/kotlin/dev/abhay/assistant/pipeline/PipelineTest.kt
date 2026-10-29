package dev.abhay.assistant.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The three steps: their defaults, the carry-over from the old settings, and what each option needs. */
class PipelineTest {
    private val gemini = Abilities(geminiSpeech = true, geminiRewrite = true)
    private val bare = Abilities(geminiSpeech = false, geminiRewrite = false)
    private val computer = bare.copy(link = true, linkAudio = true)

    @Test
    fun theDefaultsFollowWhatGeminiNanoCanDoHere() {
        assertThat(Pipeline.resolve(Saved(), gemini)).isEqualTo(Pipeline(AudioStep.Gemini, RewriteStep.Gemini, ToolStep.Off))
        assertThat(Pipeline.resolve(Saved(), bare)).isEqualTo(Pipeline(AudioStep.Whistle, RewriteStep.Off, ToolStep.Off))
        // Before AICore has answered: the phone-only fallbacks.
        assertThat(Pipeline.resolve(Saved(), Abilities())).isEqualTo(Pipeline(AudioStep.Whistle, RewriteStep.Off, ToolStep.Off))
        // What the user chose under Advanced stays chosen.
        assertThat(Pipeline.resolve(Saved(AudioStep.Off, RewriteStep.Link, ToolStep.Link), gemini))
            .isEqualTo(Pipeline(AudioStep.Off, RewriteStep.Link, ToolStep.Link))
        // One step chosen, the rest the defaults.
        assertThat(Pipeline.resolve(Saved(tools = ToolStep.Link), gemini)).isEqualTo(Pipeline(AudioStep.Gemini, RewriteStep.Gemini, ToolStep.Link))
    }

    @Test
    fun eachTalkChoiceWorksWithWhateverIsHere() {
        // Gemini Nano here: everything stays on the phone.
        assertThat(Talk.English.steps(gemini)).isEqualTo(Pipeline(AudioStep.Gemini, RewriteStep.Gemini, ToolStep.Off))
        assertThat(Talk.Hindi.steps(gemini)).isEqualTo(Pipeline(AudioStep.Gemini, RewriteStep.Gemini, ToolStep.Off))
        assertThat(Talk.Typing.steps(gemini)).isEqualTo(Pipeline(AudioStep.Off, RewriteStep.Gemini, ToolStep.Off))
        // No Gemini Nano, Ferb Link paired and hearing: Hindi through the computer; English stays on the phone.
        assertThat(Talk.Hindi.steps(computer)).isEqualTo(Pipeline(AudioStep.Link, RewriteStep.Link, ToolStep.Off))
        assertThat(Talk.English.steps(computer)).isEqualTo(Pipeline(AudioStep.Whistle, RewriteStep.Off, ToolStep.Off))
        assertThat(Talk.Typing.steps(computer)).isEqualTo(Pipeline(AudioStep.Off, RewriteStep.Link, ToolStep.Off))
        // Gemini's rewrite but not its speech, computer paired: hears there, rewrites here.
        assertThat(Talk.Hindi.steps(computer.copy(geminiRewrite = true))).isEqualTo(Pipeline(AudioStep.Link, RewriteStep.Gemini, ToolStep.Off))
        // Neither: English and typing work, Hindi can't be picked, and says why.
        assertThat(Talk.English.steps(bare)).isEqualTo(Pipeline(AudioStep.Whistle, RewriteStep.Off, ToolStep.Off))
        assertThat(Talk.Hindi.reason(bare)).isEqualTo(Talk.HINDI_NEEDS)
        assertThat(Talk.English.reason(bare)).isNull()
        assertThat(Talk.Typing.reason(bare)).isNull()
        assertThat(Talk.Hindi.reason(computer)).isNull()
        // Paired, but the computer's model doesn't hear: still no Hindi voice.
        assertThat(Talk.Hindi.reason(bare.copy(link = true))).isEqualTo(Talk.HINDI_NEEDS)
        // Not checked yet: not held back.
        assertThat(Talk.Hindi.reason(Abilities())).isNull()
        // The default: Hindi when something hears it.
        assertThat(Talk.default(gemini)).isEqualTo(Talk.Hindi)
        assertThat(Talk.default(computer)).isEqualTo(Talk.Hindi)
        assertThat(Talk.default(bare)).isEqualTo(Talk.English)
        assertThat(Pipeline.resolve(Saved(talk = Talk.Typing), gemini).audio).isEqualTo(AudioStep.Off)
    }

    @Test
    fun stepsAChoiceGivesAnywayBecomeIt() {
        assertThat(Pipeline.toTalk(Saved())).isEqualTo(Saved())
        assertThat(Pipeline.toTalk(Saved(AudioStep.Gemini, RewriteStep.Gemini, ToolStep.Off))).isEqualTo(Saved())
        assertThat(Pipeline.toTalk(Saved(audio = AudioStep.Gemini))).isEqualTo(Saved())
        assertThat(Pipeline.toTalk(Saved(audio = AudioStep.Off))).isEqualTo(Saved(talk = Talk.Typing))
        // Anything a choice wouldn't give stays under Advanced.
        assertThat(Pipeline.toTalk(Saved(tools = ToolStep.Link))).isEqualTo(Saved(tools = ToolStep.Link))
        assertThat(Pipeline.toTalk(Saved(AudioStep.Link, RewriteStep.Link))).isEqualTo(Saved(AudioStep.Link, RewriteStep.Link))
        assertThat(Pipeline.toTalk(Saved(audio = AudioStep.Whistle))).isEqualTo(Saved(audio = AudioStep.Whistle))
        assertThat(Saved(audio = AudioStep.Whistle).custom).isTrue()
        assertThat(Saved(talk = Talk.English).custom).isFalse()
    }

    @Test
    fun theOldSettingsCarryOver() {
        assertThat(Pipeline.migrate(Pipeline.Old())).isEqualTo(Saved())
        assertThat(Pipeline.migrate(Pipeline.Old(voiceOn = false))).isEqualTo(Saved(audio = AudioStep.Off))
        assertThat(Pipeline.migrate(Pipeline.Old(ear = "gemini"))).isEqualTo(Saved(audio = AudioStep.Gemini))
        assertThat(Pipeline.migrate(Pipeline.Old(ear = "nano-hi"))).isEqualTo(Saved(audio = AudioStep.Gemini))
        assertThat(Pipeline.migrate(Pipeline.Old(ear = "whistle"))).isEqualTo(Saved())
        // Computer picked in the chat: Gemma heard and rewrote there.
        val computer = Pipeline.Old(linkMode = "option", answerer = "link")
        assertThat(Pipeline.migrate(computer.copy(jev = true))).isEqualTo(Saved(AudioStep.Link, RewriteStep.Link, ToolStep.Link))
        // With voice on this phone off the mic still showed for Computer, and Gemma heard.
        assertThat(Pipeline.migrate(computer.copy(voiceOn = false)).audio).isEqualTo(AudioStep.Link)
        // Phone picked: Jev as it was, the rest the defaults.
        assertThat(Pipeline.migrate(Pipeline.Old(linkMode = "option", answerer = "needle", jev = true))).isEqualTo(Saved(tools = ToolStep.Link))
        // Ferb Link off: Jev wasn't used, so it isn't now (requests stay on the phone).
        assertThat(Pipeline.migrate(Pipeline.Old(linkMode = "off", answerer = "link", jev = true))).isEqualTo(Saved())
        assertThat(Pipeline.migrate(Pipeline.Old(jev = true))).isEqualTo(Saved())
        // The removed "When Needle 3 is unsure" meant Phone picked, whatever the answerer said.
        assertThat(Pipeline.migrate(Pipeline.Old(linkMode = "fallback", answerer = "link", jev = true))).isEqualTo(Saved(tools = ToolStep.Link))
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
