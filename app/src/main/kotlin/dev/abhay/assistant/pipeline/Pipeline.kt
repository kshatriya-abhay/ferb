package dev.abhay.assistant.pipeline

/**
 * How Ferb understands a request, step by step (Settings → How Ferb understands you): what hears a
 * spoken one, what rewrites it into one plain English command, and what narrows the tools. Needle 3
 * always makes the tool call. A Ferb-Link step that fails at request time falls back for that
 * request ([fallback]), and the reply's details say so.
 */
enum class AudioStep(val id: String, val title: String, val hint: String) {
    Off("off", "Off", "No mic: you type"),
    Whistle("whistle", "Whistle", "On this phone, English only"),
    Gemini("gemini", "Gemini Nano", "On this phone, English and Hindi"),
    Link("link", "Ferb Link (Gemma 4)", "On your computer, English and Hindi");

    /** For this request, when this step fails: Ferb Link's falls back to Whistle (the phone has its recording). */
    val fallback: AudioStep? get() = if (this == Link) Whistle else null

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
        const val TITLE = "Audio processing"
        const val HINT = "Turns your voice into text."
    }
}

enum class RewriteStep(val id: String, val title: String, val hint: String) {
    Off("off", "Off", "Your words as they are"),
    Gemini("gemini", "Gemini Nano", "On this phone"),
    Link("link", "Ferb Link (Gemma 4)", "On your computer");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
        const val TITLE = "Prompt rewrite"
        const val HINT = "Helps with Hindi and accuracy."
    }
}

enum class ToolStep(val id: String, val title: String, val hint: String) {
    Off("off", "Off", "Needle 3 picks from the tools named"),
    Link("link", "Ferb Link (Jev)", "Jev, via your computer, picks first");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
        const val TITLE = "Tool selection"
        const val HINT = "Narrows the tool choice."
    }
}

/**
 * Settings → Choose your preferred model (and onboarding's second page), the one choice most
 * people make (the user's decision, 2026-10-30). Each stands for a set of steps ([steps]); the steps
 * themselves are under Advanced, where Jev stays. Needle 3 makes every tool call whichever is
 * picked, and typing works with each. Gemini Nano can be picked while it's still to download
 * (picking it starts the download, and Ferb uses Whistle meanwhile); Gemma 4 while Ferb Link isn't
 * paired yet (requests fall back, one at a time). Only what can never work here is greyed out,
 * [reason] saying why.
 */
enum class ModelChoice(val id: String, val title: String, val hint: String) {
    Whistle("needle_whistle", "Needle + Whistle", "English-only, wide-compatibility"),
    Gemini("gemini", "Gemini Nano", "on-device, multilingual, compatibility varies"),
    Gemma("gemma", "Gemma 4 (via Ferb Link)", "run it on your own server, multilingual");

    /** The steps it stands for: what a set saved under Advanced must be, exactly, to become it. */
    val steps: Pipeline get() = when (this) {
        Whistle -> Pipeline(AudioStep.Whistle, RewriteStep.Off, ToolStep.Off)
        Gemini -> Pipeline(AudioStep.Gemini, RewriteStep.Gemini, ToolStep.Off)
        Gemma -> Pipeline(AudioStep.Link, RewriteStep.Link, ToolStep.Off)
    }

    /**
     * The steps requests take now: Gemini Nano's parts once each is ready (Whistle, and no rewrite,
     * until then), so Ferb switches the moment its download is done. Ferb Link's steps as they
     * are: one that fails (not paired, or the computer away) falls back for that request.
     */
    fun resolve(can: Abilities): Pipeline = when (this) {
        Gemini -> Pipeline(if (can.geminiSpeech == true) AudioStep.Gemini else AudioStep.Whistle, can.phoneRewrite, ToolStep.Off)
        else -> steps
    }

    /** Why this can't be picked on this phone, or null when it can (still to download, or to pair, counts as can). */
    fun reason(can: Abilities): String? = when (this) {
        Gemini -> GEMINI_CANT_RUN.takeIf { !can.geminiHere }
        Gemma -> LINK_NOT_INSTALLED.takeIf { !can.linkInstalled }
        Whistle -> null
    }

    /** [reason] in a few words, for setup, where the options carry no other text. */
    fun shortReason(can: Abilities): String? = when (this) {
        Gemini -> CANT_RUN_HERE.takeIf { !can.geminiHere }
        Gemma -> NEEDS_LINK.takeIf { !can.linkInstalled }
        Whistle -> null
    }

    /** Gemma 4 picked with Ferb Link installed but not paired (or off): it says so, with a button to open Ferb Link. */
    fun needsPairing(can: Abilities) = this == Gemma && can.linkInstalled && !can.link

