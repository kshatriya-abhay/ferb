package dev.abhay.assistant.pipeline

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The model choice, or the three steps as the user chose them under Advanced ([Saved]), saved at
 * once, and the [pipeline] in use, resolved against what this phone can use now ([Abilities]):
 * Gemini Nano once AICore has answered ([geminiChecked]), Ferb Link as [linkState] says
 * ([linkChanged]). The first time, the settings from before the steps are carried over
 * ([Pipeline.migrate]), and later steps that are exactly a model's become it ([Pipeline.toModel]).
 */
class PipelineSettings(context: Context, private val linkState: () -> LinkState = { LinkState() }) {
    /** Ferb Link as last read: installed; paired and on ([ready]); and the computer hears ([audio]). */
    data class LinkState(val installed: Boolean = false, val ready: Boolean = false, val audio: Boolean = false)

    private val prefs = context.getSharedPreferences("pipeline", Context.MODE_PRIVATE)
    private val voice = context.getSharedPreferences("voice", Context.MODE_PRIVATE)

    init {
        if (!prefs.getBoolean(KEY_MIGRATED, false)) {
            val link = context.getSharedPreferences("link", Context.MODE_PRIVATE)
            val saved = Pipeline.migrate(Pipeline.Old(
                voiceOn = voice.getBoolean("whistle", true),
                ear = voice.getString("ear", null),
                linkMode = link.getString("mode", null),
                answerer = link.getString("answerer", null),
                jev = link.getBoolean("jev_selects", false),
            ))
            prefs.edit().apply {
                saved.audio?.let { putString(KEY_AUDIO, it.id) }
                saved.rewrite?.let { putString(KEY_REWRITE, it.id) }
                saved.tools?.let { putString(KEY_TOOLS, it.id) }
                putBoolean(KEY_MIGRATED, true)
            }.apply()
        }
    }

    init {
        if (!prefs.getBoolean(KEY_MODEL_MIGRATED, false)) {
            val modelled = Pipeline.toModel(read())
            prefs.edit().apply {
                // Talk to Ferb in (2026-10-30, briefly) is gone: its choice goes with it.
                remove(KEY_TALK); remove(KEY_TALK_MIGRATED)
                if (modelled != read()) {
                    remove(KEY_AUDIO); remove(KEY_REWRITE); remove(KEY_TOOLS)
                    modelled.model?.let { putString(KEY_MODEL, it.id) }
                }
                putBoolean(KEY_MODEL_MIGRATED, true)
            }.apply()
        }
    }

    private fun read() = Saved(
        AudioStep.of(prefs.getString(KEY_AUDIO, null)), RewriteStep.of(prefs.getString(KEY_REWRITE, null)),
        ToolStep.of(prefs.getString(KEY_TOOLS, null)), ModelChoice.of(prefs.getString(KEY_MODEL, null)),
    )

    private val _saved = MutableStateFlow(read())
    val saved: StateFlow<Saved> = _saved.asStateFlow()

    private val _abilities = MutableStateFlow(Abilities())
    /** What this phone can use now, as last checked. */
    val abilities: StateFlow<Abilities> = _abilities.asStateFlow()
    private val _pipeline = MutableStateFlow(Pipeline.resolve(_saved.value, _abilities.value))
    /** The steps each request takes now. */
    val pipeline: StateFlow<Pipeline> = _pipeline.asStateFlow()

    private fun update() {
        _pipeline.value = Pipeline.resolve(_saved.value, _abilities.value)
    }

    /**
     * AICore's answer (each part ready now, or not; [here]: whether it can run on this phone at
     * all). Ferb Link's state is read again too.
     */
    fun geminiChecked(speech: Boolean, rewrite: Boolean, here: Boolean = true) {
        val link = linkState()
        _abilities.value = _abilities.value.copy(geminiSpeech = speech, geminiRewrite = rewrite, link = link.ready, linkAudio = link.audio,
            linkApp = link.installed, geminiHere = here)
        update()
    }

    /**
     * Ferb Link's state, read again (on resume, and before a request). [heard]: whether the
     * computer's model takes audio, when a live check has just said so (its status may not know yet).
     */
    fun linkChanged(heard: Boolean? = null) {
        val state = linkState()
        val link = state.ready
        val audio = link && (heard ?: state.audio)
        val now = _abilities.value
        if (link == now.link && audio == now.linkAudio && state.installed == now.linkApp) return
        _abilities.value = now.copy(link = link, linkAudio = audio, linkApp = state.installed)
        update()
    }

    private fun save(edit: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(edit).apply()
        _saved.value = read()
        update()
    }

    /** The model choice, and the steps under Advanced go back to following it. */
    fun setModel(model: ModelChoice) = save { remove(KEY_AUDIO); remove(KEY_REWRITE); remove(KEY_TOOLS); putString(KEY_MODEL, model.id) }

    /** A step changed under Advanced: the other two stay as they are now, and the model choice shows none. */
    private fun setStep(key: String, id: String) {
        val now = _pipeline.value
        save {
            remove(KEY_MODEL)
            putString(KEY_AUDIO, now.audio.id); putString(KEY_REWRITE, now.rewrite.id); putString(KEY_TOOLS, now.tools.id)
            putString(key, id)
        }
    }

    /** Puts back what was saved before (device tests). */
    fun restore(saved: Saved) = save {
        clear(); putBoolean(KEY_MIGRATED, true); putBoolean(KEY_MODEL_MIGRATED, true)
        saved.audio?.let { putString(KEY_AUDIO, it.id) }; saved.rewrite?.let { putString(KEY_REWRITE, it.id) }
        saved.tools?.let { putString(KEY_TOOLS, it.id) }; saved.model?.let { putString(KEY_MODEL, it.id) }
    }

    fun setAudio(step: AudioStep) = setStep(KEY_AUDIO, step.id)
    fun setRewrite(step: RewriteStep) = setStep(KEY_REWRITE, step.id)
    fun setTools(step: ToolStep) = setStep(KEY_TOOLS, step.id)

    init {
        // Gemini Nano's language choice is gone (2026-10-25: one locale hears English, Hindi and
        // Hinglish, [dev.abhay.assistant.voice.NanoSpeech]): the saved choice goes, once.
        if (voice.contains(KEY_LANGUAGE)) voice.edit().remove(KEY_LANGUAGE).apply()
    }

    companion object {
        private const val KEY_AUDIO = "audio"
        private const val KEY_REWRITE = "rewrite"
        private const val KEY_TOOLS = "tools"
        private const val KEY_MIGRATED = "migrated"
        private const val KEY_MODEL = "model"
        private const val KEY_MODEL_MIGRATED = "model_migrated"
        /** Talk to Ferb in's choice and its migration flag, 2026-10-30; removed by the model choice's migration. */
        private const val KEY_TALK = "talk"
        private const val KEY_TALK_MIGRATED = "talk_migrated"
        /** Gemini Nano's language choice, removed 2026-10-25; kept in "voice", where the first POC build saved it. */
        internal const val KEY_LANGUAGE = "gemini_language"
    }
}
