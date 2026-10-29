package dev.abhay.assistant

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.pipeline.AudioStep
import dev.abhay.assistant.ui.OPEN_CHAT
import dev.abhay.assistant.ui.SHEET_BAR
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The assistant's sheet (on the phone; run only when the phone is free): ACTION_ASSIST opens it
 * over whatever is in front, with the box ready to type (audio processing off for the test, so
 * nothing is recorded; the user's own choice is put back after), and back dismisses it.
 */
@RunWith(AndroidJUnit4::class)
class AssistSheetDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AssistantApp
    private var saved: dev.abhay.assistant.pipeline.Saved? = null

    @Before
    fun noMic() {
        saved = app.pipeline.saved.value
        app.pipeline.setAudio(AudioStep.Off)
    }

    @After
    fun restore() {
        // Back to what the user had: Talk to Ferb in, or the steps under Advanced.
        saved?.let(app.pipeline::restore)
    }

    @Test
    fun theSheetOpensOverTheAppAndBackClosesIt() {
        val intent = Intent(Intent.ACTION_ASSIST).setClass(app, AssistActivity::class.java)
        ActivityScenario.launch<AssistActivity>(intent).use { scenario ->
            compose.onNodeWithText("Ferb").assertExists()
            compose.onNodeWithContentDescription(OPEN_CHAT).assertExists()
            compose.onNodeWithText("What would you like done?").assertExists()
            // The box is on screen straight away, with no drag: whole, inside the window
            // (2026-10-24: at half height it was below the fold).
            compose.waitForIdle()
            val bar = compose.onNodeWithTag(SHEET_BAR).assertIsDisplayed().fetchSemanticsNode().boundsInWindow
            var window = 0
            scenario.onActivity { window = it.window.decorView.height }
            assertThat(bar.top).isAtLeast(0f)
            assertThat(bar.bottom).isAtMost(window.toFloat())
            compose.onNodeWithText("What would you like done?").assertIsDisplayed()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(5_000) { scenario.state == Lifecycle.State.DESTROYED }
            assertThat(scenario.state).isEqualTo(Lifecycle.State.DESTROYED)
        }
    }

    /**
     * The sheet recording, then out of sight (another screen in front): its recording goes, so no
     * mic is left on behind the chat (the review fix of 2026-10-24). Whistle hears it, so the
     * phone's own recorder is the one that must stop; nothing is sent.
     */
    @Test
    fun aRecordingGoesWhenTheSheetIsOutOfSight() {
        app.pipeline.setAudio(AudioStep.Whistle)
        val intent = Intent(Intent.ACTION_ASSIST).setClass(app, AssistActivity::class.java)
        ActivityScenario.launch<AssistActivity>(intent).use { scenario ->
            // Opened as the assistant, it starts listening at once.
            compose.waitUntil(5_000) { app.chat.value.voice is dev.abhay.assistant.ui.AssistantViewModel.Voice.Recording }
            scenario.moveToState(Lifecycle.State.CREATED)
            compose.waitUntil(5_000) { app.chat.value.voice == null }
            assertThat(app.chat.value.busy).isFalse()
        }
    }
}
