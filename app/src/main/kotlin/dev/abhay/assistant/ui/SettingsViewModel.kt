package dev.abhay.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
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
    /** Jev's second opinion (Settings → Ferb Link), saved at once. */
    val jev: StateFlow<Boolean> = linkSettings.jev
    fun setJev(on: Boolean) = linkSettings.setJev(on)
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
