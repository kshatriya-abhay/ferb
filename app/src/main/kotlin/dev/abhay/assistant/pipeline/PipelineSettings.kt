package dev.abhay.assistant.pipeline

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Talk to Ferb in, or the three steps as the user chose them under Advanced ([Saved]), saved at
 * once, and the [pipeline] in use, resolved against what this phone can use now ([Abilities]):
 * Gemini Nano once AICore has answered ([geminiChecked]), Ferb Link as [linkState] says
 * ([linkChanged]). The first time, the settings from before the steps are carried over
 * ([Pipeline.migrate]), and later the steps a choice gives anyway become it ([Pipeline.toTalk]).
 */
class PipelineSettings(context: Context, private val linkState: () -> Pair<Boolean, Boolean> = { false to false }) {
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
        if (!prefs.getBoolean(KEY_TALK_MIGRATED, false)) {
            val talked = Pipeline.toTalk(read())
            prefs.edit().apply {
                if (talked != read()) {
                    remove(KEY_AUDIO); remove(KEY_REWRITE); remove(KEY_TOOLS)
                    talked.talk?.let { putString(KEY_TALK, it.id) }
                }
                putBoolean(KEY_TALK_MIGRATED, true)
            }.apply()
        }
    }

    private fun read() = Saved(
        AudioStep.of(prefs.getString(KEY_AUDIO, null)), RewriteStep.of(prefs.getString(KEY_REWRITE, null)),
        ToolStep.of(prefs.getString(KEY_TOOLS, null)), Talk.of(prefs.getString(KEY_TALK, null)),
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

    /** AICore's answer (each part available now, or not). Ferb Link's state is read again too. */
    fun geminiChecked(speech: Boolean, rewrite: Boolean) {
        val (link, audio) = linkState()
        _abilities.value = _abilities.value.copy(geminiSpeech = speech, geminiRewrite = rewrite, link = link, linkAudio = audio)
        update()
    }

    /**
     * Ferb Link's state, read again (on resume, and before a request). [heard]: whether the
     * computer's model takes audio, when a live check has just said so (its status may not know yet).
     */
    fun linkChanged(heard: Boolean? = null) {
        val (link, seen) = linkState()
        val audio = link && (heard ?: seen)
        if (link == _abilities.value.link && audio == _abilities.value.linkAudio) return
        _abilities.value = _abilities.value.copy(link = link, linkAudio = audio)
        update()
    }

    private fun save(edit: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(edit).apply()
        _saved.value = read()
        update()
    }

    /** Talk to Ferb in: the choice, and the steps under Advanced go back to following it. */
    fun setTalk(talk: Talk) = save { remove(KEY_AUDIO); remove(KEY_REWRITE); remove(KEY_TOOLS); putString(KEY_TALK, talk.id) }

    /** A step changed under Advanced: the other two stay as they are now, and Talk to Ferb in shows none. */
    private fun setStep(key: String, id: String) {
        val now = _pipeline.value
        save {
            remove(KEY_TALK)
            putString(KEY_AUDIO, now.audio.id); putString(KEY_REWRITE, now.rewrite.id); putString(KEY_TOOLS, now.tools.id)
            putString(key, id)
        }
    }

    /** Puts back what was saved before (device tests). */
    fun restore(saved: Saved) = save {
        clear(); putBoolean(KEY_MIGRATED, true); putBoolean(KEY_TALK_MIGRATED, true)
        saved.audio?.let { putString(KEY_AUDIO, it.id) }; saved.rewrite?.let { putString(KEY_REWRITE, it.id) }
        saved.tools?.let { putString(KEY_TOOLS, it.id) }; saved.talk?.let { putString(KEY_TALK, it.id) }
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
        private const val KEY_TALK = "talk"
        private const val KEY_TALK_MIGRATED = "talk_migrated"
        /** Gemini Nano's language choice, removed 2026-10-25; kept in "voice", where the first POC build saved it. */
        internal const val KEY_LANGUAGE = "gemini_language"
    }
}
