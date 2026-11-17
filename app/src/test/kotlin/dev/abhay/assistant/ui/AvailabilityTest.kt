package dev.abhay.assistant.ui

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.link.LinkStatus
import dev.abhay.assistant.pipeline.Availability
import dev.abhay.assistant.pipeline.LinkAvailability
import dev.abhay.assistant.voice.GeminiSetup
import org.junit.Test

/** What the step options show for Ferb Link, from its status as last read. */
class AvailabilityTest {
    @Test
    fun beforeTheFirstReadFerbLinkIsBeingCheckedNotOff() {
        // Review P2-120: it started from Off, whose "Turn on" and error colours showed until the read.
        val checking = availability(null, null, GeminiSetup())
        assertThat(checking.link).isEqualTo(LinkAvailability.Unavailable(CHECKING))
        assertThat(checking.linkAudioReason).isEqualTo(CHECKING)
        // Read: as it is.
        assertThat(availability(LinkStatus.Ready(audio = true), true, GeminiSetup()).let { it.link to it.linkAudioReason })
            .isEqualTo(LinkAvailability.Ready to null)
        assertThat(availability(LinkStatus.Ready(), false, GeminiSetup()).linkAudioReason).isEqualTo(Availability.LINK_NO_AUDIO)
    }
}
