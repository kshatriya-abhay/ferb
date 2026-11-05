package dev.abhay.assistant.voice

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import com.google.mlkit.genai.common.GenAiException
import dev.abhay.assistant.R
import dev.abhay.assistant.pipeline.AudioStep

/**
 * Battery Saver stops Gemini Nano's speech recognition: on the user's Pixel (2026-11-05) it failed
 * with "ERROR_TYPE_AICORE_NOT_ENABLED_RUNTIME_LIMITS" while Battery Saver was on, in the smoke and
 * release builds. Ferb warns while it's on and voice uses Gemini Nano, and says so in place of
 * that error. Only voice is claimed: the text rewrite wasn't seen to fail.
 */
object BatterySaver {
    /** The warning shows while Battery Saver is on and Gemini Nano hears spoken requests. */
    fun warns(powerSave: Boolean, audio: AudioStep): Boolean = powerSave && audio == AudioStep.Gemini

    /** Whether [e] (or a cause) is AICore refusing for battery limits: Battery Saver's runtime limits, or the per-app battery quota. */
    fun isBatteryLimit(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(8).any {
        (it as? GenAiException)?.errorCode == GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED ||
            it.message.orEmpty().contains("RUNTIME_LIMITS")
    }

    /**
     * What to say for a recognition error that's a battery limit: that Battery Saver is on (and
     * stops it till turned off), or, with it off, that Android's battery limits stopped it; null
     * for any other error, which is shown as it is.
     */
    fun notice(e: Throwable, powerSave: Boolean): Int? = when {
        !isBatteryLimit(e) -> null
        powerSave -> R.string.battery_saver_voice
        else -> R.string.battery_limits_voice
    }

    fun isOn(context: Context): Boolean = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true

    /** Battery Saver's page in Android's settings, or the battery page where a phone has none. */
    fun openSettings(context: Context) {
        for (action in listOf(Settings.ACTION_BATTERY_SAVER_SETTINGS, Intent.ACTION_POWER_USAGE_SUMMARY, Settings.ACTION_SETTINGS)) {
            try {
                return context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
            }
        }
    }
}
