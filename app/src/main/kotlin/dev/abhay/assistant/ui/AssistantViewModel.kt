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
import dev.abhay.assistant.tools.Outcome
import dev.abhay.assistant.tools.Prepared
import dev.abhay.assistant.tools.Tools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The assistant: loads Needle 3 with the tools turned on in settings, sends each request to it,
 * checks the calls it returns, and runs them: at once when it's sure (confidence ≥ 0.7), after the
 * user's OK when it's less sure, never when it found no tool for the request.
 */
class AssistantViewModel(app: Application) : AndroidViewModel(app) {

    /** One call from a reply: checked, and what became of it. */
    data class CallState(val call: ToolCall, val prepared: Prepared, val status: Status)

    sealed interface Status {
        /** Waiting for the user's Run or Skip. */
        data object Waiting : Status
        data object Skipped : Status
        data class Finished(val outcome: Outcome) : Status
    }

    data class Exchange(val id: Long, val request: String, val reply: NeedleReply, val decision: Decision, val calls: List<CallState>, val wallMs: Long)

    data class UiState(
        val input: String = "",
        val busy: Boolean = false,
        /** Oldest first, like a chat. */
        val history: List<Exchange> = emptyList(),
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
        _state.update { it.copy(busy = true, input = "", notice = null) }
        viewModelScope.launch {
            try {
                val started = SystemClock.elapsedRealtime()
                val reply = NeedleReply.parse(assistant.engine.complete(request))
                val wallMs = SystemClock.elapsedRealtime() - started
                val decision = if (reply.error != null) Decision.Refuse else Decision.of(reply)
                val calls = reply.calls.map { call -> CallState(call, prepare(call), Status.Waiting) }
                val exchange = Exchange(nextId++, request, reply, decision, calls, wallMs)
                _state.update { it.copy(history = it.history + exchange) }
                if (decision == Decision.Run) run(exchange.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(notice = e.message ?: e.toString(), input = request) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Checks a call before anything runs: the tool must be turned on, and its arguments valid. */
    private fun prepare(call: ToolCall): Prepared {
        val tool = Tools.byName(call.name)?.takeIf { it.name in enabledTools.value }
            ?: return Prepared.Invalid("No tool called ${call.name} is turned on")
        return tool.prepare(call.arguments, assistant.apps)
    }

    /** Runs an exchange's waiting calls, in order; a call that failed its checks reports why instead. */
    fun run(exchangeId: Long) = updateExchange(exchangeId) { exchange ->
        exchange.copy(calls = exchange.calls.map { state ->
            if (state.status != Status.Waiting) state
            else state.copy(status = Status.Finished(when (val p = state.prepared) {
                is Prepared.Ready -> p.run(getApplication())
                is Prepared.Invalid -> Outcome.Failed(p.reason)
            }))
        })
    }

    fun skip(exchangeId: Long) = updateExchange(exchangeId) { e ->
        e.copy(calls = e.calls.map { if (it.status == Status.Waiting) it.copy(status = Status.Skipped) else it })
    }

    fun clearHistory() = _state.update { it.copy(history = emptyList()) }

    private fun updateExchange(id: Long, change: (Exchange) -> Exchange) =
        _state.update { s -> s.copy(history = s.history.map { if (it.id == id) change(it) else it }) }
}
