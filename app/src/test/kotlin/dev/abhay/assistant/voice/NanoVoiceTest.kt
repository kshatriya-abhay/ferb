package dev.abhay.assistant.voice

import com.google.common.truth.Truth.assertThat
import com.google.mlkit.genai.common.FeatureStatus
import org.junit.Test

/** Gemini Nano as the phone's voice (POC): the settings, its setup, its fallback, the live text and the rewrite. */
class NanoVoiceTest {
    @Test
    fun geminiNanosLanguageDefaultsToEnglish() {
        assertThat(GeminiLanguage.of(null)).isEqualTo(GeminiLanguage.English)
        assertThat(GeminiLanguage.of("hi-IN").locale.toLanguageTag()).isEqualTo("hi-IN")
    }

    @Test
    fun eachPartSaysWhereItStands() {
        assertThat(Part.of(FeatureStatus.AVAILABLE)).isEqualTo(Part.Ready)
        assertThat(Part.of(FeatureStatus.DOWNLOADABLE)).isEqualTo(Part.NeedsDownload)
        assertThat(Part.of(FeatureStatus.DOWNLOADING)).isEqualTo(Part.Downloading())
        assertThat(Part.of(FeatureStatus.UNAVAILABLE)).isEqualTo(Part.Unavailable)
        assertThat(Part.Downloading().label()).isEqualTo("Downloading…")
        assertThat(Part.Downloading(4_200_000, null).label()).isEqualTo("Downloading: 4.2 MB so far")
        assertThat(Part.Downloading(120_000_000, 480_000_000).label()).isEqualTo("Downloading: 120 of 480 MB")
        assertThat(Part.Downloading(120_000_000, 480_000_000).fraction).isEqualTo(0.25f)
        assertThat(Part.Downloading(5, 0).fraction).isNull()
        assertThat(Part.Failed("not enough space on the phone").label()).isEqualTo("Couldn't download: not enough space on the phone")
    }

    @Test
    fun theSettingsRowSumsTheSetupUp() {
        assertThat(GeminiSetup(Part.Ready, Part.Ready).summary()).isEqualTo("Ready")
        assertThat(GeminiSetup(Part.Ready, Part.Ready).ready).isTrue()
        assertThat(GeminiSetup(Part.Ready, Part.NeedsDownload).summary()).isEqualTo("Set up needed: tap to download")
        assertThat(GeminiSetup(Part.Ready, Part.NeedsDownload).needsSetUp).isTrue()
        assertThat(GeminiSetup(Part.Downloading(100, 400), Part.Downloading(100, 600)).summary()).isEqualTo("Downloading: 20%")
        // Android fetching it with no numbers yet: Set up follows it, for progress.
        assertThat(GeminiSetup(Part.Ready, Part.Downloading()).summary()).isEqualTo("Downloading…")
        assertThat(GeminiSetup(Part.Ready, Part.Downloading()).needsSetUp).isTrue()
        assertThat(GeminiSetup(Part.Ready, Part.Downloading(10, 100)).needsSetUp).isFalse()
        assertThat(GeminiSetup(Part.Unavailable, Part.Ready).summary()).isEqualTo("Not available on this phone")
        assertThat(GeminiSetup().summary()).isEqualTo("Checking…")
    }

    @Test
    fun whistleHearsItWhenGeminiCant() {
        assertThat(NanoChoice.of(FeatureStatus.AVAILABLE)).isEqualTo(NanoChoice.Nano)
        assertThat(NanoChoice.of(FeatureStatus.DOWNLOADABLE)).isEqualTo(NanoChoice.Whistle(NanoChoice.NOT_SET_UP))
        assertThat(NanoChoice.of(FeatureStatus.DOWNLOADING)).isEqualTo(NanoChoice.Whistle(NanoChoice.DOWNLOADING))
        assertThat(NanoChoice.of(FeatureStatus.UNAVAILABLE)).isEqualTo(NanoChoice.Whistle(NanoChoice.UNAVAILABLE))
        assertThat(NanoChoice.of(42)).isEqualTo(NanoChoice.Whistle(NanoChoice.UNAVAILABLE))
    }

