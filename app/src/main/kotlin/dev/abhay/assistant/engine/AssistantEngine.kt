package dev.abhay.assistant.engine

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.abhay.assistant.voice.Heard
import dev.abhay.assistant.voice.PcmRecorder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Needle 3, bundled in the APK, loaded once per process and kept loaded. Its tools can be swapped
 * at any time with [configure], which re-runs `needle_init` on the loaded model (checked on the
 * desktop: a second init replaces the tool set, and answers match a fresh start); [complete] does
 * so when a request is routed to other tools.
 *
 * Whistle (Phone-mode voice) loads beside it on the first spoken request and stays loaded too: the
 * engine holds one text and one speech model, and has no unload.
 */
class AssistantEngine(
    private val context: Context,
    /** The native engine; [NeedleCalls] (JNI) in the app, a fake in tests. */
    private val native: EngineCalls = NeedleCalls(context),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
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
    suspend fun configure(names: List<String>, toolsJson: String) = lock.withLock { configureLocked(names, toolsJson) }

    /** [configure]'s work, with [lock] held. */
    private suspend fun configureLocked(names: List<String>, toolsJson: String) {
        try {
            // The native load and init, and what records them, finish together even if the screen
            // that asked closes meanwhile: otherwise the model is loaded but not known to be, the next
            // configure loads it again, and every screen reads the cancel as a failure (review P2-89).
            withContext(NonCancellable) {
                if (loadMs == null) {
                    _state.value = State.Loading
                    val started = clock()
                    native.load(MODEL_ASSET)
                    loadMs = clock() - started
                }
                val started = clock()
                val today = LocalDate.now()
                val prefix = native.init(systemFacts(today), toolsJson)
                factsDate = today
                _state.value = State.Ready(loadMs!!, names, prefix, clock() - started)
                lastTools = names to toolsJson
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: e.toString())
        }
    }

    /**
     * Needle 3's JSON for [request], with [names] as its tools ([toolsJson]). Re-initialises first
     * when they aren't the tools it has (requests are routed to different tools), or the date has
     * changed since, so "today" stays right.
     */
    suspend fun complete(request: String, names: List<String>, toolsJson: String): String = lock.withLock {
        // One lock section for the check, the re-init and the call: two screens' requests (the chat
        // and the assistant sheet) can't swap the tools between another's check and its call.
        if (lastTools?.first != names || factsDate != LocalDate.now()) {
            configureLocked(names, toolsJson)
            (_state.value as? State.Failed)?.let { throw IllegalStateException(it.message) }
        }
        native.complete(request)
    }

    private var whistleLoadMs: Long? = null

    /** How long Whistle took to load, once it has. */
    val whistleLoaded get() = whistleLoadMs

    /**
     * What Whistle hears in [pcm] (16 kHz mono float, at most 30 s), in English: Needle 3's tools
     * are English, and Hindi or Hinglish speech goes to the computer. Loads Whistle the first time.
     */
    suspend fun transcribe(pcm: FloatArray): Heard = lock.withLock {
        if (whistleLoadMs == null) withContext(NonCancellable) {
            // As Needle 3's load: finished and recorded together, so a cancel never loads it twice.
            val started = clock()
            native.load(WHISTLE_ASSET)
            whistleLoadMs = clock() - started
        }
        val started = clock()
        val json = native.transcribe(pcm, "en")
        Heard.parse(json, clock() - started, pcm.size * 1000L / PcmRecorder.SAMPLE_RATE)
    }

    companion object {
        const val MODEL_ASSET = "needle3.cact"
        const val WHISTLE_ASSET = "whistle.cact"

        /** Facts, not instructions: what the model may use to resolve requests. */
        fun systemFacts(date: LocalDate, locale: Locale = Locale.getDefault()): String =
            "date: ${date.format(DateTimeFormatter.ISO_LOCAL_DATE)} (${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)})\n" +
                "locale: ${locale.toLanguageTag()}\n" +
                "device: Android phone"
    }
}
