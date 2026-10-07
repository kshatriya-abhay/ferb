package dev.abhay.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.abhay.assistant.AssistantApp
import dev.abhay.assistant.tools.ToolConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The tool settings being edited: one draft across the Tools list and every tool's page, saved
 * together. Saving a different set of tools makes the assistant give Needle 3 the new set
 * ([AssistantViewModel] watches it); other changes need no reload.
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = (app as AssistantApp).toolSettings
    val saved: StateFlow<Map<String, ToolConfig>> = settings.configs

    private val _draft = MutableStateFlow(saved.value)
    val draft: StateFlow<Map<String, ToolConfig>> = _draft.asStateFlow()

    fun update(name: String, change: (ToolConfig) -> ToolConfig) =
        _draft.update { d -> d + (name to change(d.getValue(name))) }

    fun save() = settings.save(_draft.value)

    fun discard() {
        _draft.value = saved.value
    }
}
