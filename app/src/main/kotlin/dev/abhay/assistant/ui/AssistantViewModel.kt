package dev.abhay.assistant.ui

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.abhay.assistant.AssistantApp
import dev.abhay.assistant.engine.AssistantEngine
import dev.abhay.assistant.engine.Decision
import dev.abhay.assistant.engine.NeedleReply
import dev.abhay.assistant.engine.ToolCall
import dev.abhay.assistant.tools.CallRunner
import dev.abhay.assistant.tools.Choice
import dev.abhay.assistant.tools.FollowUp
import dev.abhay.assistant.tools.FollowUpEnv
import dev.abhay.assistant.tools.Outcome
import dev.abhay.assistant.tools.Prepared
import dev.abhay.assistant.tools.Tool
import dev.abhay.assistant.tools.ToolRouter
import dev.abhay.assistant.tools.Tools
import dev.abhay.assistant.tools.Verdict
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
 * (each tool has its own bar and can be set to always ask: [dev.abhay.assistant.tools.Verdict]), after the user's OK when
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

    /**
     * [runAt] is the confidence the reply needed to run without asking: the highest bar among the
     * tools it calls. [alwaysConfirm] is set when one of them is set to always ask.
     */
    data class Exchange(
        val id: Long,
        val request: String,
        val reply: NeedleReply,
        val decision: Decision,
        val runAt: Double,
        val alwaysConfirm: Boolean,
        /** Set when the request doesn't mention a tool it reached (see [dev.abhay.assistant.tools.ToolConfig.keywords]). */
        val unmentioned: Boolean,
        /** Set when a call has a value the request doesn't say (see [dev.abhay.assistant.tools.Tool.grounded]). */
        val ungrounded: Boolean,
        /** The tools the request mentioned, which Needle 3 was given ([ToolRouter]); empty: none, so it got every tool turned on. */
        val routed: List<String>,
        /** Set when every tool was also tried, because the mentioned ones gave no call. */
        val checkedAll: Boolean,
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
        // Loads the model on first use in this process. Each request then gets the tools it
        // mentions (ToolRouter), so saving other tools needs no reload here.
        viewModelScope.launch {
            enabledTools.collect { names -> if (engine.value !is AssistantEngine.State.Ready) applyTools(names) }
        }
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
                val enabled = enabledTools.value.mapNotNull(Tools::byName)
                val configs = assistant.toolSettings.configs.value
                val routed = ToolRouter.route(request, enabled, configs)
                val checkAllOn = assistant.toolSettings.checkAllAsFallback.value
                // No tool mentioned: every tool turned on, and Needle 3 weighs the five that match
                // best (its own retrieval), unless the user chose to check them all.
                var reply = when {
                    routed.isNotEmpty() -> ask(request, routed)
                    checkAllOn || enabled.isEmpty() -> NO_TOOL_MENTIONED
                    else -> ask(request, enabled)
                }
                val checkAll = reply.calls.isEmpty() && reply.error == null && checkAllOn && enabled.isNotEmpty()
                if (checkAll) {
                    // Every tool, five at a time; the most confident answer with a call wins.
                    ToolRouter.groups(enabled).filter { it != routed }.forEach { group ->
                        val answer = ask(request, group)
                        if (answer.calls.isNotEmpty() && (answer.confidence ?: 0.0) > (reply.confidence.takeIf { reply.calls.isNotEmpty() } ?: -1.0)) reply = answer
                    }
                }
                val wallMs = SystemClock.elapsedRealtime() - started
                val verdict = Verdict.of(reply, configs, request)
                val calls = reply.calls.map { call -> CallState(call, prepare(call, request), Status.Waiting) }
                val exchange = Exchange(nextId++, request, reply, verdict.decision, verdict.runAt, verdict.alwaysConfirm, verdict.unmentioned, verdict.ungrounded,
                    routed.map { it.name }, checkAll, calls, wallMs)
                _state.update { it.copy(history = it.history + exchange, pending = null) }
                if (verdict.decision == Decision.Run) run(exchange.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(notice = e.message ?: e.toString(), input = request) }
            } finally {
                _state.update { it.copy(busy = false, pending = null) }
            }
        }
    }

    private suspend fun ask(request: String, tools: List<Tool>): NeedleReply =
        NeedleReply.parse(assistant.engine.complete(request, tools.map { it.name }, Tools.json(tools)))

    /** Checks a call before anything runs: the tool must be turned on, and its arguments valid. */
    private fun prepare(call: ToolCall, request: String): Prepared {
        val tool = Tools.byName(call.name)?.takeIf { it.name in enabledTools.value }
            ?: return Prepared.Invalid("No tool called ${call.name} is turned on")
        return tool.prepare(tool.fromRequest(call.arguments, request), assistant.apps, assistant.contacts)
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
                            is Prepared.Ready -> p.run(launchContext())
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

    /**
     * Runs a result's button: e.g. "Turn off" after the torch went on. The call shows as queued
     * while it runs (an Undo may wait for another app), then its result becomes the new one.
     */
    fun followUp(exchangeId: Long, callIndex: Int, action: FollowUp) {
        fun set(status: Status) = updateExchange(exchangeId) { e ->
            e.copy(calls = e.calls.mapIndexed { i, c -> if (i == callIndex) c.copy(status = status) else c })
        }
        set(Status.Queued)
        viewModelScope.launch {
            val outcome = try {
                action.run(FollowUpEnv(launchContext(), awaitBack = runner::awaitBack))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome.Failed(e.message ?: e.toString())
            }
            set(Status.Finished(outcome))
        }
    }

    /** A result's slider moved: applies [value] (e.g. sets the volume) and shows the new result. */
    fun adjust(exchangeId: Long, callIndex: Int, value: Float) = updateExchange(exchangeId) { e ->
        e.copy(calls = e.calls.mapIndexed { i, c ->
            val done = (c.status as? Status.Finished)?.outcome as? Outcome.Done
            val level = done?.level
            if (i != callIndex || level == null) c
            else c.copy(status = Status.Finished(done.copy(message = level.set(getApplication(), value), level = level.copy(value = value))))
        })
    }

    /** The assistant's screen if it's in front (so a quiet request runs in its task), else the app. */
    private fun launchContext(): Context = assistant.foreground.activity ?: getApplication()

    /**
     * The user picked [choice] for a call that asked (a contact, or "Open dialer"): it becomes the
     * call, and the exchange's waiting calls run.
     */
    fun choose(exchangeId: Long, callIndex: Int, choice: Choice) {
        updateExchange(exchangeId) { e ->
            e.copy(calls = e.calls.mapIndexed { i, c ->
                val ready = c.prepared as? Prepared.Ready
                if (i != callIndex || ready == null) c else c.copy(prepared = Prepared.Ready(ready.summary, ready.opensApp, run = choice.run))
            })
        }
        run(exchangeId)
    }

    fun skip(exchangeId: Long) = updateExchange(exchangeId) { e ->
        e.copy(calls = e.calls.map { if (it.status == Status.Waiting) it.copy(status = Status.Skipped) else it })
    }

    fun clearHistory() = _state.update { it.copy(history = emptyList()) }

    private fun updateExchange(id: Long, change: (Exchange) -> Exchange) =
        _state.update { s -> s.copy(history = s.history.map { if (it.id == id) change(it) else it }) }

    private companion object {
        /** The answer before any is asked for, when a request mentions no tool and the check of all tools follows (or no tool is on). */
        val NO_TOOL_MENTIONED = NeedleReply(
            calls = emptyList(), suppressed = emptyList(), reasoning = "", confidence = null, ungrounded = emptyList(),
            negation = false, prefillTps = 0.0, decodeTps = 0.0, peakRamMb = 0.0, error = null,
        )
    }
}
