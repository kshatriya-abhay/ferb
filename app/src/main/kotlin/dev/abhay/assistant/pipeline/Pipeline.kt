package dev.abhay.assistant.pipeline

/**
 * How Ferb understands a request, step by step (Settings → How Ferb understands you): what hears a
 * spoken one, what rewrites it into one plain English command, and what narrows the tools. Needle 3
 * always makes the tool call. A Ferb-Link step that fails at request time falls back for that
 * request ([fallback]), and the reply's details say so.
 */
enum class AudioStep(val id: String, val title: String, val hint: String) {
    Off("off", "Off", "No microphone: type your requests"),
    Whistle("whistle", "Whistle", "On this phone, English only"),
    Gemini("gemini", "Gemini Nano", "On this phone, English and Hindi"),
    Link("link", "Ferb Link (Gemma 4)", "On your computer, English and Hindi");

    /** For this request, when this step fails: Ferb Link's falls back to Whistle (the phone has its recording). */
    val fallback: AudioStep? get() = if (this == Link) Whistle else null

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
        const val TITLE = "Audio processing"
        const val HINT = "What turns your voice into text."
    }
}

enum class RewriteStep(val id: String, val title: String, val hint: String) {
    Off("off", "Off", "Your words go to Needle 3 as they are"),
    Gemini("gemini", "Gemini Nano", "On this phone"),
    Link("link", "Ferb Link (Gemma 4)", "On your computer");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
        const val TITLE = "Prompt rewrite"
        const val HINT = "Helps with other languages and improves accuracy."
    }
}

enum class ToolStep(val id: String, val title: String, val hint: String) {
    Off("off", "Off", "Needle 3 picks from the tools your request mentions"),
    Link("link", "Ferb Link (Jev)", "Jev, through your computer, picks the tool first");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
        const val TITLE = "Tool selection"
        const val HINT = "Improves accuracy by narrowing the tool choice."
    }
}

/** The three steps as chosen; each null until chosen (or migrated), then [resolve]d to a default. */
data class Saved(val audio: AudioStep? = null, val rewrite: RewriteStep? = null, val tools: ToolStep? = null)

data class Pipeline(val audio: AudioStep, val rewrite: RewriteStep, val tools: ToolStep) {
    companion object {
        /**
         * The steps to use: as saved, else the defaults (the user's decision, 2026-10-24): Gemini
         * Nano for audio if its speech is available on this phone, else Whistle; Gemini Nano for the
         * rewrite if its model is available, else off; tool selection off. Until AICore has answered
         * ([geminiSpeech] or [geminiRewrite] null), the phone-only fallbacks.
         */
        fun resolve(saved: Saved, geminiSpeech: Boolean?, geminiRewrite: Boolean?) = Pipeline(
            audio = saved.audio ?: if (geminiSpeech == true) AudioStep.Gemini else AudioStep.Whistle,
            rewrite = saved.rewrite ?: if (geminiRewrite == true) RewriteStep.Gemini else RewriteStep.Off,
            tools = saved.tools ?: ToolStep.Off,
        )

        /**
         * Once, from the settings before the steps (2026-10-24): "Voice on this phone" off is audio
         * off; Gemini Nano picked to hear is Gemini Nano; Computer picked in the chat (Ferb Link's
         * "Choose in chat") is Ferb Link for audio and the rewrite, as Gemma heard and rewrote
         * there; "Use Jev to select the tool" on is Jev. Anything else is left to the defaults.
         */
        fun migrate(old: Old): Saved = Saved(
            audio = when {
                !old.voiceOn -> AudioStep.Off
                old.linkChosen -> AudioStep.Link
                old.ear == "gemini" || old.ear == "nano-en" || old.ear == "nano-hi" -> AudioStep.Gemini
                else -> null
            },
            rewrite = if (old.linkChosen) RewriteStep.Link else null,
            tools = if (old.jev) ToolStep.Link else null,
        )
    }

    /** The settings before the steps, as saved. */
    data class Old(val voiceOn: Boolean = true, val ear: String? = null, val linkChosen: Boolean = false, val jev: Boolean = false)
}

/**
 * What each option needs, for Settings and onboarding: Ferb Link installed and paired with a
 * computer for its options ([linkReason] null when it is), and AICore's checks for Gemini Nano's
 * ([geminiSpeech], [geminiRewrite]: null until checked).
 */
data class Availability(
    val linkReason: String? = null,
    val geminiSpeech: Boolean? = null,
    val geminiRewrite: Boolean? = null,
    /** Why the computer can't hear (its model takes no audio), when Ferb Link is otherwise ready. */
    val linkAudioReason: String? = null,
    /** Why Jev can't pick (off in Ferb Link, or at its spending limit), when Ferb Link is otherwise ready. */
    val jevReason: String? = null,
) {
    /** Why [step] can't be picked now, or null when it can. */
    fun reason(step: Any): String? = when (step) {
        AudioStep.Link -> linkReason ?: linkAudioReason
        ToolStep.Link -> linkReason ?: jevReason
        RewriteStep.Link -> linkReason
        AudioStep.Gemini -> when (geminiSpeech) { false -> GEMINI_UNAVAILABLE; else -> null }
        RewriteStep.Gemini -> when (geminiRewrite) { false -> GEMINI_UNAVAILABLE; else -> null }
        else -> null
    }

    companion object {
        const val GEMINI_UNAVAILABLE = "Not available on this phone"
        const val LINK_NOT_INSTALLED = "Install Ferb Link to use your computer"
        const val LINK_NOT_PAIRED = "Pair Ferb Link with your computer first"
        const val LINK_NO_AUDIO = "Your computer's model doesn't take audio"
        const val JEV_OFF = "Turn on Jev in Ferb Link first"
        const val JEV_CAPPED = "Jev reached its spending limit: raise it in Ferb Link"
    }
}

/** A step that couldn't be used for this request, and what was used instead, for the reply's details. */
data class Fallback(val step: String, val used: String, val why: String) {
    val line get() = "$step: $used instead ($why)"
}
