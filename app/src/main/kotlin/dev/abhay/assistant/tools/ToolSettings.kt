package dev.abhay.assistant.tools

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Every tool's configuration, kept in preferences as JSON per tool. Before any save, each tool's
 * defaults; an older install's list of tools turned on is carried over.
 */
class ToolSettings(context: Context) {
    private val prefs = context.getSharedPreferences("tools", Context.MODE_PRIVATE)

    private val _configs = MutableStateFlow(read())
    /** By tool name, for every tool in [Tools.all]. */
    val configs: StateFlow<Map<String, ToolConfig>> = _configs.asStateFlow()

    private val _enabled = MutableStateFlow(enabledNames(_configs.value))
    /** Names of the tools turned on, in [Tools.all] order. */
    val enabled: StateFlow<List<String>> = _enabled.asStateFlow()

    private val _checkAllAsFallback = MutableStateFlow(prefs.getBoolean(KEY_CHECK_ALL, false))
    /**
     * Whether a request no mentioned tool answers is also tried against every tool turned on (see
     * [ToolRouter.groups]). Off by default: on tool-check it added one right call that runs, and
     * made unrelated requests reach a tool (the calls ask first, since they aren't mentioned).
     */
    val checkAllAsFallback: StateFlow<Boolean> = _checkAllAsFallback.asStateFlow()

    /** Saved at once: it's on the settings home, apart from the tools' draft. */
    fun setCheckAllAsFallback(on: Boolean) {
        prefs.edit().putBoolean(KEY_CHECK_ALL, on).apply()
        _checkAllAsFallback.value = on
    }

    private fun read(): Map<String, ToolConfig> {
        val legacyEnabled = prefs.getStringSet(LEGACY_ENABLED, null)
        return Tools.all.associate { tool ->
            val json = prefs.getString(KEY_PREFIX + tool.name, null)
            tool.name to when {
                json != null -> ToolConfig.fromJson(json, tool)
                legacyEnabled != null -> ToolConfig.defaults(tool).copy(enabled = tool.name in legacyEnabled)
                else -> ToolConfig.defaults(tool)
            }
        }
    }

    fun save(configs: Map<String, ToolConfig>) {
        val complete = Tools.all.associate { it.name to (configs[it.name] ?: _configs.value.getValue(it.name)) }
        prefs.edit().apply {
            Tools.all.forEach { tool -> putString(KEY_PREFIX + tool.name, complete.getValue(tool.name).toJson(tool)) }
            remove(LEGACY_ENABLED)
        }.apply()
        _configs.value = complete
        _enabled.value = enabledNames(complete)
    }

    companion object {
        private const val KEY_PREFIX = "tool."
        private const val KEY_CHECK_ALL = "check_all_as_fallback"
        /** Phase 1 kept only a set of the tools turned on. */
        private const val LEGACY_ENABLED = "enabled"

        fun defaults(): Map<String, ToolConfig> = Tools.all.associate { it.name to ToolConfig.defaults(it) }

        /** The tools turned on in [configs], in the catalogue's order. */
        fun enabledNames(configs: Map<String, ToolConfig>): List<String> = Tools.all.map { it.name }.filter { configs[it]?.enabled == true }
    }
}
