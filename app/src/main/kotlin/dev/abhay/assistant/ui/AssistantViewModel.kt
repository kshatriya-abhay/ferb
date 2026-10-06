package dev.abhay.assistant.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.abhay.assistant.AssistantApp
import dev.abhay.assistant.engine.AssistantEngine
import dev.abhay.assistant.engine.Decision
import dev.abhay.assistant.engine.NeedleReply
import dev.abhay.assistant.engine.ToolCall
import dev.abhay.assistant.tools.CallRunner
import dev.abhay.assistant.tools.FollowUp
import dev.abhay.assistant.tools.Outcome
import dev.abhay.assistant.tools.Prepared
import dev.abhay.assistant.tools.Tools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The assistant: loads Needle 3 with the tools turned on in settings, sends each request to it,
 * checks the calls it returns, and runs them: at once when it's sure enough for the tools it calls
 * (each tool has its own bar, [dev.abhay.assistant.tools.Tool.runFrom]), after the user's OK when
 * it's less sure, never when it found no tool for the request. Results can offer buttons (Turn off,
 * Undo…), which run follow-up actions.
 */
class AssistantViewModel(app: Application) : AndroidViewModel(app) {

    /** One call from a reply: checked, and what became of it. */
    data class CallState(val call: ToolCall, val prepared: Prepared, val status: Status)

    sealed interface Status {
        /** Waiting for the user's Run or Skip. */
        data object Waiting : Status
        /** About to run, after the calls before it. */
        data object Queued : Status
        data object Skipped : Status
        data class Finished(val outcome: Outcome) : Status
    }

    /** [runAt] is the confidence the reply needed to run without asking: the highest bar among the tools it calls. */
    data class Exchange(
        val id: Long,
        val request: String,
        val reply: NeedleReply,
        val decision: Decision,
        val runAt: Double,
        val calls: List<CallState>,
        val wallMs: Long,
    )

    data class UiState(
        val input: String = "",
        val busy: Boolean = false,
        /** Oldest first, like a chat. */
        val history: List<Exchange> = emptyList(),
        /** The request sent and not answered yet, shown at once with a "thinking" bubble. */
        val pending: String? = null,
        val notice: String? = null,
    )

    private val assistant = app as AssistantApp
    val engine: StateFlow<AssistantEngine.State> = assistant.engine.state
    val enabledTools: StateFlow<List<String>> = assistant.toolSettings.enabled

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var nextId = 0L

    init {
        // Loads the model on first use in this process; afterwards it only re-applies the tools.
        if (engine.value !is AssistantEngine.State.Ready) viewModelScope.launch { applyTools(enabledTools.value) }
    }

    /** Saves the tools turned on, and gives them to Needle 3 (the loaded model, re-initialised). */
    fun saveTools(names: Set<String>) {
        assistant.toolSettings.save(names)
        viewModelScope.launch { applyTools(enabledTools.value) }
    }

    fun retry() {
        viewModelScope.launch { applyTools(enabledTools.value) }
    }

    private suspend fun applyTools(names: List<String>) {
        val tools = names.mapNotNull(Tools::byName)
        assistant.engine.configure(tools.map { it.name }, Tools.json(tools))
    }

    fun onInputChange(text: String) = _state.update { it.copy(input = text) }

    fun send(text: String = _state.value.input) {
        val request = text.trim()
        if (request.isEmpty() || _state.value.busy || engine.value !is AssistantEngine.State.Ready) return
        _state.update { it.copy(busy = true, input = "", notice = null, pending = request) }
        viewModelScope.launch {
            try {
                val started = SystemClock.elapsedRealtime()
                val reply = NeedleReply.parse(assistant.engine.complete(request))
                val wallMs = SystemClock.elapsedRealtime() - started
                val runAt = runAt(reply.calls)
                val decision = if (reply.error != null) Decision.Refuse else Decision.of(reply, runAt)
                val calls = reply.calls.map { call -> CallState(call, prepare(call), Status.Waiting) }
                val exchange = Exchange(nextId++, request, reply, decision, runAt, calls, wallMs)
                _state.update { it.copy(history = it.history + exchange, pending = null) }
                if (decision == Decision.Run) run(exchange.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(notice = e.message ?: e.toString(), input = request) }
            } finally {
                _state.update { it.copy(busy = false, pending = null) }
            }
        }
    }

    /** The highest bar among the tools [calls] name (an unknown tool counts as Cactus's 0.7). */
    private fun runAt(calls: List<ToolCall>): Double =
        calls.maxOfOrNull { Tools.byName(it.name)?.runFrom ?: Decision.RUN_AT } ?: Decision.RUN_AT

    /** Checks a call before anything runs: the tool must be turned on, and its arguments valid. */
    private fun prepare(call: ToolCall): Prepared {
        val tool = Tools.byName(call.name)?.takeIf { it.name in enabledTools.value }
            ?: return Prepared.Invalid("No tool called ${call.name} is turned on")
        return tool.prepare(call.arguments, assistant.apps)
    }

    private val runner by lazy { CallRunner(assistant.foreground.inFront) }
    private val running = Mutex()

    /**
     * Runs an exchange's waiting calls, one at a time (see [CallRunner]); a call that failed its
     * checks reports why instead. Each outcome shows as soon as it's known.
     */
    fun run(exchangeId: Long) {
        val waiting = _state.value.history.firstOrNull { it.id == exchangeId }?.calls?.indices
            ?.filter { i -> _state.value.history.first { it.id == exchangeId }.calls[i].status == Status.Waiting }.orEmpty()
        if (waiting.isEmpty()) return
        updateExchange(exchangeId) { e -> e.copy(calls = e.calls.mapIndexed { i, c -> if (i in waiting) c.copy(status = Status.Queued) else c }) }
        viewModelScope.launch {
            running.withLock {
                fun call(i: Int) = _state.value.history.first { it.id == exchangeId }.calls[i]
                runner.run(
                    calls = waiting,
                    opensApp = { i -> (call(i).prepared as? Prepared.Ready)?.opensApp == true },
                    execute = { i ->
                        when (val p = call(i).prepared) {
                            is Prepared.Ready -> p.run(getApplication())
                            is Prepared.Invalid -> Outcome.Failed(p.reason)
                        }
                    },
                    report = { i, outcome ->
                        updateExchange(exchangeId) { e ->
                            e.copy(calls = e.calls.mapIndexed { j, c -> if (j == i) c.copy(status = Status.Finished(outcome)) else c })
                        }
                    },
                )
            }
        }
    }

    /** Runs a result's button: e.g. "Turn off" after the torch went on. The call's result becomes the new one. */
    fun followUp(exchangeId: Long, callIndex: Int, action: FollowUp) {
        val outcome = action.run(getApplication())
        updateExchange(exchangeId) { e ->
            e.copy(calls = e.calls.mapIndexed { i, c -> if (i == callIndex) c.copy(status = Status.Finished(outcome)) else c })
        }
    }

    fun skip(exchangeId: Long) = updateExchange(exchangeId) { e ->
        e.copy(calls = e.calls.map { if (it.status == Status.Waiting) it.copy(status = Status.Skipped) else it })
    }

    fun clearHistory() = _state.update { it.copy(history = emptyList()) }

    private fun updateExchange(id: Long, change: (Exchange) -> Exchange) =
        _state.update { s -> s.copy(history = s.history.map { if (it.id == id) change(it) else it }) }
}
