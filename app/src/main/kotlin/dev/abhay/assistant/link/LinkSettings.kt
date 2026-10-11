package dev.abhay.assistant.link

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How Ferb uses Ferb Link. */
enum class LinkMode(val id: String) {
    /** Needle 3 only (the default). */
    Off("off"),
    /** Needle 3 first; the computer's model when Needle 3 is unsure or finds no tool ([LinkPolicy]). */
    Fallback("fallback"),
    /** The chat box offers a choice of model, Needle 3 or Ferb Link ([Answerer]). */
    Option("option");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Off
    }
}

/** Who answers in [LinkMode.Option], as picked in the chat box. */
enum class Answerer(val id: String, val label: String) {
    Needle("needle", "Needle 3"),
    Link("link", "Ferb Link");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Needle
    }
}

/**
 * Ferb's own Ferb Link settings, saved at once (they're on a page of their own, apart from the
 * tools' draft). Ferb Link's server address and key live in Ferb Link, not here.
 */
class LinkSettings(context: Context) {
    private val prefs = context.getSharedPreferences("link", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(LinkMode.of(prefs.getString(KEY_MODE, null)))
    val mode: StateFlow<LinkMode> = _mode.asStateFlow()

    private val _answerer = MutableStateFlow(Answerer.of(prefs.getString(KEY_ANSWERER, null)))
    /** The chat box's choice in [LinkMode.Option], kept until changed. */
    val answerer: StateFlow<Answerer> = _answerer.asStateFlow()

    fun setMode(mode: LinkMode) {
        prefs.edit().putString(KEY_MODE, mode.id).apply()
        _mode.value = mode
    }

    fun setAnswerer(answerer: Answerer) {
        prefs.edit().putString(KEY_ANSWERER, answerer.id).apply()
        _answerer.value = answerer
    }

    companion object {
        private const val KEY_MODE = "mode"
        private const val KEY_ANSWERER = "answerer"
    }
}
