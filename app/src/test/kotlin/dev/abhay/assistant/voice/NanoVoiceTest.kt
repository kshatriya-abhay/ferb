package dev.abhay.assistant.voice

import com.google.common.truth.Truth.assertThat
import com.google.mlkit.genai.common.FeatureStatus
import org.junit.Test

/** Gemini Nano as the phone's ear (POC): the setting, its fallback to Whistle, and the live text. */
class NanoVoiceTest {
    @Test
    fun whistleIsTheDefaultAndNanoComesInTwoLanguages() {
        assertThat(PhoneEar.of(null)).isEqualTo(PhoneEar.Whistle)
        assertThat(PhoneEar.of("gone")).isEqualTo(PhoneEar.Whistle)
        assertThat(PhoneEar.of("nano-hi")).isEqualTo(PhoneEar.NanoHindi)
        assertThat(PhoneEar.NANO.map { it.locale!!.toLanguageTag() }).containsExactly("en-US", "hi-IN").inOrder()
        assertThat(PhoneEar.Whistle.nano).isFalse()
    }

    @Test
    fun whistleHearsItWhenNanoCant() {
        assertThat(NanoChoice.of(FeatureStatus.AVAILABLE)).isEqualTo(NanoChoice.Nano)
        // The model to fetch: Whistle meanwhile, and the download starts.
        assertThat(NanoChoice.of(FeatureStatus.DOWNLOADABLE)).isEqualTo(NanoChoice.Whistle(NanoChoice.DOWNLOADING, download = true))
        assertThat(NanoChoice.of(FeatureStatus.DOWNLOADING)).isEqualTo(NanoChoice.Whistle(NanoChoice.DOWNLOADING))
        assertThat(NanoChoice.of(FeatureStatus.UNAVAILABLE)).isEqualTo(NanoChoice.Whistle(NanoChoice.UNAVAILABLE))
        assertThat(NanoChoice.of(42)).isEqualTo(NanoChoice.Whistle(NanoChoice.UNAVAILABLE))
        assertThat(NanoChoice.name(FeatureStatus.DOWNLOADABLE)).isEqualTo("downloadable")
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
        assertThat(live.heard).isEqualTo("Set an alarm for 7 tomorrow.")
    }

    @Test
    fun whatWasTypedStaysFirst() {
        val live = LiveTranscript("remind me ")
        assertThat(live.text()).isEqualTo("remind me")
        assertThat(live.partial("to call mum")).isEqualTo("remind me to call mum")
        assertThat(live.heard).isEqualTo("to call mum")
        assertThat(LiveTranscript().heard).isEmpty()
    }
}
