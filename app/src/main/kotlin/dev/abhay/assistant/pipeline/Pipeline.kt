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

/**
 * Settings → Talk to Ferb in: the one choice most people make. Each picks the best steps this
 * phone can use now ([steps]); the steps themselves are under Advanced. When nothing here can
 * hear Hindi, English + Hindi can't be picked, and [reason] says why.
 */
enum class Talk(val id: String, val title: String, val hint: String) {
    English("english", "English", "Speak in English"),
    Hindi("hindi", "English + Hindi", "Speak English, Hindi or Hinglish"),
    Typing("typing", "Typing only", "No microphone");

    /**
     * The steps for this choice, on this phone now: Gemini Nano when it's here (it stays on the
     * phone), else the computer through Ferb Link for Hindi (Whistle hears English only), else
     * Whistle. English stays on the phone. Tool selection is always off (Jev is under Advanced).
     */
    fun steps(can: Abilities): Pipeline = when (this) {
        English -> Pipeline(if (can.geminiSpeech == true) AudioStep.Gemini else AudioStep.Whistle, can.phoneRewrite, ToolStep.Off)
        Hindi -> Pipeline(
            audio = when {
                can.geminiSpeech == true -> AudioStep.Gemini
                can.linkAudio -> AudioStep.Link
                else -> AudioStep.Whistle
            },
            rewrite = can.bestRewrite,
            tools = ToolStep.Off,
        )
        Typing -> Pipeline(AudioStep.Off, can.bestRewrite, ToolStep.Off)
    }

    /** Why this choice can't be picked now, or null when it can. */
    fun reason(can: Abilities): String? = when {
        this == Hindi && can.geminiSpeech != true && !can.linkAudio && can.geminiSpeech != null -> HINDI_NEEDS
        else -> null
    }

    /** Where requests are understood, for the choice's second line. */
    fun where(can: Abilities): String {
        val steps = steps(can)
        return when {
            steps.audio == AudioStep.Link || (this == Typing && steps.rewrite == RewriteStep.Link) -> "Through your computer"
            steps.audio == AudioStep.Gemini || steps.rewrite == RewriteStep.Gemini -> "Gemini Nano, on this phone"
            steps.audio == AudioStep.Whistle -> "Whistle, on this phone"
            else -> "On this phone"
        }
    }

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
        const val TITLE = "Talk to Ferb in"
        const val HINDI_NEEDS = "Needs Gemini Nano on this phone, or Ferb Link connected to your computer"

        /** Before anything is chosen: English + Hindi when something here hears it, else English. */
        fun default(can: Abilities) = if (can.geminiSpeech == true || can.linkAudio) Hindi else English
    }
}

/**
 * What this phone can use now: Gemini Nano's speech and rewrite (null until AICore has answered),
 * and Ferb Link ([link]: installed, paired and on; [linkAudio]: and the computer's model hears).
 */
data class Abilities(
    val geminiSpeech: Boolean? = null,
    val geminiRewrite: Boolean? = null,
    val link: Boolean = false,
    val linkAudio: Boolean = false,
) {
    internal val phoneRewrite get() = if (geminiRewrite == true) RewriteStep.Gemini else RewriteStep.Off
    internal val bestRewrite get() = when {
        geminiRewrite == true -> RewriteStep.Gemini
        link -> RewriteStep.Link
        else -> RewriteStep.Off
    }
}

/**
 * What the user chose: [talk], or, once a step is changed under Advanced, the three steps (each
 * null until chosen or migrated, then a default), and [talk] null.
 */
data class Saved(val audio: AudioStep? = null, val rewrite: RewriteStep? = null, val tools: ToolStep? = null, val talk: Talk? = null) {
    val custom get() = talk == null && (audio != null || rewrite != null || tools != null)
}

data class Pipeline(val audio: AudioStep, val rewrite: RewriteStep, val tools: ToolStep) {
    companion object {
        /**
         * The steps to use: [Saved.talk]'s; else, once changed under Advanced, the steps as saved,
         * the rest the defaults (the user's decision, 2026-10-24): Gemini Nano for audio if its
         * speech is available on this phone, else Whistle; Gemini Nano for the rewrite if its model
         * is available, else off; tool selection off. With nothing saved, [Talk.default]'s. Until
         * AICore has answered, the phone-only fallbacks.
         */
        fun resolve(saved: Saved, can: Abilities): Pipeline = when {
            saved.talk != null -> saved.talk.steps(can)
            saved.custom -> Pipeline(
                audio = saved.audio ?: if (can.geminiSpeech == true) AudioStep.Gemini else AudioStep.Whistle,
                rewrite = saved.rewrite ?: can.phoneRewrite,
                tools = saved.tools ?: ToolStep.Off,
            )
            else -> Talk.default(can).steps(can)
        }

        /**
         * Once, when Talk to Ferb in arrived (2026-10-30): steps that one of its choices gives
         * anyway become that choice, so the settings show it; anything else stays under Advanced.
         */
        fun toTalk(saved: Saved): Saved = when {
            saved.talk != null || !saved.custom -> saved
            saved.tools != null && saved.tools != ToolStep.Off -> saved
            saved.audio == AudioStep.Off && saved.rewrite in setOf(null, RewriteStep.Gemini) -> Saved(talk = Talk.Typing)
            saved.audio in setOf(null, AudioStep.Gemini) && saved.rewrite in setOf(null, RewriteStep.Gemini) -> Saved()
            else -> saved
        }

        /**
         * Once, from the settings before the steps (2026-10-24): Computer picked in the chat (Ferb
         * Link's "Choose in chat") is Ferb Link for audio and the rewrite, as Gemma heard and
         * rewrote there, even with "Voice on this phone" off (the mic showed for Computer then);
         * otherwise voice off is audio off, and Gemini Nano picked to hear is Gemini Nano. "Use Jev
         * to select the tool" is Jev only while Ferb Link was on (it was used only then), so no
         * request leaves the phone that didn't before. Anything else is left to the defaults.
         */
        fun migrate(old: Old): Saved = Saved(
            audio = when {
                old.linkChosen -> AudioStep.Link
                !old.voiceOn -> AudioStep.Off
                old.ear == "gemini" || old.ear == "nano-en" || old.ear == "nano-hi" -> AudioStep.Gemini
                else -> null
            },
            rewrite = if (old.linkChosen) RewriteStep.Link else null,
            tools = if (old.jev && old.linkOn) ToolStep.Link else null,
        )
    }

    /**
     * The settings before the steps, as saved: Ferb Link's [linkMode] ("off", "option", or the
     * removed "fallback", which became "option" with Phone picked) and [answerer] ("needle", "link").
     */
    data class Old(
        val voiceOn: Boolean = true,
        val ear: String? = null,
        val linkMode: String? = null,
        val answerer: String? = null,
        val jev: Boolean = false,
    ) {
        val linkOn get() = linkMode == "option" || linkMode == "fallback"
        val linkChosen get() = linkMode == "option" && answerer == "link"
    }
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
