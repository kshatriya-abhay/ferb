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
    fun eachModelStandsForItsSteps() {
        assertThat(ModelChoice.Whistle.steps).isEqualTo(Pipeline(AudioStep.Whistle, RewriteStep.Off, ToolStep.Off))
        assertThat(ModelChoice.Gemini.steps).isEqualTo(Pipeline(AudioStep.Gemini, RewriteStep.Gemini, ToolStep.Off))
        assertThat(ModelChoice.Gemma.steps).isEqualTo(Pipeline(AudioStep.Link, RewriteStep.Link, ToolStep.Off))
        // Jev is never a model's: only under Advanced.
        assertThat(ModelChoice.entries.map { it.steps.tools }).containsExactly(ToolStep.Off, ToolStep.Off, ToolStep.Off)
        assertThat(ModelChoice.TITLE).isEqualTo("Choose your preferred model")
        assertThat(ModelChoice.entries.map { it.title }).containsExactly("Needle + Whistle", "Gemini Nano", "Gemma 4 (via Ferb Link)").inOrder()
        assertThat(ModelChoice.entries.map { it.hint }).containsExactly("English-only, wide-compatibility",
            "on-device, multilingual, compatibility varies", "run it on your own server, multilingual").inOrder()
    }

    @Test
    fun geminiNanoUsesWhatsReadyAndSwitchesWhenItIs() {
        assertThat(ModelChoice.Gemini.resolve(gemini)).isEqualTo(Pipeline(AudioStep.Gemini, RewriteStep.Gemini, ToolStep.Off))
        // Still to download: Whistle and no rewrite meanwhile.
        assertThat(ModelChoice.Gemini.resolve(bare)).isEqualTo(Pipeline(AudioStep.Whistle, RewriteStep.Off, ToolStep.Off))
        assertThat(ModelChoice.Gemini.waitsForGemini(bare)).isTrue()
        assertThat(ModelChoice.Gemini.waitsForGemini(gemini)).isFalse()
        assertThat(ModelChoice.Gemini.reason(bare)).isNull()
        // Speech ready, the rewrite still downloading.
        assertThat(ModelChoice.Gemini.resolve(bare.copy(geminiSpeech = true))).isEqualTo(Pipeline(AudioStep.Gemini, RewriteStep.Off, ToolStep.Off))
        // AICore can't run it here: greyed out, and nothing to wait for.
        assertThat(ModelChoice.Gemini.reason(bare.copy(geminiHere = false))).isEqualTo(ModelChoice.GEMINI_CANT_RUN)
        assertThat(ModelChoice.Gemini.waitsForGemini(bare.copy(geminiHere = false))).isFalse()
        // The others don't wait for it.
        assertThat(ModelChoice.Whistle.resolve(gemini)).isEqualTo(ModelChoice.Whistle.steps)
        assertThat(ModelChoice.Gemma.waitsForGemini(bare)).isFalse()
    }

    @Test
    fun gemmaNeedsFerbLinkInstalledAndSaysWhenItIsntPaired() {
        val installed = bare.copy(linkApp = true)
        assertThat(ModelChoice.Gemma.reason(bare)).isEqualTo(ModelChoice.LINK_NOT_INSTALLED)
        assertThat(ModelChoice.Gemma.needsPairing(bare)).isFalse()
        // Installed, not paired: can be picked, says so; requests fall back one at a time meanwhile.
        assertThat(ModelChoice.Gemma.reason(installed)).isNull()
        assertThat(ModelChoice.Gemma.needsPairing(installed)).isTrue()
        assertThat(ModelChoice.Gemma.resolve(installed)).isEqualTo(Pipeline(AudioStep.Link, RewriteStep.Link, ToolStep.Off))
        assertThat(ModelChoice.Gemma.needsPairing(computer)).isFalse()
        assertThat(ModelChoice.Gemma.reason(computer)).isNull()
        // Paired means installed.
        assertThat(computer.linkInstalled).isTrue()
        assertThat(ModelChoice.Whistle.reason(bare.copy(geminiHere = false))).isNull()
        assertThat(ModelChoice.Whistle.needsPairing(installed)).isFalse()
    }

    @Test
    fun theDefaultIsGeminiNanoWhereItCanRun() {
        assertThat(ModelChoice.default(gemini)).isEqualTo(ModelChoice.Gemini)
        // Still to download counts: it can run here.
        assertThat(ModelChoice.default(bare)).isEqualTo(ModelChoice.Gemini)
        assertThat(ModelChoice.default(computer)).isEqualTo(ModelChoice.Gemini)
        assertThat(ModelChoice.default(bare.copy(geminiHere = false))).isEqualTo(ModelChoice.Whistle)
        assertThat(ModelChoice.default(computer.copy(geminiHere = false))).isEqualTo(ModelChoice.Whistle)
        assertThat(Pipeline.resolve(Saved(model = ModelChoice.Gemma), gemini).audio).isEqualTo(AudioStep.Link)
        assertThat(Pipeline.resolve(Saved(model = ModelChoice.Whistle), gemini)).isEqualTo(ModelChoice.Whistle.steps)
        assertThat(ModelChoice.of("gemma")).isEqualTo(ModelChoice.Gemma)
        assertThat(ModelChoice.of("hindi")).isNull()
    }

    @Test
    fun stepsThatAreExactlyAModelsBecomeIt() {
        assertThat(Pipeline.toModel(Saved())).isEqualTo(Saved())
        assertThat(Pipeline.toModel(Saved(AudioStep.Whistle, RewriteStep.Off, ToolStep.Off))).isEqualTo(Saved(model = ModelChoice.Whistle))
        assertThat(Pipeline.toModel(Saved(AudioStep.Gemini, RewriteStep.Gemini, ToolStep.Off))).isEqualTo(Saved(model = ModelChoice.Gemini))
        assertThat(Pipeline.toModel(Saved(AudioStep.Link, RewriteStep.Link, ToolStep.Off))).isEqualTo(Saved(model = ModelChoice.Gemma))
        // Not exactly one: stays under Advanced.
        assertThat(Pipeline.toModel(Saved(AudioStep.Link, RewriteStep.Link, ToolStep.Link))).isEqualTo(Saved(AudioStep.Link, RewriteStep.Link, ToolStep.Link))
        assertThat(Pipeline.toModel(Saved(AudioStep.Off, RewriteStep.Gemini, ToolStep.Off))).isEqualTo(Saved(AudioStep.Off, RewriteStep.Gemini, ToolStep.Off))
        assertThat(Pipeline.toModel(Saved(audio = AudioStep.Whistle))).isEqualTo(Saved(audio = AudioStep.Whistle))
        assertThat(Pipeline.toModel(Saved(model = ModelChoice.Gemma))).isEqualTo(Saved(model = ModelChoice.Gemma))
        assertThat(Saved(audio = AudioStep.Whistle).custom).isTrue()
        assertThat(Saved(model = ModelChoice.Whistle).custom).isFalse()
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
    fun whatCantEverWorkHereIsntOffered() {
        val notInstalled = Availability(linkReason = Availability.LINK_NOT_INSTALLED, geminiSpeech = false, geminiRewrite = true)
        assertThat(notInstalled.hidden(AudioStep.Link)).isTrue()
        assertThat(notInstalled.hidden(RewriteStep.Link)).isTrue()
        assertThat(notInstalled.hidden(ToolStep.Link)).isTrue()
        assertThat(notInstalled.hidden(AudioStep.Gemini)).isTrue()
        assertThat(notInstalled.hidden(RewriteStep.Gemini)).isFalse()
        assertThat(notInstalled.hidden(AudioStep.Whistle)).isFalse()
        // Installed but not paired: offered, greyed with why.
        assertThat(Availability(linkReason = Availability.LINK_NOT_PAIRED).hidden(AudioStep.Link)).isFalse()
        // Not checked yet: offered.
        assertThat(Availability().hidden(AudioStep.Gemini)).isFalse()
    }

    @Test
    fun aFailedStepSaysWhatWasUsed() {
        assertThat(AudioStep.Link.fallback).isEqualTo(AudioStep.Whistle)
        assertThat(AudioStep.Gemini.fallback).isNull()
        assertThat(Fallback("Rewrite", "Gemini Nano", "computer away").line).isEqualTo("Rewrite: Gemini Nano instead (computer away)")
        assertThat(RewriteStep.HINT).isEqualTo("Helps with Hindi and accuracy.")
        assertThat(ToolStep.HINT).isEqualTo("Narrows the tool choice.")
    }
}