    @Test
    fun partialResultsReplaceEachOtherAndFinalsAddUp() {
        val live = LiveTranscript()
        assertThat(live.partial("set an")).isEqualTo("set an")
        assertThat(live.partial("set an alarm")).isEqualTo("set an alarm")
        assertThat(live.final("Set an alarm for 7")).isEqualTo("Set an alarm for 7")
        assertThat(live.partial(" tomorrow ")).isEqualTo("Set an alarm for 7 tomorrow")
        assertThat(live.final("tomorrow.")).isEqualTo("Set an alarm for 7 tomorrow.")
        assertThat(live.final("  ")).isEqualTo("Set an alarm for 7 tomorrow.")
        assertThat(LiveTranscript().heard).isEmpty()
    }

    @Test
    fun theRewriteIsOneCommandOrNone() {
        assertThat(GeminiRewrite.parse("torch jala do", "Turn on the flashlight\n")).isEqualTo(Rewritten("torch jala do", "Turn on the flashlight"))
        // Quotes, an arrow or a label around it go; only the first line counts.
        assertThat(GeminiRewrite.parse("x", "x -> \"Call Priya\"\nSomething else").command).isEqualTo("Call Priya")
        assertThat(GeminiRewrite.parse("x", "Command: Set a timer for 5 minutes").command).isEqualTo("Set a timer for 5 minutes")
        assertThat(GeminiRewrite.parse("what is the capital of japan", "NONE").command).isNull()
        assertThat(GeminiRewrite.parse("x", " none.").command).isNull()
    }

    @Test
    fun aCommandCutOffAtTheTokenLimitFails() {
        val cut = runCatching { GeminiRewrite.parse("bhaiya ko message karo ki main late hoon", "Send a text to bhaiya: main", cut = true) }
        assertThat(cut.exceptionOrNull()?.message).isEqualTo(GeminiRewrite.CUT_OFF)
        // A cut in a later line, or NONE, is whole; and not cut, the same words are the command.
        assertThat(GeminiRewrite.parse("x", "Turn on the flashlight\nAnd then", cut = true).command).isEqualTo("Turn on the flashlight")
        assertThat(GeminiRewrite.parse("x", "NONE", cut = true).command).isNull()
        assertThat(GeminiRewrite.parse("x", "Send a text to bhaiya: main").command).isEqualTo("Send a text to bhaiya: main")
    }

    @Test(expected = IllegalArgumentException::class)
    fun anEmptyAnswerFails() {
        GeminiRewrite.parse("kal 7 baje", "  \n ")
    }

    @Test
    fun noPromptExampleIsABenchOrHeldOutLine() {
        // The bench (and the held-out set) must measure the prompt, not recall its examples.
        val bench = java.io.File("../tool-check").listFiles { f -> f.name.endsWith(".txt") }!!.flatMap { it.readLines() }
            .filter { "|" in it && !it.startsWith("#") }.map { it.substringAfter("|").trim().lowercase() }.toSet()
        assertThat(bench).isNotEmpty()
        for (prompt in listOf(GeminiRewrite.PROMPT_V1, GeminiRewrite.PROMPT_V2)) {
            val examples = prompt.substringAfter("Examples:\n").lines().map { it.substringBefore(" -> ").trim().lowercase() }
            assertThat(examples).isNotEmpty()
            assertThat(examples.filter { it in bench }).isEmpty()
        }
    }

    @Test
    fun thePromptsExamplesAreShortAndEndInNoneForQuestions() {
        assertThat(GeminiRewrite.PROMPT).contains("Reply with exactly one line: the command, or NONE.")
        assertThat(GeminiRewrite.PROMPT).contains("what is the capital of japan -> NONE")
        assertThat(GeminiRewrite.MAX_TOKENS).isAtMost(48)
    }
}
