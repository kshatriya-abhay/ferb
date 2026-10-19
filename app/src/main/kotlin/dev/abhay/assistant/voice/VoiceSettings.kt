package dev.abhay.assistant.voice

import android.content.Context
import dev.abhay.assistant.onboarding.Tip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Voice on this phone (Whistle)" (Settings), on by default. Off: Whistle is never loaded, and the
 * mic shows only when the computer hears the request ([Mic.shows]).
 */
class VoiceSettings(context: Context) {
    private val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)

    private val _whistle = MutableStateFlow(prefs.getBoolean(KEY_WHISTLE, true))
    val whistle: StateFlow<Boolean> = _whistle.asStateFlow()

    fun setWhistle(on: Boolean) {
        prefs.edit().putBoolean(KEY_WHISTLE, on).apply()
        _whistle.value = on
    }

    companion object {
        private const val KEY_WHISTLE = "whistle"
        const val TITLE = "Voice on this phone (Whistle)"
        const val DESCRIPTION = "Speak requests in English, heard on this phone. Computer mode can still listen."
    }
}

/** Whether the chat box shows the mic. */
object Mic {
    /**
     * The computer hears it when it's picked and takes audio (Gemma transcribes there); anywhere
     * else (Phone picked, Ferb Link off, "When Needle 3 is unsure") Whistle would, so only with it on.
     */
    fun shows(computerHears: Boolean, whistleOn: Boolean): Boolean = computerHears || whistleOn

    /** The mic's tip: with Whistle off, the mic shows only for Computer, so only the computer hears. */
    fun tip(whistleOn: Boolean): String = if (whistleOn) Tip.Mic.text else COMPUTER_TIP

    const val COMPUTER_TIP = "Tap the mic, tap again to stop, then send. Heard by your computer; English or Hindi."
}
