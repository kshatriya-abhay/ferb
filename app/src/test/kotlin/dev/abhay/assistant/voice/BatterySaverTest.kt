package dev.abhay.assistant.voice

import com.google.common.truth.Truth.assertThat
import com.google.mlkit.genai.common.GenAiException
import dev.abhay.assistant.R
import dev.abhay.assistant.pipeline.AudioStep
import org.junit.Test

/** Battery Saver stops Gemini Nano's speech recognition: when Ferb warns, and what it says for that error. */
class BatterySaverTest {
    /** As seen on the user's Pixel with Battery Saver on (2026-11-05). */
    private val runtimeLimits = IllegalStateException("Speech recognition engine is closed due to internal error: ERROR_TYPE_AICORE_NOT_ENABLED_RUNTIME_LIMITS")

    @Test
    fun warnsOnlyWithBatterySaverOnAndGeminiNanoHearing() {
        assertThat(BatterySaver.warns(powerSave = true, AudioStep.Gemini)).isTrue()
        assertThat(BatterySaver.warns(powerSave = false, AudioStep.Gemini)).isFalse()
        for (other in AudioStep.entries - AudioStep.Gemini) assertThat(BatterySaver.warns(powerSave = true, other)).isFalse()
    }

    @Test
    fun runtimeLimitsIsABatteryLimit() {
        assertThat(BatterySaver.isBatteryLimit(runtimeLimits)).isTrue()
        // Wrapped, as ML Kit may hand it on.
        assertThat(BatterySaver.isBatteryLimit(GenAiException("recognition failed", runtimeLimits, GenAiException.ErrorCode.UNKNOWN))).isTrue()
    }

    @Test
    fun thePerAppBatteryQuotaIsABatteryLimit() {
        assertThat(BatterySaver.isBatteryLimit(GenAiException("quota", null, GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED))).isTrue()
    }

    @Test
    fun otherErrorsAreNot() {
        assertThat(BatterySaver.isBatteryLimit(GenAiException("busy", null, GenAiException.ErrorCode.BUSY))).isFalse()
        assertThat(BatterySaver.isBatteryLimit(GenAiException("background", null, GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED))).isFalse()
        assertThat(BatterySaver.isBatteryLimit(IllegalStateException("closed"))).isFalse()
        assertThat(BatterySaver.notice(IllegalStateException("closed"), powerSave = true)).isNull()
    }

    @Test
    fun theNoticeSaysBatterySaverOnlyWhenItIsOn() {
        assertThat(BatterySaver.notice(runtimeLimits, powerSave = true)).isEqualTo(R.string.battery_saver_voice)
        assertThat(BatterySaver.notice(runtimeLimits, powerSave = false)).isEqualTo(R.string.battery_limits_voice)
    }
}
