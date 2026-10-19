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
    Needle("needle", "Phone"),
    Link("link", "Computer");

    /**
     * The label in the chat box: "Phone + Jev" while Jev picks the tool for the phone ("Use Jev to
     * select the tool" on, and Ferb Link reporting Jev available), so it's plain the request
     * leaves the phone. Computer's stays as it is.
     */
    fun label(usingJev: Boolean): String = if (this == Needle && usingJev) "Phone + Jev" else label

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Needle

        /**
         * Whether the chat box says "Using Jev" under it: while Jev is in use, unless Phone is
         * picked, whose label says it already ("Phone + Jev"). [shown]: the choice in the chat
         * box, null when there's none ("When Needle 3 is unsure").
         */
        fun usingJevLine(usingJev: Boolean, shown: Answerer?): Boolean = usingJev && shown != Needle
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

    private val _jev = MutableStateFlow(prefs.getBoolean(KEY_JEV, false))
    /**
     * "Use Jev to select the tool" ([LinkPolicy.select]): every request goes to Jev first, in
     * every mode but Off. Off until the user turns it on, and used only while Ferb Link has Jev on.
     */
    val jev: StateFlow<Boolean> = _jev.asStateFlow()

    fun setJev(on: Boolean) {
        prefs.edit().putBoolean(KEY_JEV, on).apply()
        _jev.value = on
    }

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
        private const val KEY_JEV = "jev_selects"
    }
}
