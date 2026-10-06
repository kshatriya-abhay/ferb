package dev.abhay.assistant.engine

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Needle 3, bundled in the APK, loaded once per process and kept loaded. Its tools can be swapped
 * at any time with [configure], which re-runs `needle_init` on the loaded model (checked on the
 * desktop: a second init replaces the tool set, and answers match a fresh start).
 */
class AssistantEngine(private val context: Context) {
    sealed interface State {
        data object Loading : State
        /** [tools] are the names Needle 3 has now; [prefixTokens] the length of facts plus schemas. */
        data class Ready(val loadMs: Long, val tools: List<String>, val prefixTokens: Int, val initMs: Long) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Mutex()
    private var loadMs: Long? = null
    private var factsDate: LocalDate? = null
    /** The tools last given to the model, to give them again with a new date. */
    private var lastTools: Pair<List<String>, String>? = null

    /** Loads the model if it isn't yet, then gives it [toolsJson] (the tools named [names]). */
    suspend fun configure(names: List<String>, toolsJson: String) = lock.withLock {
        try {
            if (loadMs == null) {
                _state.value = State.Loading
                val started = SystemClock.elapsedRealtime()
                Needle.loadAsset(context.assets, MODEL_ASSET)
                loadMs = SystemClock.elapsedRealtime() - started
            }
            val started = SystemClock.elapsedRealtime()
            val today = LocalDate.now()
            val prefix = Needle.init(systemFacts(today), toolsJson)
            factsDate = today
            _state.value = State.Ready(loadMs!!, names, prefix, SystemClock.elapsedRealtime() - started)
            lastTools = names to toolsJson
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: e.toString())
        }
    }

    /** Needle 3's JSON for [request]. Re-initialises first if the date has changed since, so "today" stays right. */
    suspend fun complete(request: String): String {
        if (factsDate != LocalDate.now()) lastTools?.let { (names, json) -> configure(names, json) }
        return lock.withLock { Needle.complete(request) }
    }

    companion object {
        const val MODEL_ASSET = "needle3.cact"

        /** Facts, not instructions: what the model may use to resolve requests. */
        fun systemFacts(date: LocalDate, locale: Locale = Locale.getDefault()): String =
            "date: ${date.format(DateTimeFormatter.ISO_LOCAL_DATE)} (${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)})\n" +
                "locale: ${locale.toLanguageTag()}\n" +
                "device: Android phone"
    }
}
