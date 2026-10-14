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
import dev.abhay.assistant.link.Answerer
import dev.abhay.assistant.link.LinkMode
import dev.abhay.assistant.link.LinkPolicy
import dev.abhay.assistant.link.LinkStatus
import dev.abhay.assistant.link.Recorder
import dev.abhay.assistant.link.Transcript
import dev.abhay.assistant.link.brief
import dev.abhay.assistant.link.describe
import dev.abhay.assistant.tools.CallRunner
import dev.abhay.assistant.tools.Choice
import dev.abhay.assistant.tools.FollowUp
import dev.abhay.assistant.tools.FollowUpEnv
import dev.abhay.assistant.tools.Outcome
import dev.abhay.assistant.tools.PrepareEnv
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
class AssistantViewModel(private val app: Application) : AndroidViewModel(app) {

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
        val calls: List<CallState>,
        val wallMs: Long,
        /** Needle 3's answer, when the computer's replaced it (Ferb Link's "When Needle 3 is unsure"). */
        val needleReply: NeedleReply? = null,
        /** Why the computer's answer isn't shown, when Ferb asked it and it failed (Needle 3 answered): in a few words, and at length. */
        val laptopError: String? = null,
        val laptopErrorDetail: String? = null,
        /** For a spoken request: the words heard, when they differ from [request] (its English command). */
        val heard: String? = null,
        /** When no tool did it: the tools that are off but that the request mentions, offered to turn on. */
        val turnOn: List<String> = emptyList(),
    )

    data class UiState(
        val input: String = "",
        val busy: Boolean = false,
        /** Oldest first, like a chat. */
        val history: List<Exchange> = emptyList(),
        /** The request sent and not answered yet, shown at once with a "thinking" bubble. */
        val pending: String? = null,
        /** What the thinking bubble says, when it's more than thinking (waiting for the computer). */
        val asking: String? = null,
        val notice: String? = null,
        /** A spoken request being recorded, or recorded and waiting for Send. */
        val voice: Voice? = null,
    )

    sealed interface Voice {
        data class Recording(val startedAt: Long) : Voice
        /** [audio] is [Recorder.MIME], [ms] long. */
        class Recorded(val audio: ByteArray, val ms: Long) : Voice
    }

    private val assistant = app as AssistantApp
    val engine: StateFlow<AssistantEngine.State> = assistant.engine.state
    val enabledTools: StateFlow<List<String>> = assistant.toolSettings.enabled
    /** How Ferb Link is used (Settings → Ferb Link), and who answers when the chat box offers a choice. */
    val linkMode: StateFlow<LinkMode> = assistant.linkSettings.mode
    val answerer: StateFlow<Answerer> = assistant.linkSettings.answerer
    fun setAnswerer(answerer: Answerer) {
        // Voice goes only through Ferb Link.
        if (answerer != Answerer.Link) discardRecording()
        assistant.linkSettings.setAnswerer(answerer)
        if (answerer == Answerer.Link) wakeComputer()
    }
    val link get() = assistant.link
    /**
     * Turns on [tool], offered under a reply no tool could do ([Exchange.turnOn]), and asks again.
     * Its Android permissions, if any, are asked for by the screen at the same time.
     */
    fun turnOn(exchangeId: Long, tool: String) {
        val exchange = _state.value.history.firstOrNull { it.id == exchangeId } ?: return
        val configs = assistant.toolSettings.configs.value
        val config = configs[tool] ?: return
        assistant.toolSettings.save(configs + (tool to config.copy(enabled = true)))
        updateExchange(exchangeId) { it.copy(turnOn = emptyList()) }
        send(exchange.request)
    }

    /** The welcome screen and the one-time tips. */
    val onboarding get() = assistant.onboarding

    private var wokeAt = 0L

    /**
     * Has Ferb Link load the computer's model if it's asleep or unloaded, when a request is likely:
     * the chat box tapped with the computer picked, or the computer just picked. A cold load took
     * 2–28 s, longer than the 10 s Ferb waits for an answer; started on the tap, most of it hides
     * behind the typing. A loaded model costs Ferb Link two small requests, so at most once a minute.
     */
    fun wakeComputer() {
        val now = SystemClock.elapsedRealtime()
        if (wokeAt != 0L && now - wokeAt < WAKE_EVERY_MS) return
        wokeAt = now
        viewModelScope.launch { assistant.link.wake() }
    }

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
        if (_state.value.voice is Voice.Recorded) return sendVoice()
        val request = text.trim()
        if (request.isEmpty() || _state.value.busy || engine.value !is AssistantEngine.State.Ready) return
        _state.update { it.copy(busy = true, input = "", notice = null, pending = request) }
        viewModelScope.launch { answer(request, heard = null) }
    }

    /** Answers [request] (typed, or the English command of a spoken one, [heard] as said) and runs what it can. */
    private suspend fun answer(request: String, heard: String?) {
        try {
            val started = SystemClock.elapsedRealtime()
            val enabled = enabledTools.value.mapNotNull(Tools::byName)
            val configs = assistant.toolSettings.configs.value
            val routed = ToolRouter.route(request, enabled, configs)
            // Ferb Link, as chosen in Settings: the computer's model answers first when Ferb Link
            // is picked in the chat box ("Option"), or after Needle 3 when it's unsure or finds no
            // tool ("Fallback"). Needle 3 answers whenever the computer can't.
            val mode = assistant.linkSettings.mode.value
            val chosen = LinkPolicy.laptopFirst(mode, assistant.linkSettings.answerer.value)
            var laptop: NeedleReply? = null
            var laptopError: String? = null
            var laptopErrorDetail: String? = null
            suspend fun askLaptop() {
                val status = assistant.link.status()
                if (status is LinkStatus.Ready) {
                    _state.update { it.copy(asking = "Asking your computer\u2026") }
                    laptop = assistant.link.complete(request, Tools.json(routed.ifEmpty { enabled }))
                    laptopError = laptop?.error
                    laptopErrorDetail = laptop?.let { it.errorDetail ?: it.error }
                } else if (chosen) {
                    // Picked in the chat box, so say why it didn't answer; in Fallback, a Ferb Link
                    // that's off or not set up is simply not asked.
                    laptopError = brief(status)
                    laptopErrorDetail = describe(status)
                }
            }
            if (chosen && enabled.isNotEmpty()) askLaptop()
            var needle: NeedleReply? = null
            if (laptop?.error != null || laptop == null) {
                _state.update { it.copy(asking = null) }
                val reply = askNeedle(request, enabled, routed)
                needle = reply
                if (!chosen && mode == LinkMode.Fallback && enabled.isNotEmpty() && reply.error == null &&
                    LinkPolicy.askLaptopAfter(reply, Verdict.of(reply, configs, request))) askLaptop()
            }
            // The computer's answer whenever it gave one, including "no tool fits".
            val shown = laptop?.takeIf { it.error == null } ?: needle!!
            val verdict = if (shown.fromLaptop) LinkPolicy.verdict(shown, configs, request) else Verdict.of(shown, configs, request)
            val calls = shown.calls.map { call -> CallState(call, prepare(call, request), Status.Waiting) }
            val exchange = Exchange(nextId++, request, shown, verdict.decision, verdict.runAt, verdict.alwaysConfirm, verdict.unmentioned, verdict.ungrounded,
                routed.map { it.name }, calls, SystemClock.elapsedRealtime() - started,
                needleReply = needle.takeIf { shown !== it },
                laptopError = laptopError.takeIf { !shown.fromLaptop },
                laptopErrorDetail = laptopErrorDetail.takeIf { !shown.fromLaptop },
                heard = heard,
                turnOn = if (shown.calls.isEmpty() && shown.error == null) ToolRouter.off(request, configs).map { it.name } else emptyList())
            _state.update { it.copy(history = it.history + exchange, pending = null, asking = null) }
            if (verdict.decision == Decision.Run) run(exchange.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(notice = e.message ?: e.toString(), input = request) }
        } finally {
            _state.update { it.copy(busy = false, pending = null, asking = null) }
        }
    }

    private val recorder by lazy { Recorder(app) }

    /**
     * Starts recording a spoken request (the microphone permission is granted), and has Ferb Link
     * wake the computer's model meanwhile.
     */
    fun startRecording() {
        if (_state.value.busy || recorder.recording) return
        try {
            recorder.start(onLimit = ::stopRecording)
        } catch (e: Exception) {
            _state.update { it.copy(notice = "The microphone can't be used: ${e.message ?: e}") }
            return
        }
        _state.update { it.copy(voice = Voice.Recording(SystemClock.elapsedRealtime()), notice = null) }
        viewModelScope.launch { assistant.link.wake() }
    }

    /** Stops recording; the recording waits for Send (or [discardRecording]). */
    fun stopRecording() {
        val recording = _state.value.voice as? Voice.Recording ?: return
        val ms = SystemClock.elapsedRealtime() - recording.startedAt
        val audio = recorder.stop()
        _state.update {
            it.copy(voice = audio?.let { a -> Voice.Recorded(a, ms) },
                notice = if (audio == null) "Nothing recorded: tap the mic, speak, then tap it again" else null)
        }
    }

    fun discardRecording() {
        recorder.cancel()
        _state.update { it.copy(voice = null) }
    }

    /**
     * Sends the recording to the computer through Ferb Link, then answers its English command like a
     * typed request. When the computer fails, the recording is kept, to send again.
     */
    private fun sendVoice() {
        val clip = _state.value.voice as? Voice.Recorded ?: return
        if (_state.value.busy || engine.value !is AssistantEngine.State.Ready) return
        _state.update { it.copy(busy = true, voice = null, notice = null, pending = voiceLabel(clip.ms), asking = "Your computer is listening…") }
        viewModelScope.launch {
            when (val next = VoiceOutcome.of(assistant.link.transcribe(clip.audio, Recorder.MIME))) {
                is VoiceOutcome.Failed ->
                    _state.update { it.copy(busy = false, pending = null, asking = null, voice = clip.takeIf { next.keepClip }, notice = next.notice) }
                is VoiceOutcome.Answer -> {
                    _state.update { it.copy(pending = next.request, asking = null) }
                    answer(next.request, heard = next.heard)
                }
            }
        }
    }

    override fun onCleared() = recorder.cancel()

    /**
     * Needle 3's answer: from the tools [routed] to (the request mentions them), else from every tool
     * turned on, as Needle 3 weighs the five that match best (its own retrieval). Until 2026-10-12
     * a setting could check every tool, five at a time, instead; removed: on tool-check it added
     * one right call that runs and let unrelated requests reach tools, and Ferb Link now covers
     * Needle 3 finding nothing.
     */
    private suspend fun askNeedle(request: String, enabled: List<Tool>, routed: List<Tool>): NeedleReply = when {
        routed.isNotEmpty() -> ask(request, routed)
        enabled.isEmpty() -> NO_TOOL
        else -> ask(request, enabled)
    }

    private suspend fun ask(request: String, tools: List<Tool>): NeedleReply =
        NeedleReply.parse(assistant.engine.complete(request, tools.map { it.name }, Tools.json(tools)))

    /** Checks a call before anything runs: the tool must be turned on, and its arguments valid. */
    private fun prepare(call: ToolCall, request: String): Prepared {
        val tool = Tools.byName(call.name)?.takeIf { it.name in enabledTools.value }
            ?: return Prepared.Invalid("No tool called ${call.name} is turned on")
        val env = PrepareEnv(assistant.apps, assistant.contacts, assistant.calendar, notes = assistant.notes,
            options = assistant.toolSettings.configs.value[tool.name]?.options.orEmpty())
        return tool.prepare(tool.fromRequest(call.arguments, request), env)
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

    /** Makes [tree], picked by the user, the notes folder (kept across restarts). */
    fun pickNotesFolder(tree: android.net.Uri) = assistant.notes.pick(tree)

    /** A short message at the bottom of the screen. */
    fun notify(text: String) = _state.update { it.copy(notice = text) }

    private fun updateExchange(id: Long, change: (Exchange) -> Exchange) =
        _state.update { s -> s.copy(history = s.history.map { if (it.id == id) change(it) else it }) }

    private companion object {
        const val WAKE_EVERY_MS = 60_000L

        /** The answer when no tool is on. */
        val NO_TOOL = NeedleReply(
            calls = emptyList(), suppressed = emptyList(), reasoning = "", confidence = null, ungrounded = emptyList(),
            negation = false, prefillTps = 0.0, decodeTps = 0.0, peakRamMb = 0.0, error = null,
        )
    }
}

/**
 * What a transcription leads to: answering its English command (with what was heard, when that
 * differs), or a notice. A failure keeps the recording, to send again; no words don't.
 */
internal sealed interface VoiceOutcome {
    data class Answer(val request: String, val heard: String?) : VoiceOutcome
    data class Failed(val notice: String, val keepClip: Boolean) : VoiceOutcome

    companion object {
        fun of(transcript: Transcript): VoiceOutcome = when {
            transcript.error != null -> Failed("Your computer couldn't hear it: ${transcript.error.replaceFirstChar(Char::lowercase)}", keepClip = true)
            transcript.text.isBlank() -> Failed("No words heard: try again, a little closer", keepClip = false)
            else -> Answer(transcript.text, transcript.heard.takeUnless { it.isBlank() || it.equals(transcript.text, ignoreCase = true) })
        }
    }
}

/** "Voice request · 0:04", for the request's bubble until the computer has heard it. */
internal fun voiceLabel(ms: Long): String = String.format(java.util.Locale.US, "Voice request \u00b7 %d:%02d", ms / 60_000, ms / 1000 % 60)
