package dev.abhay.assistant.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.pipeline.AudioStep
import dev.abhay.assistant.pipeline.Availability
import dev.abhay.assistant.voice.GeminiSetup
import dev.abhay.assistant.voice.Part
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A step's options in Settings (Compose, on the phone): the one in use, Ferb Link greyed with why, Gemini Nano's settings button. */
@RunWith(AndroidJUnit4::class)
class StepOptionsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun ferbLinkIsGreyedWithWhyAndGeminiNanoOpensItsPage() {
        var picked: AudioStep? = null
        var opened = 0
        compose.setContent {
            StepOptions(AudioOptions, AudioStep.Gemini, { picked = it },
                Availability(linkReason = Availability.LINK_NOT_PAIRED, geminiSpeech = true, geminiRewrite = true),
                GeminiSetup(Part.Ready, Part.Ready), onOpenGemini = { opened++ })
        }
        compose.onNode(hasText(AudioStep.Gemini.title)).assertIsSelected()
        // Ferb Link can't be picked yet, and says why.
        compose.onNodeWithText(Availability.LINK_NOT_PAIRED).assertExists()
        compose.onNode(hasText(AudioStep.Link.title)).assertIsNotEnabled()
        // Whistle can, and the row picks it.
        compose.onNode(hasText(AudioStep.Whistle.title)).assertIsEnabled().performClick()
        assertThat(picked).isEqualTo(AudioStep.Whistle)
        // Gemini Nano's own button opens its page; the row only picks it.
        compose.onNodeWithContentDescription(GEMINI_SETTINGS).performClick()
        assertThat(opened).isEqualTo(1)
    }
}
