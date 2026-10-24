package dev.abhay.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import dev.abhay.assistant.voice.GeminiLanguage
import dev.abhay.assistant.voice.GeminiSetup
import dev.abhay.assistant.voice.Part
import dev.abhay.assistant.pipeline.AudioStep
import dev.abhay.assistant.pipeline.Pipeline
import dev.abhay.assistant.pipeline.RewriteStep
import dev.abhay.assistant.pipeline.ToolStep
import dev.abhay.assistant.AssistantApp
import dev.abhay.assistant.tools.ToolConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The tool settings being edited: one draft across the Tools list and every tool's page, saved
 * together. Nothing needs reloading: each request gives Needle 3 the tools it mentions, from the
 * saved set ([dev.abhay.assistant.tools.ToolRouter]).
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = (app as AssistantApp).toolSettings

    /** Ferb Link (Settings → Ferb Link), for its page and for the steps that use it. */
    val link = (app as AssistantApp).link
    /** How Ferb understands you (Settings): each step as chosen, and the steps in use. Saved at once. */
    private val steps = (app as AssistantApp).pipeline
    val pipeline: StateFlow<Pipeline> = steps.pipeline
    fun setAudio(step: AudioStep) = steps.setAudio(step)
    fun setRewrite(step: RewriteStep) = steps.setRewrite(step)
    fun setTools(step: ToolStep) = steps.setTools(step)
    /** The language Gemini Nano expects. */
    val language: StateFlow<GeminiLanguage> = steps.language
    fun setLanguage(language: GeminiLanguage) {
        steps.setLanguage(language)
        checkGemini()
    }
    /** Gemini Nano's setup (Settings → Gemini Nano): what's on the phone, and download progress. */
    private val nano = (app as AssistantApp).nanoEars
    val gemini: StateFlow<GeminiSetup> = nano.setup
    fun checkGemini() {
        viewModelScope.launch {
            nano.refresh(steps.language.value)
            val setup = nano.setup.value
            steps.geminiChecked(speech = setup.speech == Part.Ready, rewrite = setup.rewrite == Part.Ready)
        }
    }
    fun setUpGemini() = nano.setUp(steps.language.value)
    fun setUpGeminiSpeech() = nano.setUpSpeech(steps.language.value)
    fun setUpGeminiRewrite() = nano.setUpRewrite(steps.language.value)

    val saved: StateFlow<Map<String, ToolConfig>> = settings.configs

    /** The one-time tips (a tool's page has one), and "Show tips again". */
    val onboarding = (app as AssistantApp).onboarding

    /** The notes folder (Settings → Tools → Notes), picked at once, apart from the draft. */
    val notes = (app as AssistantApp).notes


    private val _draft = MutableStateFlow(saved.value)
    val draft: StateFlow<Map<String, ToolConfig>> = _draft.asStateFlow()

    fun update(name: String, change: (ToolConfig) -> ToolConfig) =
        _draft.update { d -> d + (name to change(d.getValue(name))) }

    fun save() = settings.save(_draft.value)

    fun discard() {
        _draft.value = saved.value
    }
}
