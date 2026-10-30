package dev.abhay.assistant.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.R
import dev.abhay.assistant.pipeline.Abilities
import dev.abhay.assistant.pipeline.ModelChoice
import dev.abhay.assistant.voice.GeminiSetup
import dev.abhay.assistant.voice.GeminiWatch
import dev.abhay.assistant.voice.Part
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first launch's four pages (Compose, on the phone): in order, the recommended model saved by
 * Next, the tools page's way to Settings → Tools (and back to it), Skip, Gemini Nano's setup under it on the model page (download, progress, failure, can't
 * run), and Gemma 4 greyed out without Ferb Link, or asking to pair it.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {
    @get:Rule val compose = createComposeRule()

    private val gemini = Abilities(geminiSpeech = true, geminiRewrite = true)
    private val saved = mutableListOf<Any>()
    private var done = 0

    private fun show(assistantHeld: Boolean = false, can: Abilities = gemini, setup: GeminiSetup = GeminiSetup(Part.Ready, Part.Ready),
                     watch: GeminiWatch = GeminiWatch.Off, model: ModelChoice? = ModelChoice.default(can)) = compose.setContent {
        OnboardingFlow(model, can, setup, assistantHeld, onModel = { saved += it },
            onSetUpGemini = { saved += "set up" }, onOpenAssistantSettings = { saved += "assistant settings" },
            onCheckGemini = { saved += "check" }, onDone = { done++ }, geminiWatch = watch,
            onKeepModel = { saved += "kept ${it.id}" }, onOpenLink = { saved += "open link" }, onOpenTools = { saved += "tools" })
    }

    @Test
    fun throughEveryPageWithTheRecommendedChoice() {
        show()
        compose.onNodeWithText(str(R.string.welcome_not_a_chatbot)).assertExists()
        compose.onNodeWithText(str(R.string.welcome_does_things_for_you)).assertExists()
        compose.onNodeWithText(str(PageText.START)).performClick()
        compose.onNodeWithText(str(PageText.MODEL_TITLE)).assertExists()
        // The recommended model says so: Gemini Nano, where it can run.
        compose.onNodeWithText("${str(RECOMMENDED)} · ${ModelChoice.Gemini.hint}").assertExists()
        compose.onNodeWithText(ModelChoice.Whistle.hint).assertExists()
        compose.onNodeWithText(ModelChoice.Gemma.title).assertExists()
        compose.onNodeWithText(str(PageText.NEXT)).performClick()
        compose.onNodeWithText(str(PageText.TOOLS_TITLE)).assertExists()
        compose.onNodeWithText(str(PageText.TOOLS_BODY)).assertExists()
        compose.onNodeWithText(str(PageText.OPEN_TOOLS)).performClick()
        compose.onNodeWithText(str(PageText.NEXT)).performClick()
        compose.onNodeWithText(str(PageText.ASSISTANT_TITLE)).assertExists()
        compose.onNodeWithText(str(PageText.OPEN_ASSISTANT_SETTINGS)).performClick()
        compose.onNodeWithText(str(PageText.DONE)).performClick()
        // Next saved the choice as shown (no download started), so a default can't change under the user later.
        assertThat(saved).containsExactly("kept gemini", "tools", "assistant settings").inOrder()
        assertThat(done).isEqualTo(1)
    }

    @Test
    fun skipEndsItAndBackGoesBack() {
        show(assistantHeld = true)
        compose.onNodeWithText(str(PageText.START)).performClick()
        compose.onNodeWithText(str(PageText.BACK)).performClick()
        compose.onNodeWithText(str(R.string.welcome_not_a_chatbot)).assertExists()
        compose.onNodeWithText(str(R.string.welcome_does_things_for_you)).assertExists()
        compose.onNodeWithText(str(PageText.SKIP)).performClick()
        assertThat(done).isEqualTo(1)
        assertThat(saved).isEmpty()
    }

    @Test
    fun whereGeminiNanoCantRunItsGreyedOutAndNeedleWhistleIsRecommended() {
        show(can = Abilities(geminiSpeech = false, geminiRewrite = false, geminiHere = false), setup = GeminiSetup(Part.Unavailable, Part.Unavailable))
        compose.onNodeWithText(str(PageText.START)).performClick()
        compose.onNodeWithText(ModelChoice.GEMINI_CANT_RUN).assertExists()
        compose.onNodeWithText("${str(RECOMMENDED)} · ${ModelChoice.Whistle.hint}").assertExists()
        // Greyed out: a tap does nothing.
        compose.onNodeWithText(ModelChoice.GEMINI_CANT_RUN).performClick()
        compose.onNodeWithText(str(PageText.NEXT)).performClick()
        assertThat(saved).containsExactly("kept needle_whistle")
    }

    @Test
    fun gemmaNeedsFerbLinkAndAsksToPairIt() {
        show(can = gemini)
        compose.onNodeWithText(str(PageText.START)).performClick()
        compose.onNodeWithText(ModelChoice.LINK_NOT_INSTALLED).assertExists()
        compose.onNodeWithText(ModelChoice.LINK_NOT_INSTALLED).performClick()
        assertThat(saved).isEmpty()
    }

    @Test
    fun gemmaPickedWithFerbLinkNotPairedSaysSoWithAButton() {
        show(can = gemini.copy(linkApp = true), model = ModelChoice.Gemma)
        compose.onNodeWithText(str(PageText.START)).performClick()
        compose.onNodeWithText(ModelChoice.PAIR_LINK).assertExists()
        compose.onNodeWithText(str(OPEN_FERB_LINK)).performScrollTo().performClick()
        assertThat(saved).containsExactly("open link")
        // Gemini Nano's setup belongs to Gemini Nano: not shown under Gemma 4.
        compose.onNodeWithText(str(R.string.ui_gemini_ready)).assertDoesNotExist()
    }

    @Test
    fun geminiNanoToDownloadIsSetUpOnThePage() {
        val toDownload = Abilities(geminiSpeech = false, geminiRewrite = false)
        show(can = toDownload, setup = GeminiSetup(Part.NeedsDownload, Part.NeedsDownload))
        compose.onNodeWithText(str(PageText.START)).performClick()
        compose.onNodeWithText(str(R.string.ui_gemini_offer)).assertExists()
        compose.onNodeWithText(str(PageText.SET_UP_GEMINI)).performScrollTo().performClick()
        assertThat(saved).contains("set up")
        // Picking it (a tap) saves it; Whistle hears until it's ready.
        compose.onNodeWithText("${str(RECOMMENDED)} · ${ModelChoice.Gemini.hint}").performScrollTo().performClick()
        assertThat(saved).contains(ModelChoice.Gemini)
    }

    @Test
    fun stillNotReadyAfterWaitingOffersTryAgain() {
        show(can = Abilities(geminiSpeech = false, geminiRewrite = false), setup = GeminiSetup(Part.Downloading, Part.Downloading), watch = GeminiWatch.GaveUp)
        compose.onNodeWithText(str(PageText.START)).performClick()
        compose.onNodeWithText(str(R.string.ui_gemini_still_downloading)).assertExists()
        compose.onNodeWithText(str(R.string.ui_try_again)).performScrollTo().performClick()
        assertThat(saved).contains("check")
    }

    @Test
    fun settingsSaysReadyOnlyWhenItBecameReadyWhileFerbWaited() {
        var watch by mutableStateOf(GeminiWatch.Off)
        compose.setContent { GeminiSetUp(GeminiSetup(Part.Ready, Part.Ready), onSetUp = {}, onCheckAgain = {}, watch = watch) }
        compose.onNodeWithText(str(R.string.ui_gemini_ready)).assertDoesNotExist()
        watch = GeminiWatch.Ready
        compose.onNodeWithText(str(R.string.ui_gemini_ready)).assertExists()
    }

    @Test
    fun aDownloadShowsNoPercentage() {
        show(can = Abilities(geminiSpeech = false, geminiRewrite = false), setup = GeminiSetup(Part.Downloading, Part.Downloading), watch = GeminiWatch.Watching)
        compose.onNodeWithText(str(PageText.START)).performClick()
        compose.onNodeWithText(str(R.string.ui_gemini_downloading)).assertExists()
        compose.onNodeWithText("%", substring = true).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.ui_gemini_meanwhile)).assertExists()
    }

    @Test
    fun aFailedDownloadSaysWhyAndCanBeTriedAgain() {
        show(can = Abilities(geminiSpeech = false, geminiRewrite = false), setup = GeminiSetup(Part.Failed("not enough space on the phone"), Part.NeedsDownload))
        compose.onNodeWithText(str(PageText.START)).performClick()
        compose.onNodeWithText(str(R.string.ui_gemini_failed, "not enough space on the phone")).assertExists()
        compose.onNodeWithText(str(R.string.ui_try_again)).performScrollTo().performClick()
        assertThat(saved).contains("set up")
    }

    @Test
    fun aStatusCheckThatThrewSaysCouldntCheckNotCantRun() {
        show(can = Abilities(geminiSpeech = false, geminiRewrite = false), setup = GeminiSetup(Part.Unchecked("NullPointerException"), Part.Unchecked("NullPointerException")))
        compose.onNodeWithText(str(PageText.START)).performClick()
        compose.onNodeWithText(str(R.string.ui_gemini_unchecked, "NullPointerException")).assertExists()
        compose.onNodeWithText(str(R.string.ui_gemini_cant_run)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.ui_try_again)).performScrollTo().performClick()
        assertThat(saved).contains("check")
    }

    @Test
    fun readyGeminiNanoSaysSo() {
        show()
        compose.onNodeWithText(str(PageText.START)).performClick()
        compose.onNodeWithText(str(R.string.ui_gemini_ready)).assertExists()
    }

    @Test
    fun backFromSettingsAsTheAssistantItSaysSo() {
        var held by mutableStateOf(false)
        compose.setContent {
            OnboardingFlow(ModelChoice.Gemini, gemini, GeminiSetup(Part.Ready, Part.Ready), held, onModel = { saved += it },
                onSetUpGemini = {}, onOpenAssistantSettings = { saved += "assistant settings" }, onDone = { done++ })
        }
        compose.onNodeWithText(str(PageText.START)).performClick()
        compose.onNodeWithText(str(PageText.NEXT)).performClick()
        compose.onNodeWithText(str(PageText.NEXT)).performClick()
        compose.onNodeWithText(str(PageText.OPEN_ASSISTANT_SETTINGS)).performClick()
        compose.onNodeWithText(str(PageText.COME_BACK)).assertExists()
        // Picked in Settings; back in Ferb, the page stays and says so.
        held = true
        compose.onNodeWithText(str(PageText.IS_ASSISTANT)).assertExists()
        compose.onNodeWithText(str(PageText.OPEN_ASSISTANT_SETTINGS)).assertDoesNotExist()
        compose.onNodeWithText(str(PageText.DONE)).performClick()
        assertThat(done).isEqualTo(1)
    }

    @Test
    fun backFromSettingsToolsIsTheToolsPageAgain() {
        // Settings → Tools replaces the pages on screen; the page is held outside, as Onboarding() does.
        val page = mutableStateOf(dev.abhay.assistant.onboarding.Page.Welcome)
        var away by mutableStateOf(false)
        compose.setContent {
            if (!away) OnboardingFlow(ModelChoice.Gemini, gemini, GeminiSetup(Part.Ready, Part.Ready), false, onModel = {},
                onSetUpGemini = {}, onOpenAssistantSettings = {}, onDone = { done++ }, onOpenTools = { away = true }, pageState = page)
        }
        compose.onNodeWithText(str(PageText.START)).performClick()
        compose.onNodeWithText(str(PageText.NEXT)).performClick()
        compose.onNodeWithText(str(PageText.OPEN_TOOLS)).performClick()
        compose.onNodeWithText(str(PageText.TOOLS_TITLE)).assertDoesNotExist()
        away = false
        compose.onNodeWithText(str(PageText.TOOLS_TITLE)).assertExists()
        compose.onNodeWithText(str(PageText.NEXT)).performClick()
        compose.onNodeWithText(str(PageText.ASSISTANT_TITLE)).assertExists()
    }

    @Test
    fun theAssistantSettingIsTheFirstPageTried() {
        val intents = assistantSettingsIntents()
        assertThat(intents.map { it.action }).containsExactly(
            android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS,
            android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
            android.provider.Settings.ACTION_SETTINGS,
        ).inOrder()
        // This phone opens one of them.
        val packages = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        assertThat(intents.any { packages.resolveActivity(it, 0) != null }).isTrue()
    }
}
