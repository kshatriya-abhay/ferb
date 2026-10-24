package dev.abhay.assistant

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBackUnconditionally
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.pipeline.AudioStep
import dev.abhay.assistant.ui.OPEN_CHAT
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
    private var audio: AudioStep? = null

    @Before
    fun noMic() {
        audio = app.pipeline.saved.value.audio
        app.pipeline.setAudio(AudioStep.Off)
    }

    @After
    fun restore() {
        // Back to what the user had (or to the default, when nothing was chosen).
        audio?.let(app.pipeline::setAudio) ?: app.getSharedPreferences("pipeline", android.content.Context.MODE_PRIVATE).edit().remove("audio").commit()
    }

    @Test
    fun theSheetOpensOverTheAppAndBackClosesIt() {
        val intent = Intent(Intent.ACTION_ASSIST).setClass(app, AssistActivity::class.java)
        ActivityScenario.launch<AssistActivity>(intent).use { scenario ->
            compose.onNodeWithText("Ferb").assertExists()
            compose.onNodeWithContentDescription(OPEN_CHAT).assertExists()
            compose.onNodeWithText("What would you like done?").assertExists()
            pressBackUnconditionally()
            compose.waitUntil(5_000) { scenario.state == Lifecycle.State.DESTROYED }
            assertThat(scenario.state).isEqualTo(Lifecycle.State.DESTROYED)
        }
    }
}
