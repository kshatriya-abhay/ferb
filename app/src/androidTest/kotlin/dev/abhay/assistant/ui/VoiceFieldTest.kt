package dev.abhay.assistant.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The recording's ✕ and ✓ (Compose, on the phone): ✓ stops and sends, ✕ discards, the live words show. */
@RunWith(AndroidJUnit4::class)
class VoiceFieldTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theTickSendsAndTheCrossCancels() {
        var finished = 0
        var discarded = 0
        compose.setContent {
            VoiceField(AssistantViewModel.Voice.Recording(0, onPhone = true, live = true, heard = "set an alarm"),
                onFinish = { finished++ }, onDiscard = { discarded++ }, onSend = {})
        }
        compose.onNodeWithText("set an alarm").assertIsDisplayed()
        compose.onNodeWithContentDescription(str(FINISH)).performClick()
        assertThat(finished).isEqualTo(1)
        compose.onNodeWithContentDescription(str(CANCEL)).performClick()
        assertThat(discarded).isEqualTo(1)
    }

    @Test
    fun silentAfterSpeechTheTickShowsItsSendingRing() {
        compose.setContent {
            VoiceField(AssistantViewModel.Voice.Recording(0, onPhone = true, sendingIn = 0.5f), onFinish = {}, onDiscard = {}, onSend = {})
        }
        compose.onNodeWithContentDescription(str(SENDING)).assertIsDisplayed()
    }

    @Test
    fun whileSpeakingThereIsNoRing() {
        compose.setContent {
            VoiceField(AssistantViewModel.Voice.Recording(0, onPhone = true), onFinish = {}, onDiscard = {}, onSend = {})
        }
        compose.onNodeWithContentDescription(str(SENDING)).assertDoesNotExist()
    }

    @Test
    fun aWaitingRecordingOffersSendAgain() {
        var sent = 0
        compose.setContent {
            VoiceField(AssistantViewModel.Voice.Heard("call mum", 900), onFinish = {}, onDiscard = {}, onSend = { sent++ })
        }
        compose.onNodeWithContentDescription("Send").performClick()
        assertThat(sent).isEqualTo(1)
    }
}
