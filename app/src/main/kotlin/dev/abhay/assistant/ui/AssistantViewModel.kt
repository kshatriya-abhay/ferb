package dev.abhay.assistant.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.abhay.assistant.AssistantApp
import dev.abhay.assistant.Private
import dev.abhay.assistant.engine.AssistantEngine
import dev.abhay.assistant.engine.Decision
import dev.abhay.assistant.engine.NeedleReply
import dev.abhay.assistant.engine.ToolCall
import dev.abhay.assistant.link.JevReply
import dev.abhay.assistant.link.LinkStatus
import dev.abhay.assistant.link.brief
import dev.abhay.assistant.pipeline.AudioStep
import dev.abhay.assistant.pipeline.Fallback
import dev.abhay.assistant.pipeline.Pipeline
import dev.abhay.assistant.pipeline.RewriteStep
import dev.abhay.assistant.pipeline.ToolStep
import dev.abhay.assistant.voice.AacEncoder
import dev.abhay.assistant.voice.EnergyVad
import dev.abhay.assistant.voice.Endpoint
import dev.abhay.assistant.voice.Part
import dev.abhay.assistant.link.Transcript
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
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import dev.abhay.assistant.voice.LiveTranscript
import dev.abhay.assistant.voice.NanoChoice
import dev.abhay.assistant.voice.NanoEars
import dev.abhay.assistant.voice.PcmRecorder
import dev.abhay.assistant.voice.PhoneVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
        /** For a spoken request: the words heard, when they differ from [request] (its English command). */
        val heard: String? = null,
        /** For a typed request: what the rewrite made of it, when that differs ("Understood as"). */
        val understood: String? = null,
        /** The rewrite said nothing is asked of the phone (NONE). */
        val nothingAsked: Boolean = false,
        /** The steps that couldn't be used for this request, and what was used instead. */
        val fallbacks: List<Fallback> = emptyList(),
        /** When no tool did it: the tools that are off but that the request mentions, offered to turn on. */
        val turnOn: List<String> = emptyList(),
        /** Jev's reply, when it selected the tool ([LinkPolicy.select]); its pick, or why it failed, shows in the details. */
        val jev: JevReply? = null,
        /** The steps the request took, as they ran ("Voice: Gemini Nano → Rewrite: Gemini Nano → Needle 3 (0.81)"). */
        val path: List<Step> = emptyList(),
        /**
         * For each call Needle 3 held back ([NeedleReply.suppressed]), what Ferb would have done,
         * with its own resolved time ("Remind you: “Fill the form” · Sat 31 Oct, 09:00"); null where
         * it couldn't plan one. Needle 3's reasoning has dates of its own, which Ferb doesn't use.
         */
        val heldBack: List<String?> = emptyList(),
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
        /**
         * Whistle is transcribing on the phone: the screen keeps still meanwhile (no animated
         * indicator), as the engine's pinned workers slow several times over when the UI competes
         * for their cores (Local Models Demo, 2026-10-06).
         */
        val transcribing: Boolean = false,
    )

    sealed interface Voice {
        /**
         * [onPhone]: recorded as PCM on the phone (for Whistle, or for the computer through Ferb
         * Link); [live]: Gemini Nano hears it as it's said, its words shown as they come.
         */
        data class Recording(
            val startedAt: Long, val onPhone: Boolean = false, val live: Boolean = false, val heard: String = "",
            /** Silent after speech: how far through the silence before it's sent by itself (0–1), for ✓'s ring; null otherwise. */
            val sendingIn: Float? = null,
        ) : Voice
        /** What Gemini Nano heard, waiting to be sent (✓ sends it at once; this is for a retry). */
        data class Heard(val text: String, val ms: Long) : Voice
        /** [pcm]: 16 kHz float, [ms] long; heard by the audio step when sent. [audio]: unused, kept for the tests' shape. */
        class Recorded(val ms: Long, val pcm: FloatArray? = null) : Voice
    }

    private val assistant = app as AssistantApp
    val engine: StateFlow<AssistantEngine.State> = assistant.engine.state
    val enabledTools: StateFlow<List<String>> = assistant.toolSettings.enabled
    /** How Ferb understands a request (Settings → How Ferb understands you). */
    val pipeline: StateFlow<Pipeline> = assistant.pipeline.pipeline
    /** Whether a step uses the computer, so typing or speaking wakes its model ([wakeComputer]). */
    val usesComputer get() = pipeline.value.let { it.audio == AudioStep.Link || it.rewrite == RewriteStep.Link || it.tools == ToolStep.Link }
    val link get() = assistant.link
    val notes get() = assistant.notes
    val reminders get() = assistant.reminders

    /** Screens a reply's button opens in Ferb (the Reminders tab), each once, onto the back stack. */
    private val screens = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val opens: kotlinx.coroutines.flow.Flow<String> = screens.receiveAsFlow()

    /** A reminder just made by hand, for the Reminders tab to scroll to once. */
    val justAdded = MutableStateFlow<String?>(null)

    /** Saves a reminder made by hand, as `add_reminder` does without a note, and schedules it. Blocking. False if it isn't valid. */
    fun addReminder(draft: dev.abhay.assistant.reminders.ReminderDraft): Boolean {
        val reminder = draft.save(assistant.reminders, java.time.LocalDateTime.now()) ?: return false
        assistant.syncReminders()
        justAdded.value = reminder.id
        return true
    }

    fun clearReminder(id: String) = assistant.clearReminder(id)
    fun dismissReminder(id: String) = assistant.dismissReminder(id)
    fun restoreReminder(reminder: dev.abhay.assistant.reminders.Reminder) = assistant.restoreReminder(reminder)
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
     * the chat box tapped, or recording started, while a step uses the computer. A cold load took
     * 2–28 s, longer than the 10 s Ferb waits for an answer; started on the tap, most of it hides
     * behind the typing. A loaded model costs Ferb Link two small requests, so at most once a minute.
     */
    fun wakeComputer() {
        val now = SystemClock.elapsedRealtime()
        if (wokeAt != 0L && now - wokeAt < WAKE_EVERY_MS) return
        wokeAt = now
        viewModelScope.launch { assistant.link.wake() }
    }

    /** Shared by the chat and the assistant's sheet ([AssistantApp.chat]). */
    private val _state = assistant.chat
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // Loads the model on first use in this process. Each request then gets the tools it
        // mentions (ToolRouter), so saving other tools needs no reload here.
        viewModelScope.launch {
            enabledTools.collect { names -> if (engine.value !is AssistantEngine.State.Ready) applyTools(names) }
        }
        // What Gemini Nano can do here, for the steps not chosen yet (their defaults). Ferb is in
        // front now, which AICore needs.
        viewModelScope.launch {
            assistant.nanoEars.refresh()
            val setup = assistant.nanoEars.setup.value
            assistant.pipeline.geminiChecked(speech = setup.speech == Part.Ready, rewrite = setup.rewrite == Part.Ready)
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
        (_state.value.voice as? Voice.Heard)?.let { return sendGemini(it) }
        val request = text.trim()
        if (request.isEmpty() || _state.value.busy || engine.value !is AssistantEngine.State.Ready) return
        _state.update { it.copy(busy = true, input = "", notice = null, pending = request) }
        viewModelScope.launch { answer(request, heardBy = null) }
    }

    /**
     * Answers [said] (typed, or a spoken request as heard by [heardBy]) through the rewrite and the
     * tool selection, then Needle 3 ([AnswerFlow]), and runs what it can. [audioFallbacks]: the
     * hearing step that couldn't be used this time, for the details.
     */
    private suspend fun answer(said: String, heardBy: List<Step>?, audioFallbacks: List<Fallback> = emptyList()) {
        try {
            val started = SystemClock.elapsedRealtime()
            val enabled = enabledTools.value.mapNotNull(Tools::byName)
            val configs = assistant.toolSettings.configs.value
            val answer = flow.answer(said, enabled, configs, pipeline.value, heardBy,
                geminiUsable = assistant.nanoEars.setup.value.rewrite == Part.Ready)
            val shown = answer.shown
            val verdict = answer.verdict
            val text = answer.command ?: said
            val calls = shown.calls.map { call -> CallState(call, prepare(call, text), Status.Waiting) }
            val spoken = heardBy != null
            val exchange = Exchange(assistant.exchangeIds.getAndIncrement(), if (spoken) text else said, shown, verdict.decision, verdict.runAt, verdict.alwaysConfirm, verdict.unmentioned, verdict.ungrounded,
                answer.checked, calls, SystemClock.elapsedRealtime() - started,
                heard = said.takeIf { spoken && answer.command != null },
                understood = answer.command.takeIf { !spoken },
                nothingAsked = answer.nothingAsked,
                fallbacks = audioFallbacks + answer.fallbacks,
                turnOn = if (shown.calls.isEmpty() && shown.error == null && !answer.nothingAsked) ToolRouter.off(text, configs).map { it.name } else emptyList(),
                jev = answer.jev,
                path = answer.path,
                heldBack = shown.suppressed.map { call -> (prepare(call, text) as? Prepared.Ready)?.summary })
            _state.update { it.copy(history = it.history + exchange, pending = null, asking = null) }
            if (verdict.decision == Decision.Run) run(exchange.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(notice = e.message ?: e.toString(), input = if (heardBy == null) said else it.input) }
        } finally {
            _state.update { it.copy(busy = false, pending = null, asking = null) }
        }
    }

    private val flow = AnswerFlow(assistant.link, needle = ::ask, gemini = { assistant.nanoEars.rewrite(it) },
        asking = { text -> _state.update { it.copy(asking = text) } })

    private val pcmRecorder = PcmRecorder()
    /** The phone recording under way, which ends with its clip. */
    private var pcmRecording: Deferred<FloatArray?>? = null

    /**
     * Starts recording a spoken request (the microphone permission is granted), for the audio step:
     * Gemini Nano hears it live; Whistle, or the computer through Ferb Link, from a PCM recording.
     */
    fun startRecording() {
        if (_state.value.busy || pcmRecorder.recording || pcmRecording != null || nanoJob != null) return
        sendWhenStopped = false
        when (pipeline.value.audio) {
            // Off: there's no mic, so this is only a guard.
            AudioStep.Off -> return
            AudioStep.Gemini -> startNano()
            AudioStep.Whistle -> startPhoneRecording()
            AudioStep.Link -> {
                startPhoneRecording()
                wakeComputer()
            }
        }
    }

    @SuppressLint("MissingPermission") // the screen asks for the microphone before starting
    private fun startPhoneRecording() {
        val startedAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(voice = Voice.Recording(startedAt, onPhone = true), notice = null) }
        // Auto-send: speech, then 1 s of silence ([Endpoint.SILENCE_MS]), sends it; no speech in 8 s stops it, sending nothing.
        val vad = EnergyVad()
        var decided = false
        val job = viewModelScope.async {
            try {
                // At 30 s (Whistle's most, and Ferb Link's) it stops by itself and waits for Send.
                pcmRecorder.record(onChunk = { chunk, read ->
                    val decision = vad.feed(chunk, 0, read)
                    val sending = vad.endpoint.sending(vad.nowMs)
                    viewModelScope.launch {
                        showSending(sending)
                        if (!decided && decision != Endpoint.Decision.Continue) {
                            decided = true
                            autoStop(decision)
                        }
                    }
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(voice = null, notice = "The microphone can't be used: ${e.message ?: e}") }
                null
            }
        }
        pcmRecording = job
        viewModelScope.launch {
            // The recorder failed (the mic in use elsewhere): clear it, or the mic stays blocked.
            val pcm = job.await() ?: run { if (pcmRecording === job) pcmRecording = null; return@launch }
            if (pcmRecording !== job) return@launch
            pcmRecording = null
            val ms = SystemClock.elapsedRealtime() - startedAt
            if (heardNothing) {
                heardNothing = false
                return@launch _state.update { if (it.voice is Voice.Recording) it.copy(voice = null, notice = NOTHING_HEARD) else it }
            }
            _state.update {
                if (it.voice !is Voice.Recording) it
                else it.copy(voice = Voice.Recorded(ms, pcm).takeIf { pcm.isNotEmpty() },
                    notice = if (pcm.isEmpty()) "Nothing recorded: tap the mic, speak, then tap ✓" else null)
            }
            sendIfAsked()
        }
    }

    /** Gemini Nano listening: it ends with what was heard, sent at once by ✓. */
    private var nanoJob: kotlinx.coroutines.Job? = null

    /** No speech before the endpointer gave up: the recording stops, and nothing is sent. */
    private var heardNothing = false

    /** ✓'s ring while silent after speech ([Endpoint.sending]), on the recording shown. */
    private fun showSending(sending: Float?) = _state.update { s ->
        val r = s.voice as? Voice.Recording ?: return@update s
        val now = r.sendingIn
        if (now == sending || (now != null && sending != null && kotlin.math.abs(now - sending) < 0.04f)) s
        else s.copy(voice = r.copy(sendingIn = sending))
    }

    /** The endpointer's call: a second of silence after speech sends it, as ✓ would; no speech, nothing. */
    private fun autoStop(decision: Endpoint.Decision) {
        if (_state.value.voice !is Voice.Recording) return
        when (decision) {
            Endpoint.Decision.End -> finishRecording()
            Endpoint.Decision.NoSpeech -> {
                heardNothing = true
                stopRecording()
            }
            Endpoint.Decision.Continue -> {}
        }
    }

    /**
     * Gemini Nano hears the request, what it hears so far showing in the box's place. When its
     * checkStatus() says it can't (not set up, or unavailable here), Whistle hears this one.
     */
    private fun startNano() {
        if (nanoJob != null) return
        val startedAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(voice = Voice.Recording(startedAt, onPhone = true, live = true), notice = null) }
        fun live(heard: String) = _state.update { s -> (s.voice as? Voice.Recording)?.takeIf { it.live }?.let { s.copy(voice = it.copy(heard = heard)) } ?: s }
        // Started once it's stored: a status() that returns without suspending would otherwise clear
        // nanoJob before the assignment, which then kept a finished job and blocked the mic for good.
        lateinit var self: kotlinx.coroutines.Job
        self = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val nano = assistant.nanoEars
            val choice = NanoChoice.of(nano.status())
            if (choice is NanoChoice.Whistle) {
                if (nanoJob === self) nanoJob = null
                Log.i(NanoEars.TAG, "${dev.abhay.assistant.voice.NanoSpeech.LOCALE}: Whistle instead (${choice.notice})")
                startPhoneRecording()
                _state.update { it.copy(notice = choice.notice) }
                return@launch
            }
            val transcript = LiveTranscript()
            val listening = SystemClock.elapsedRealtime()
            var firstWords = -1L
            var failed = false
            // Auto-send: a new partial result is speech; a second without one after speech sends it, none in 8 s stops it.
            val endpoint = Endpoint()
            var shown = ""
            fun heard(text: String) {
                live(text)
                if (text.isNotBlank() && text != shown) endpoint.speech(SystemClock.elapsedRealtime() - listening)
                shown = text
            }
            val ticker = launch {
                while (isActive) {
                    delay(100)
                    val at = SystemClock.elapsedRealtime() - listening
                    showSending(endpoint.sending(at))
                    when (endpoint.decide(at)) {
                        Endpoint.Decision.Continue -> {}
                        Endpoint.Decision.End -> { finishRecording(); break }
                        Endpoint.Decision.NoSpeech -> { heardNothing = true; stopRecording(); break }
                    }
                }
            }
            try {
                nano.listen().collect { response ->
                    when (response) {
                        is SpeechRecognizerResponse.PartialTextResponse -> {
                            if (firstWords < 0) firstWords = SystemClock.elapsedRealtime() - listening
                            heard(transcript.partial(response.text))
                        }
                        is SpeechRecognizerResponse.FinalTextResponse -> {
                            if (firstWords < 0) firstWords = SystemClock.elapsedRealtime() - listening
                            heard(transcript.final(response.text))
                        }
                        is SpeechRecognizerResponse.ErrorResponse -> throw response.e
                        else -> {}
                    }
                }
                Log.i(NanoEars.TAG, "${dev.abhay.assistant.voice.NanoSpeech.LOCALE}: status check ${listening - startedAt} ms, first words after $firstWords ms, " +
                    "${SystemClock.elapsedRealtime() - listening} ms listening; heard ${Private.text(transcript.heard)}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                Log.w(NanoEars.TAG, "${dev.abhay.assistant.voice.NanoSpeech.LOCALE}: recognition failed", e)
                _state.update { it.copy(notice = "Gemini Nano couldn't hear it: ${e.message ?: e}") }
            } finally {
                ticker.cancel()
                if (nanoJob === self) nanoJob = null
                // Cancelled (✕): nothing is kept, whatever was heard.
                val cancelled = !isActive
                val heard = transcript.heard
                val ms = SystemClock.elapsedRealtime() - startedAt
                val nothing = heardNothing.also { heardNothing = false }
                _state.update {
                    if ((it.voice as? Voice.Recording)?.live != true) it
                    else if (cancelled) it.copy(voice = null)
                    else if (nothing) it.copy(voice = null, notice = NOTHING_HEARD)
                    else if (heard.isBlank()) it.copy(voice = null, notice = if (failed) it.notice else PhoneVoice.NO_WORDS)
                    else it.copy(voice = Voice.Heard(heard, ms))
                }
                if (!cancelled) sendIfAsked()
            }
        }
        nanoJob = self
        self.start()
    }

    /** What Gemini Nano heard, answered like a typed request through the rest of the steps. */
    private fun sendGemini(clip: Voice.Heard) {
        if (_state.value.busy || engine.value !is AssistantEngine.State.Ready) return
        _state.update { it.copy(busy = true, voice = null, notice = null, pending = clip.text, asking = null) }
        viewModelScope.launch { answer(clip.text, heardBy = listOf(Step("Voice: Gemini Nano"))) }
    }

    /** The tick (✓) was tapped: the recording is sent as soon as it's stopped. */
    private var sendWhenStopped = false

    /** ✓: stops recording and sends it straight away (heard, rewritten and answered as usual). */
    fun finishRecording() {
        if (_state.value.voice !is Voice.Recording) return
        sendWhenStopped = true
        stopRecording()
    }

    /** After a recording stopped: sends it if ✓ asked for that. A recording that hit its limit by itself waits for Send. */
    private fun sendIfAsked() {
        if (!sendWhenStopped) return
        sendWhenStopped = false
        val voice = _state.value.voice
        if (voice is Voice.Recorded || voice is Voice.Heard) send()
    }

    /** Stops recording; the recording waits for Send (or [discardRecording]); see [finishRecording]. */
    fun stopRecording() {
        val recording = _state.value.voice as? Voice.Recording ?: return
        if (recording.live) {
            val job = nanoJob ?: return _state.update { it.copy(voice = null) }
            // ML Kit sends the last words, then ends; if it doesn't within 3 s, it's cut short.
            viewModelScope.launch {
                runCatching { assistant.nanoEars.stop() }.onFailure { Log.w(NanoEars.TAG, "stopRecognition failed", it) }
                if (kotlinx.coroutines.withTimeoutOrNull(3_000) { job.join() } == null) job.cancel()
            }
            return
        }
        pcmRecorder.stop()
    }

    /** ✕: discards the recording (or what was heard); nothing is sent. */
    /**
     * The screen went out of sight: a recording of its own goes (✓ already pressed, it's sent). So a
     * recording on screen is always the screen's own, and ✓ or ✕ on it stops the real recorder.
     */
    fun leftScreen() {
        if (!sendWhenStopped && (nanoJob != null || pcmRecording != null)) discardRecording()
    }

    fun discardRecording() {
        sendWhenStopped = false
        heardNothing = false
        nanoJob?.cancel()
        nanoJob = null
        pcmRecording?.cancel()
        pcmRecording = null
        _state.update { it.copy(voice = null) }
    }

    /** A PCM recording, heard by the audio step: the computer (Whistle if it can't), or Whistle. */
    private fun sendVoice() {
        val clip = _state.value.voice as? Voice.Recorded ?: return
        if (_state.value.busy || engine.value !is AssistantEngine.State.Ready) return
        val pcm = clip.pcm ?: return _state.update { it.copy(voice = null) }
        PhoneVoice.silent(pcm)?.let { failed -> return _state.update { it.copy(voice = null, notice = failed.notice) } }
        if (pipeline.value.audio == AudioStep.Link) sendLinkVoice(clip, pcm) else sendPhoneVoice(clip, pcm)
    }

    /**
     * The computer hears it (Gemma 4 through Ferb Link), sent as AAC. What it heard goes on to the
     * rewrite as said; with the rewrite off, its own English command does (Ferb Link writes both).
     * If the computer can't hear it, Whistle hears the same recording, and the details say so.
     */
    private fun sendLinkVoice(clip: Voice.Recorded, pcm: FloatArray) {
        _state.update { it.copy(busy = true, voice = null, notice = null, pending = voiceLabel(clip.ms), asking = "Your computer is listening…") }
        viewModelScope.launch {
            val why: String = when (val status = assistant.link.status()) {
                is LinkStatus.Ready -> {
                    if (status.asleep) _state.update { it.copy(asking = "Waking your computer…") }
                    val audio = try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { AacEncoder.encode(pcm, PcmRecorder.SAMPLE_RATE, app.cacheDir) }
                    } catch (e: Exception) {
                        null
                    }
                    if (audio == null) "the recording couldn't be encoded"
                    else when (val next = VoiceOutcome.of(assistant.link.transcribe(audio, AacEncoder.MIME, status.asleep))) {
                        is VoiceOutcome.Answer -> {
                            val said = if (pipeline.value.rewrite == RewriteStep.Off) next.request else next.heard ?: next.request
                            _state.update { it.copy(pending = said, asking = null) }
                            return@launch answer(said, heardBy = listOf(Step("Voice: Ferb Link")))
                        }
                        is VoiceOutcome.Failed -> if (!next.keepClip) {
                            // No words: not the computer's failure, so no Whistle either.
                            return@launch _state.update { it.copy(busy = false, pending = null, asking = null, notice = next.notice) }
                        } else next.notice.removePrefix("Your computer couldn't hear it: ")
                    }
                }
                else -> brief(status)
            }
            _state.update { it.copy(asking = "Transcribing…", transcribing = true) }
            whistle(clip, pcm, listOf(Fallback("Audio", "Whistle", "Ferb Link: $why")))
        }
    }

    /**
     * Whistle transcribes the recording on the phone, in English, then its text is answered like a
     * typed request ("Voice: Whistle → …"). A clip without speech never reaches the model.
     */
    private fun sendPhoneVoice(clip: Voice.Recorded, pcm: FloatArray) {
        _state.update { it.copy(busy = true, voice = null, notice = null, pending = voiceLabel(clip.ms), asking = "Transcribing…", transcribing = true) }
        viewModelScope.launch { whistle(clip, pcm, emptyList()) }
    }

    private suspend fun whistle(clip: Voice.Recorded, pcm: FloatArray, fallbacks: List<Fallback>) {
        val heard = try {
            Result.success(assistant.engine.transcribe(pcm))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        heard.getOrNull()?.let { Log.i(TAG, "Whistle: ${it.audioMs} ms of audio in ${it.ms} ms (load ${assistant.engine.whistleLoaded} ms)") }
        _state.update { it.copy(transcribing = false) }
        when (val next = PhoneVoice.of(heard)) {
            is PhoneVoice.Failed ->
                _state.update { it.copy(busy = false, pending = null, asking = null, voice = clip.takeIf { next.keepClip }, notice = next.notice) }
            is PhoneVoice.Request -> {
                _state.update { it.copy(pending = next.text, asking = null) }
                answer(next.text, heardBy = listOf(Step(if (fallbacks.isEmpty()) "Voice: Whistle" else "Voice: Whistle instead")), audioFallbacks = fallbacks)
            }
        }
    }

    override fun onCleared() {
        // A recording of this screen's (the sheet closed while listening) goes with it.
        if (nanoJob != null || pcmRecording != null) _state.update { it.copy(voice = null) }
        nanoJob?.cancel()
        pcmRecording?.cancel()
        pcmRecorder.stop()
    }

    private suspend fun ask(request: String, tools: List<Tool>): NeedleReply =
        NeedleReply.parse(assistant.engine.complete(request, tools.map { it.name }, Tools.json(tools)))

    /** Checks a call before anything runs: the tool must be turned on, and its arguments valid. */
    private fun prepare(call: ToolCall, request: String): Prepared {
        val tool = Tools.byName(call.name)?.takeIf { it.name in enabledTools.value }
            ?: return Prepared.Invalid("No tool called ${call.name} is turned on")
        val env = PrepareEnv(assistant.apps, assistant.contacts, assistant.calendar, notes = assistant.notes,
            reminders = assistant.reminders, remindersChanged = assistant::syncReminders,
            openNote = { context, note -> context.startActivity(dev.abhay.assistant.ChatActivity.openNote(context, note)) },
            openReminders = { screens.trySend(dev.abhay.assistant.ChatActivity.REMINDERS) },
            granted = { assistant.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED },
            options = assistant.toolSettings.configs.value[tool.name]?.options.orEmpty())
        return tool.prepare(tool.fromRequest(call.arguments, request), env)
    }

    private val runner by lazy { CallRunner(assistant.foreground.inFront) }

    /**
     * Runs an exchange's waiting calls, one at a time (see [CallRunner]); a call that failed its
     * checks reports why instead. Each outcome shows as soon as it's known.
     */
    fun run(exchangeId: Long) {
        val waiting = _state.value.history.firstOrNull { it.id == exchangeId }?.calls?.indices
            ?.filter { i -> _state.value.history.first { it.id == exchangeId }.calls[i].status == Status.Waiting }.orEmpty()
        if (waiting.isEmpty()) return
        updateExchange(exchangeId) { e -> e.copy(calls = e.calls.mapIndexed { i, c -> if (i in waiting) c.copy(status = Status.Queued) else c }) }
        assistant.calls.launch {
            assistant.callsRunning.withLock {
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
        assistant.calls.launch {
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

    /**
     * A result's slider followed a change made outside Ferb ([dev.abhay.assistant.tools.LevelWatch]):
     * shows [value] and its message, changing nothing.
     */
    fun follow(exchangeId: Long, callIndex: Int, value: Float) = updateExchange(exchangeId) { e ->
        e.copy(calls = e.calls.mapIndexed { i, c ->
            val done = (c.status as? Status.Finished)?.outcome as? Outcome.Done
            val level = done?.level
            val watch = level?.watch
            if (i != callIndex || watch == null) c
            else c.copy(status = Status.Finished(done.copy(message = watch.describe(value), level = level.copy(value = value))))
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
        const val TAG = "Assistant"
        /** No speech before the endpointer gave up (8 s): nothing was sent, and the mic is ready again. */
        const val NOTHING_HEARD = "Didn't hear anything"
        const val WAKE_EVERY_MS = 60_000L
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
