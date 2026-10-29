package dev.abhay.assistant.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.pipeline.Abilities
import dev.abhay.assistant.pipeline.Talk
import dev.abhay.assistant.voice.GeminiSetup
import dev.abhay.assistant.voice.Part
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The first launch's three pages (Compose, on the phone): in order, the recommended choice saved by Next, Skip, and Hindi greyed out when nothing hears it. */
@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {
    @get:Rule val compose = createComposeRule()

    private val gemini = Abilities(geminiSpeech = true, geminiRewrite = true)
    private val saved = mutableListOf<Any>()
    private var done = 0

    private fun show(assistantHeld: Boolean = false, can: Abilities = gemini, setup: GeminiSetup = GeminiSetup(Part.Ready, Part.Ready)) = compose.setContent {
        OnboardingFlow(Talk.default(can), can, setup, assistantHeld, onTalk = { saved += it },
            onSetUpGemini = { saved += "set up" }, onOpenDefaultApps = { saved += "default apps" }, onDone = { done++ })
    }

    @Test
    fun throughEveryPageWithTheRecommendedChoice() {
        show()
        compose.onNodeWithText("Ferb does things on your phone").assertExists()
        compose.onNodeWithText(PageText.START).performClick()
        compose.onNodeWithText(PageText.TALK_TITLE).assertExists()
        // The recommended choice says so, and where it's understood.
        compose.onNodeWithText("$RECOMMENDED · ${Talk.Hindi.hint} · ${Talk.Hindi.where(gemini)}").assertExists()
        compose.onNodeWithText(PageText.NEXT).performClick()
        compose.onNodeWithText(PageText.ASSISTANT_TITLE).assertExists()
        compose.onNodeWithText(PageText.OPEN_DEFAULT_APPS).performClick()
        compose.onNodeWithText(PageText.DONE).performClick()
        // Next saved the choice as shown, so a default can't change under the user later.
        assertThat(saved).containsExactly(Talk.Hindi, "default apps").inOrder()
        assertThat(done).isEqualTo(1)
    }

    @Test
    fun skipEndsItAndBackGoesBack() {
        show(assistantHeld = true)
        compose.onNodeWithText(PageText.START).performClick()
        compose.onNodeWithText(PageText.BACK).performClick()
        compose.onNodeWithText("Ferb does things on your phone").assertExists()
        compose.onNodeWithText(PageText.SKIP).performClick()
        assertThat(done).isEqualTo(1)
        assertThat(saved).isEmpty()
    }

    @Test
    fun withNothingThatHearsHindiItSaysWhyAndEnglishIsPicked() {
        show(can = Abilities(geminiSpeech = false, geminiRewrite = false), setup = GeminiSetup(Part.Unavailable, Part.Unavailable))
        compose.onNodeWithText(PageText.START).performClick()
        compose.onNodeWithText(Talk.HINDI_NEEDS).assertExists()
        compose.onNodeWithText(PageText.NEXT).performClick()
        assertThat(saved).containsExactly(Talk.English)
    }

    @Test
    fun geminiNanoToDownloadOffersItsSetUp() {
        show(setup = GeminiSetup(Part.Ready, Part.NeedsDownload))
        compose.onNodeWithText(PageText.START).performClick()
        compose.onNodeWithText(PageText.SET_UP_GEMINI).performScrollTo().performClick()
        assertThat(saved).contains("set up")
    }
}
