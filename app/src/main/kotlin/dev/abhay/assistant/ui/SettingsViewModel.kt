package dev.abhay.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import dev.abhay.assistant.voice.GeminiLanguage
import dev.abhay.assistant.voice.GeminiSetup
import dev.abhay.assistant.voice.PhoneEar
import dev.abhay.assistant.AssistantApp
import dev.abhay.assistant.link.LinkMode
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

    /** Ferb Link (Settings → Ferb Link): saved at once, apart from the tools' draft. */
    val link = (app as AssistantApp).link
    private val linkSettings = (app as AssistantApp).linkSettings
    val linkMode: StateFlow<LinkMode> = linkSettings.mode
    fun setLinkMode(mode: LinkMode) = linkSettings.setMode(mode)
    /** "Use Jev to select the tool" (Settings → Ferb Link), saved at once. */
    val jev: StateFlow<Boolean> = linkSettings.jev
    fun setJev(on: Boolean) = linkSettings.setJev(on)
    /** "Use voice to text on-device", saved at once. */
    private val voice = (app as AssistantApp).voice
    val whistle: StateFlow<Boolean> = voice.whistle
    fun setWhistle(on: Boolean) = voice.setWhistle(on)
    /** Who writes it down: Whistle or Gemini Nano (POC), and the language Gemini Nano expects. */
    val ear: StateFlow<PhoneEar> = voice.ear
    fun setEar(ear: PhoneEar) = voice.setEar(ear)
    val language: StateFlow<GeminiLanguage> = voice.language
    fun setLanguage(language: GeminiLanguage) {
        voice.setLanguage(language)
        checkGemini()
    }
    /** Gemini Nano's setup (Settings → Gemini Nano): what's on the phone, and download progress. */
    private val nano = (app as AssistantApp).nanoEars
    val gemini: StateFlow<GeminiSetup> = nano.setup
    fun checkGemini() {
        viewModelScope.launch { nano.refresh(voice.language.value) }
    }
    fun setUpGemini() = nano.setUp(voice.language.value)

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
