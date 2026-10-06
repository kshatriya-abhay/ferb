package dev.abhay.assistant.tools

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which tools the user has turned on, kept in preferences; before any save, the tools on by default. */
class ToolSettings(context: Context) {
    private val prefs = context.getSharedPreferences("tools", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(read())
    /** Names of the tools turned on, in [Tools.all] order. */
    val enabled: StateFlow<List<String>> = _enabled.asStateFlow()

    private fun read(): List<String> = normalise(prefs.getStringSet(ENABLED, null) ?: defaults())

    fun save(names: Set<String>) {
        prefs.edit().putStringSet(ENABLED, names).apply()
        _enabled.value = normalise(names)
    }

    companion object {
        private const val ENABLED = "enabled"

        fun defaults(): Set<String> = Tools.all.filter { it.onByDefault }.map { it.name }.toSet()

        /** [names] that are real tools, in the catalogue's order (dropping any a newer version removed). */
        fun normalise(names: Set<String>): List<String> = Tools.all.map { it.name }.filter { it in names }
    }
}
