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
import dev.abhay.assistant.pipeline.Talk
import dev.abhay.assistant.voice.GeminiSetup
import dev.abhay.assistant.voice.Part
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first launch's three pages (Compose, on the phone): in order, the recommended choice saved by
 * Next, Skip, Gemini Nano's setup on the Talk page (download, progress, failure, can't run), and
 * Hindi greyed out only when nothing here ever can hear it.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {
    @get:Rule val compose = createComposeRule()

    private val gemini = Abilities(geminiSpeech = true, geminiRewrite = true)
    private val saved = mutableListOf<Any>()
    private var done = 0

    private fun show(assistantHeld: Boolean = false, can: Abilities = gemini, setup: GeminiSetup = GeminiSetup(Part.Ready, Part.Ready)) = compose.setContent {
        OnboardingFlow(Talk.default(can), can, setup, assistantHeld, onTalk = { saved += it },
            onSetUpGemini = { saved += "set up" }, onOpenAssistantSettings = { saved += "assistant settings" }, onDone = { done++ })
    }

    @Test
    fun throughEveryPageWithTheRecommendedChoice() {
        show()
        compose.onNodeWithText(str(R.string.welcome_not_a_chatbot)).assertExists()
        compose.onNodeWithText(str(R.string.welcome_does_things_for_you)).assertExists()
        compose.onNodeWithText(str(PageText.START)).performClick()
        compose.onNodeWithText(str(PageText.TALK_TITLE)).assertExists()
        // The recommended choice says so, and where it's understood.
        compose.onNodeWithText("${str(RECOMMENDED)} · ${Talk.Hindi.hint} · ${Talk.Hindi.where(gemini)}").assertExists()
        compose.onNodeWithText(str(PageText.NEXT)).performClick()
        compose.onNodeWithText(str(PageText.ASSISTANT_TITLE)).assertExists()
        compose.onNodeWithText(str(PageText.OPEN_ASSISTANT_SETTINGS)).performClick()
        compose.onNodeWithText(str(PageText.DONE)).performClick()
        // Next saved the choice as shown, so a default can't change under the user later.
        assertThat(saved).containsExactly(Talk.Hindi, "assistant settings").inOrder()
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
    fun whereGeminiNanoCantRunItSaysSoAndHindiNeedsTheComputer() {
        show(can = Abilities(geminiSpeech = false, geminiRewrite = false, geminiHere = false), setup = GeminiSetup(Part.Unavailable, Part.Unavailable))
        compose.onNodeWithText(str(PageText.START)).performClick()
        compose.onNodeWithText(str(R.string.ui_gemini_cant_run)).assertExists()
        compose.onNodeWithText(Talk.HINDI_NEEDS_LINK).assertExists()
        compose.onNodeWithText(str(PageText.NEXT)).performClick()
        assertThat(saved).containsExactly(Talk.English)
    }

    @Test
    fun geminiNanoToDownloadIsSetUpOnThePage() {
        val toDownload = Abilities(geminiSpeech = false, geminiRewrite = false)
        show(can = toDownload, setup = GeminiSetup(Part.NeedsDownload, Part.NeedsDownload))
        compose.onNodeWithText(str(PageText.START)).performClick()
        compose.onNodeWithText(str(R.string.ui_gemini_offer)).assertExists()
        compose.onNodeWithText(str(PageText.SET_UP_GEMINI)).performScrollTo().performClick()
        assertThat(saved).contains("set up")
        // English + Hindi can still be picked: English until Gemini Nano is ready.
        compose.onNodeWithText("${Talk.Hindi.hint} · ${Talk.UNTIL_GEMINI}").performScrollTo().performClick()
        assertThat(saved).contains(Talk.Hindi)
    }

    @Test
    fun aDownloadShowsItsProgress() {
        show(can = Abilities(geminiSpeech = false, geminiRewrite = false), setup = GeminiSetup(Part.Downloading(30, 100), Part.Downloading(10, 100)))
        compose.onNodeWithText(str(PageText.START)).performClick()
        compose.onNodeWithText(str(R.string.ui_gemini_downloading_percent, 20)).assertExists()
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
    fun readyGeminiNanoSaysSo() {
        show()
        compose.onNodeWithText(str(PageText.START)).performClick()
        compose.onNodeWithText(str(R.string.ui_gemini_ready)).assertExists()
    }

    @Test
    fun backFromSettingsAsTheAssistantItSaysSo() {
        var held by mutableStateOf(false)
        compose.setContent {
            OnboardingFlow(Talk.Hindi, gemini, GeminiSetup(Part.Ready, Part.Ready), held, onTalk = { saved += it },
                onSetUpGemini = {}, onOpenAssistantSettings = { saved += "assistant settings" }, onDone = { done++ })
        }
        compose.onNodeWithText(str(PageText.START)).performClick()
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