    /** Gemini Nano picked but not ready yet: Whistle hears until it is. */
    fun waitsForGemini(can: Abilities) = this == Gemini && can.geminiHere && can.geminiSpeech != true

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
        const val TITLE = "Choose your preferred model"
        const val GEMINI_CANT_RUN = "Gemini Nano can't run on this phone"
        const val LINK_NOT_INSTALLED = "Needs the Ferb Link app"
        const val PAIR_LINK = "Pair Ferb Link with your computer. Until then, requests stay on this phone."
        const val CANT_RUN_HERE = "Can't run on this phone"
        const val NEEDS_LINK = "Needs Ferb Link"
        const val NOT_PAIRED = "Not paired yet"

        /** Before anything is chosen: Gemini Nano if it can run here (even still to download), else Needle + Whistle. */
        fun default(can: Abilities) = if (can.geminiHere) Gemini else Whistle

        /** The one whose steps [saved] are, exactly, or null (they stay under Advanced). */
        fun matching(saved: Saved): ModelChoice? {
            val steps = Pipeline(saved.audio ?: return null, saved.rewrite ?: return null, saved.tools ?: return null)
            return entries.firstOrNull { it.steps == steps }
        }
    }
}

/**
 * What this phone can use now: Gemini Nano's speech and rewrite, ready (null until AICore has
 * answered; [geminiHere] false when AICore can't run it on this phone at all, not even after a
 * download), and Ferb Link ([linkApp]: installed; [link]: installed, paired and on; [linkAudio]:
 * and the computer hears).
 */
data class Abilities(
    val geminiSpeech: Boolean? = null,
    val geminiRewrite: Boolean? = null,
    val link: Boolean = false,
    val linkAudio: Boolean = false,
    val geminiHere: Boolean = true,
    val linkApp: Boolean = false,
) {
    /** Ferb Link is installed (paired means installed). */
    val linkInstalled get() = linkApp || link

    internal val phoneRewrite get() = if (geminiRewrite == true) RewriteStep.Gemini else RewriteStep.Off
    internal val bestRewrite get() = when {
        geminiRewrite == true -> RewriteStep.Gemini
        link -> RewriteStep.Link
        else -> RewriteStep.Off
    }
}

/**
 * What the user chose: [model], or, once a step is changed under Advanced, the three steps (each
 * null until chosen or migrated, then a default), and [model] null.
 */
data class Saved(val audio: AudioStep? = null, val rewrite: RewriteStep? = null, val tools: ToolStep? = null, val model: ModelChoice? = null) {
    val custom get() = model == null && (audio != null || rewrite != null || tools != null)
}

data class Pipeline(val audio: AudioStep, val rewrite: RewriteStep, val tools: ToolStep) {
    companion object {
        /**
         * The steps to use: [Saved.model]'s; else, once changed under Advanced, the steps as saved,
         * the rest the defaults (the user's decision, 2026-10-24): Gemini Nano for audio if its
         * speech is available on this phone, else Whistle; Gemini Nano for the rewrite if its model
         * is available, else off; tool selection off. With nothing saved, [ModelChoice.default]'s.
         * Until AICore has answered, the phone-only fallbacks.
         */
        fun resolve(saved: Saved, can: Abilities): Pipeline = when {
            saved.model != null -> saved.model.resolve(can)
            saved.custom -> Pipeline(
                audio = saved.audio ?: if (can.geminiSpeech == true) AudioStep.Gemini else AudioStep.Whistle,
                rewrite = saved.rewrite ?: can.phoneRewrite,
                tools = saved.tools ?: ToolStep.Off,
            )
            else -> ModelChoice.default(can).resolve(can)
        }

        /**
         * Once, when the model choice replaced Talk to Ferb in (2026-10-30): steps saved under
         * Advanced that are exactly a model's become it; anything else stays under Advanced. (A Talk
         * to Ferb in choice stood for steps that followed the phone, none exactly a model's: it
         * goes, and the default applies; [PipelineSettings] drops it.)
         */
        fun toModel(saved: Saved): Saved =
            if (saved.custom) ModelChoice.matching(saved)?.let { Saved(model = it) } ?: saved else saved

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
    /**
     * Whether [step] can't ever work on this phone as it is, so it isn't offered at all: Ferb
     * Link's while it isn't installed, Gemini Nano's where AICore can't run it.
     */
    fun hidden(step: Any): Boolean = when (step) {
        AudioStep.Link, RewriteStep.Link, ToolStep.Link -> linkReason == LINK_NOT_INSTALLED
        AudioStep.Gemini -> geminiSpeech == false
        RewriteStep.Gemini -> geminiRewrite == false
        else -> false
    }

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
        const val LINK_NOT_PAIRED = "Connect Ferb Link to your computer first"
        const val LINK_NO_AUDIO = "Your computer's model doesn't take audio"
        const val JEV_OFF = "Turn on Jev in Ferb Link first"
        const val JEV_CAPPED = "Jev hit its spending limit (Ferb Link)"
    }
}

/** A step that couldn't be used for this request, and what was used instead, for the reply's details. */
data class Fallback(val step: String, val used: String, val why: String) {
    val line get() = "$step: $used instead ($why)"
}
