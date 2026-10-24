package dev.abhay.assistant.voice

import android.content.Context
import dev.abhay.assistant.onboarding.Tip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Use voice to text on-device" (Settings → Voice), on by default, and who writes it down
 * ([PhoneEar]: Whistle, or Gemini Nano in a [GeminiLanguage]). Off: neither is loaded, and the mic
 * shows only when the computer hears the request ([Mic.shows]).
 */
class VoiceSettings(context: Context) {
    private val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)

    private val _whistle = MutableStateFlow(prefs.getBoolean(KEY_WHISTLE, true))
    val whistle: StateFlow<Boolean> = _whistle.asStateFlow()

    fun setWhistle(on: Boolean) {
        prefs.edit().putBoolean(KEY_WHISTLE, on).apply()
        _whistle.value = on
    }

    /** Who hears it with voice on this phone on: Whistle (the default) or Gemini Nano (POC). */
    private val _ear = MutableStateFlow(PhoneEar.of(prefs.getString(KEY_EAR, null)))
    val ear: StateFlow<PhoneEar> = _ear.asStateFlow()

    fun setEar(ear: PhoneEar) {
        prefs.edit().putString(KEY_EAR, ear.id).apply()
        _ear.value = ear
    }

    /** The language Gemini Nano expects (English, the default, hears Hindi too). "nano-hi" was Hindi in the first POC build. */
    private val _language = MutableStateFlow(GeminiLanguage.of(prefs.getString(KEY_LANGUAGE, null)
        ?: GeminiLanguage.Hindi.id.takeIf { prefs.getString(KEY_EAR, null) == "nano-hi" }))
    val language: StateFlow<GeminiLanguage> = _language.asStateFlow()

    fun setLanguage(language: GeminiLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.id).apply()
        _language.value = language
    }

    companion object {
        private const val KEY_WHISTLE = "whistle"
        private const val KEY_EAR = "ear"
        private const val KEY_LANGUAGE = "gemini_language"
        const val TITLE = "Use voice to text on-device"
        const val DESCRIPTION = "Speak requests, written down on this phone. Computer mode can still listen."
    }
}

/** Whether the chat box shows the mic. */
object Mic {
    /**
     * The computer hears it when it's picked and takes audio (Gemma transcribes there); anywhere
     * else (Phone picked, Ferb Link off) Whistle would, so only with it on.
     */
    fun shows(computerHears: Boolean, whistleOn: Boolean): Boolean = computerHears || whistleOn

    /** The mic's tip: with Whistle off, the mic shows only for Computer, so only the computer hears. */
    fun tip(whistleOn: Boolean): String = if (whistleOn) Tip.Mic.text else COMPUTER_TIP

    const val COMPUTER_TIP = "Tap the mic, tap again to stop, then send. Heard by your computer; English or Hindi."
}
