package dev.abhay.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import dev.abhay.assistant.voice.GeminiSetup
import dev.abhay.assistant.pipeline.AudioStep
import dev.abhay.assistant.pipeline.Pipeline
import dev.abhay.assistant.pipeline.RewriteStep
import dev.abhay.assistant.pipeline.ToolStep
import dev.abhay.assistant.AssistantApp
import dev.abhay.assistant.tools.ToolConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
    /** The model choice ([dev.abhay.assistant.pipeline.Saved.model]), or none when the steps were changed under Advanced. */
    val chosen get() = steps.saved
    val abilities get() = steps.abilities
    /**
     * The model choice. Picking Gemini Nano while it's still to download starts the download
     * ([download] false: only saved, as onboarding's Next saves the choice shown).
     */
    fun setModel(model: dev.abhay.assistant.pipeline.ModelChoice, download: Boolean = true) {
        steps.setModel(model)
        if (download && model.waitsForGemini(steps.abilities.value) && nano.setup.value.needsSetUp) nano.setUp()
    }
    fun linkChanged(status: dev.abhay.assistant.link.LinkStatus, heard: Boolean? = null) = steps.linkChanged(getApplication<AssistantApp>().linkState(status), heard)
    fun setAudio(step: AudioStep) = steps.setAudio(step)
    fun setRewrite(step: RewriteStep) = steps.setRewrite(step)
    fun setTools(step: ToolStep) = steps.setTools(step)
    /** Gemini Nano's setup (Settings → Gemini Nano): what's on the phone, and download progress. */
    private val nano = (app as AssistantApp).nanoEars
    val gemini: StateFlow<GeminiSetup> = nano.setup
    /** Waiting for its download: until ready, given up (Try again), or ready while Ferb waited. */
    val geminiWatch: StateFlow<dev.abhay.assistant.voice.GeminiWatch> = nano.watch
    fun checkGemini() {
        viewModelScope.launch {
            (getApplication<Application>() as AssistantApp).let { app -> app.followGemini(); ChatSession.followGemini(app.nanoEars.watch) }
            nano.refresh()
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


    /**
     * The user's unsaved edits, by tool: only the fields they changed ([ToolEdit]). The draft is the
     * saved set with those fields over it, so a tool turned on elsewhere (the chat's "Turn on")
     * shows here at once and isn't an unsaved change, Save doesn't write a stale copy over it
     * (review P2-133), and an edit undone drops out (review P2-154: a switch flipped on and off
     * kept the whole config as it was, hiding the chat's change).
     */
    private val edits = MutableStateFlow(emptyMap<String, ToolEdit>())
    val draft: StateFlow<Map<String, ToolConfig>> =
        combine(saved, edits, ::merged).stateIn(viewModelScope, SharingStarted.Eagerly, saved.value)

    private fun merged(saved: Map<String, ToolConfig>, edits: Map<String, ToolEdit>) =
        saved.mapValues { (name, config) -> edits[name]?.over(config) ?: config }

    fun update(name: String, change: (ToolConfig) -> ToolConfig) = edits.update { e ->
        val was = saved.value.getValue(name)
        val edit = ToolEdit.of(was, change(e[name]?.over(was) ?: was))
        if (edit.none) e - name else e + (name to edit)
    }

    fun save() {
        settings.save(merged(saved.value, edits.value))
        edits.value = emptyMap()
    }

    /**
     * Turns these tools on or off and saves that at once, outside the draft (setup's tools page):
     * the draft's other unsaved changes stay unsaved.
     */
    fun setEnabled(names: Collection<String>, on: Boolean) {
        settings.save(saved.value + names.associateWith { saved.value.getValue(it).copy(enabled = on) })
        edits.update { e -> e.mapValues { (name, edit) -> if (name in names) edit.copy(enabled = null) else edit }.filterValues { !it.none } }
    }

    fun discard() {
        edits.value = emptyMap()
    }
}

/** A tool's unsaved edit: the fields the user changed, null for those as saved (review P2-154). */
internal data class ToolEdit(
    val enabled: Boolean? = null,
    val alwaysConfirm: Boolean? = null,
    val runFrom: Double? = null,
    val options: Map<String, String>? = null,
    val keywords: List<String>? = null,
) {
    val none get() = this == ToolEdit()

    fun over(saved: ToolConfig) = saved.copy(
        enabled = enabled ?: saved.enabled,
        alwaysConfirm = alwaysConfirm ?: saved.alwaysConfirm,
        runFrom = runFrom ?: saved.runFrom,
        options = options ?: saved.options,
        keywords = keywords ?: saved.keywords,
    )

    companion object {
        /** [edited] against [saved]: only what differs. */
        fun of(saved: ToolConfig, edited: ToolConfig) = ToolEdit(
            enabled = edited.enabled.takeIf { it != saved.enabled },
            alwaysConfirm = edited.alwaysConfirm.takeIf { it != saved.alwaysConfirm },
            runFrom = edited.runFrom.takeIf { it != saved.runFrom },
            options = edited.options.takeIf { it != saved.options },
            keywords = edited.keywords.takeIf { it != saved.keywords },
        )
    }
}
