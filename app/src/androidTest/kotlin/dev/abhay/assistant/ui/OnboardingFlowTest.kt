package dev.abhay.assistant.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.pipeline.AudioStep
import dev.abhay.assistant.pipeline.Availability
import dev.abhay.assistant.pipeline.Pipeline
import dev.abhay.assistant.pipeline.RewriteStep
import dev.abhay.assistant.pipeline.ToolStep
import dev.abhay.assistant.voice.GeminiSetup
import dev.abhay.assistant.voice.Part
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The first launch's pages (Compose, on the phone): in order, each step's recommended option saved by Next, and Skip. */
@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {
    @get:Rule val compose = createComposeRule()

    private val recommended = Pipeline(AudioStep.Gemini, RewriteStep.Gemini, ToolStep.Off)
    private val saved = mutableListOf<Any>()
    private var done = 0

    private fun show(assistantHeld: Boolean = false, gemini: GeminiSetup = GeminiSetup(Part.Ready, Part.Ready)) = compose.setContent {
        OnboardingFlow(recommended, recommended, Availability(linkReason = Availability.LINK_NOT_INSTALLED, geminiSpeech = true, geminiRewrite = true),
            gemini, assistantHeld, onAudio = { saved += it }, onRewrite = { saved += it }, onTools = { saved += it },
            onSetUpGemini = { saved += "set up" }, onOpenDefaultApps = { saved += "default apps" }, onDone = { done++ })
    }

    @Test
    fun throughEveryPageWithTheRecommendedSteps() {
        show()
        compose.onNodeWithText("Ferb does things on your phone").assertExists()
        compose.onNodeWithText("Start").performClick()
        compose.onNodeWithText(PageText.AUDIO_TITLE).assertExists()
        // The recommended option says so; Ferb Link says why it can't be picked.
        compose.onNodeWithText("$RECOMMENDED · ${AudioStep.Gemini.hint} · Ready").assertExists()
        compose.onNodeWithText(PageText.NEXT).performClick()
        compose.onNodeWithText(PageText.REWRITE_TITLE).assertExists()
        compose.onNodeWithText(PageText.NEXT).performClick()
        compose.onNodeWithText(PageText.TOOLS_TITLE).assertExists()
        compose.onNodeWithText(PageText.NEXT).performClick()
        compose.onNodeWithText(PageText.ASSISTANT_TITLE).assertExists()
        compose.onNodeWithText(PageText.OPEN_DEFAULT_APPS).performClick()
        compose.onNodeWithText(PageText.DONE).performClick()
        // Next saved each step as shown, so a default can't change under the user later.
        assertThat(saved).containsExactly(AudioStep.Gemini, RewriteStep.Gemini, ToolStep.Off, "default apps").inOrder()
        assertThat(done).isEqualTo(1)
    }

    @Test
    fun skipEndsItAndBackGoesBack() {
        show(assistantHeld = true)
        compose.onNodeWithText("Start").performClick()
        compose.onNodeWithText(PageText.BACK).performClick()
        compose.onNodeWithText("Ferb does things on your phone").assertExists()
        compose.onNodeWithText(PageText.SKIP).performClick()
        assertThat(done).isEqualTo(1)
        assertThat(saved).isEmpty()
    }

    @Test
    fun geminiNanoToDownloadOffersItsSetUp() {
        show(gemini = GeminiSetup(Part.Ready, Part.NeedsDownload))
        compose.onNodeWithText("Start").performClick()
        compose.onNodeWithText(PageText.NEXT).performClick()
        compose.onNodeWithText(PageText.SET_UP_GEMINI).performScrollTo().performClick()
        assertThat(saved).contains("set up")
    }
}
