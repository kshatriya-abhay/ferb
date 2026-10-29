package dev.abhay.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
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
    /** Talk to Ferb in, or null when the steps were changed under Advanced. */
    val chosen get() = steps.saved
    val abilities get() = steps.abilities
    fun setTalk(talk: dev.abhay.assistant.pipeline.Talk) = steps.setTalk(talk)
    fun linkChanged(heard: Boolean? = null) = steps.linkChanged(heard)
    fun setAudio(step: AudioStep) = steps.setAudio(step)
    fun setRewrite(step: RewriteStep) = steps.setRewrite(step)
    fun setTools(step: ToolStep) = steps.setTools(step)
    /** Gemini Nano's setup (Settings → Gemini Nano): what's on the phone, and download progress. */
    private val nano = (app as AssistantApp).nanoEars
    val gemini: StateFlow<GeminiSetup> = nano.setup
    fun checkGemini() {
        viewModelScope.launch {
            nano.refresh()
            val setup = nano.setup.value
            steps.geminiChecked(speech = setup.speech == Part.Ready, rewrite = setup.rewrite == Part.Ready, here = setup.speech != Part.Unavailable)
        }
    }
    fun setUpGemini() = nano.setUp()
    fun setUpGeminiSpeech() = nano.setUpSpeech()
    fun setUpGeminiRewrite() = nano.setUpRewrite()

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
