package dev.abhay.assistant.voice

import dev.abhay.assistant.onboarding.Tip
import dev.abhay.assistant.pipeline.AudioStep

/** Whether the chat box shows the mic, and its tip. */
object Mic {
    /** With audio processing off (Settings → How Ferb understands you), there's no mic: requests are typed. */
    fun shows(audio: AudioStep): Boolean = audio != AudioStep.Off

    /** The mic's tip: who hears it, in which languages. */
    fun tip(audio: AudioStep): String = when (audio) {
        AudioStep.Whistle -> Tip.Mic.text
        AudioStep.Gemini -> GEMINI_TIP
        AudioStep.Link -> COMPUTER_TIP
        AudioStep.Off -> Tip.Mic.text
    }

    const val GEMINI_TIP = "Tap the mic and speak, English or Hindi; tap \u2713 to send. Heard on this phone by Gemini Nano."
    const val COMPUTER_TIP = "Tap the mic and speak, English or Hindi; tap \u2713 to send. Heard by your computer, through Ferb Link."
}
