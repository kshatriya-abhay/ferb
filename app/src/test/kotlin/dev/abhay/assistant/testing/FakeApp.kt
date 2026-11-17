package dev.abhay.assistant.testing

import android.content.ContextWrapper
import android.content.SharedPreferences
import dev.abhay.assistant.AssistantApp
import dev.abhay.assistant.engine.AssistantEngine
import dev.abhay.assistant.link.FerbLink
import dev.abhay.assistant.pipeline.PipelineSettings
import dev.abhay.assistant.tools.ToolSettings
import dev.abhay.assistant.voice.NanoEars
import dev.abhay.assistant.ui.AssistantViewModel
import dev.abhay.assistant.ui.ChatSession
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * An [AssistantApp] for JVM tests of the view model: settings in memory, the engine ready, and
 * AICore's checks held so nothing calls ML Kit. Built without its constructor, its lazy fields set
 * by reflection: it knows their names, so a rename in [AssistantApp] means a change here. Until the
 * voice and session state machine leaves the view model (review 8.4), this is the only way to run
 * the view model itself on the JVM.
 */
object FakeApp {
    fun create(): AssistantApp {
        // Each app starts as a fresh one: its own preferences, and the conversation the process
        // shares (ChatSession) emptied (review P2-179: one JVM-wide map and session leaked between tests).
        ChatSession.state.value = AssistantViewModel.UiState()
        ChatSession.exchangeIds.set(0)
        (field(ChatSession, "following") as AtomicBoolean).set(false)
        val context = allocate(PrefsContext::class.java)
        val app = allocate(AssistantApp::class.java)
        val engine = AssistantEngine(context)
        @Suppress("UNCHECKED_CAST")
        (field(engine, "_state") as MutableStateFlow<AssistantEngine.State>).value = AssistantEngine.State.Ready(0, emptyList(), 0, 0)
        val nano = NanoEars(MutableStateFlow(false), now = { 0L })
        // AICore's checks never answer here, so nothing calls ML Kit.
        (field(nano, "pacing") as Mutex).tryLock()
        set(app, "engine\$delegate", lazyOf(engine))
        set(app, "toolSettings\$delegate", lazyOf(ToolSettings(context)))
        set(app, "pipeline\$delegate", lazyOf(PipelineSettings(context)))
        set(app, "link\$delegate", lazyOf(FerbLink(context)))
        set(app, "nanoEars\$delegate", lazyOf(nano))
        set(app, "geminiFollow", AtomicBoolean(true))
        return app
    }

    /** Preferences in memory, this context's own (allocated without a constructor, so set on first use). */
    class PrefsContext : ContextWrapper(null) {
        private var prefs: MutableMap<String, MemoryPrefs>? = null
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            (prefs ?: mutableMapOf<String, MemoryPrefs>().also { prefs = it }).getOrPut(name) { MemoryPrefs() }
    }

    class MemoryPrefs : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = values[key] as MutableSet<String>? ?: defValues
        override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
        override fun contains(key: String) = key in values
        override fun edit(): SharedPreferences.Editor = Editor(this)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    class Editor(private val to: MemoryPrefs) : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { changes[key] = values }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) to.values.clear()
            removed.forEach { to.values.remove(it) }
            to.values.putAll(changes)
        }
    }

    fun <T> allocate(type: Class<T>): T {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        @Suppress("UNCHECKED_CAST")
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }

    fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
