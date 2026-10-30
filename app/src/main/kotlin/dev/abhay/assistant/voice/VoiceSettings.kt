package dev.abhay.assistant.voice

import dev.abhay.assistant.onboarding.Tip
import dev.abhay.assistant.pipeline.AudioStep

/** Whether the chat box shows the mic, and its tip. */
object Mic {
    /** With audio processing off (Settings → How Ferb understands you), there's no mic: requests are typed. */
    fun shows(audio: AudioStep): Boolean = audio != AudioStep.Off

    /** The mic's tip: how to use it, and the languages it hears (Whistle: English only). */
    fun tip(audio: AudioStep): String = when (audio) {
        AudioStep.Gemini, AudioStep.Link -> HINDI_TOO_TIP
        AudioStep.Whistle, AudioStep.Off -> Tip.Mic.text
    }

    const val HINDI_TOO_TIP = "Tap the mic, speak English or Hindi, then \u2713 to send."
}
