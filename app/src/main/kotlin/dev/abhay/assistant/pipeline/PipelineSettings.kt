package dev.abhay.assistant.pipeline

import android.content.Context
import android.content.SharedPreferences
import dev.abhay.assistant.voice.GeminiLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The three steps as the user chose them ([Saved]), saved at once, and the [pipeline] in use: the
 * saved steps, or the defaults once AICore has said what Gemini Nano can do here ([geminiChecked]).
 * The first time, the settings from before the steps are carried over ([Pipeline.migrate]).
 */
class PipelineSettings(context: Context) {
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

    private fun read() = Saved(AudioStep.of(prefs.getString(KEY_AUDIO, null)), RewriteStep.of(prefs.getString(KEY_REWRITE, null)), ToolStep.of(prefs.getString(KEY_TOOLS, null)))

    private val _saved = MutableStateFlow(read())
    val saved: StateFlow<Saved> = _saved.asStateFlow()

    private var geminiSpeech: Boolean? = null
    private var geminiRewrite: Boolean? = null
    private val _pipeline = MutableStateFlow(Pipeline.resolve(_saved.value, null, null))
    /** The steps each request takes now. */
    val pipeline: StateFlow<Pipeline> = _pipeline.asStateFlow()

    /** AICore's answer (each part available now, or not), for the defaults of steps not chosen yet. */
    fun geminiChecked(speech: Boolean, rewrite: Boolean) {
        geminiSpeech = speech
        geminiRewrite = rewrite
        _pipeline.value = Pipeline.resolve(_saved.value, speech, rewrite)
    }

    private fun save(edit: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(edit).apply()
        _saved.value = read()
        _pipeline.value = Pipeline.resolve(_saved.value, geminiSpeech, geminiRewrite)
    }

    fun setAudio(step: AudioStep) = save { putString(KEY_AUDIO, step.id) }
    fun setRewrite(step: RewriteStep) = save { putString(KEY_REWRITE, step.id) }
    fun setTools(step: ToolStep) = save { putString(KEY_TOOLS, step.id) }

    /** The language Gemini Nano expects (English, the default, hears Hindi too). "nano-hi" was Hindi in the first POC build. */
    private val _language = MutableStateFlow(GeminiLanguage.of(voice.getString(KEY_LANGUAGE, null)
        ?: GeminiLanguage.Hindi.id.takeIf { voice.getString("ear", null) == "nano-hi" }))
    val language: StateFlow<GeminiLanguage> = _language.asStateFlow()

    fun setLanguage(language: GeminiLanguage) {
        voice.edit().putString(KEY_LANGUAGE, language.id).apply()
        _language.value = language
    }

    companion object {
        private const val KEY_AUDIO = "audio"
        private const val KEY_REWRITE = "rewrite"
        private const val KEY_TOOLS = "tools"
        private const val KEY_MIGRATED = "migrated"
        /** Kept in "voice", where the first POC build saved it. */
        private const val KEY_LANGUAGE = "gemini_language"
    }
}
