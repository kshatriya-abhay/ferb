package dev.abhay.assistant.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.abhay.assistant.AssistantApp
import dev.abhay.assistant.engine.AssistantEngine
import dev.abhay.assistant.engine.Decision
import dev.abhay.assistant.engine.NeedleReply
import dev.abhay.assistant.engine.ToolCall
import dev.abhay.assistant.link.Answerer
import dev.abhay.assistant.link.JevReply
import dev.abhay.assistant.link.LinkMode
import dev.abhay.assistant.link.LinkPolicy
import dev.abhay.assistant.link.LinkStatus
import dev.abhay.assistant.link.Recorder
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
import dev.abhay.assistant.voice.GeminiLanguage
import dev.abhay.assistant.voice.LiveTranscript
import dev.abhay.assistant.voice.NanoChoice
import dev.abhay.assistant.voice.NanoEars
import dev.abhay.assistant.voice.PcmRecorder
import dev.abhay.assistant.voice.PhoneEar
import dev.abhay.assistant.voice.PhoneVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
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
        /** Why the computer's answer isn't shown, when Ferb asked it and it failed (Needle 3 answered): in a few words, and at length. */
        val laptopError: String? = null,
        val laptopErrorDetail: String? = null,
        /** For a spoken request: the words heard, when they differ from [request] (its English command). */
        val heard: String? = null,
        /** When no tool did it: the tools that are off but that the request mentions, offered to turn on. */
        val turnOn: List<String> = emptyList(),
        /** Jev's reply, when it selected the tool ([LinkPolicy.select]); its pick, or why it failed, shows in the details. */
        val jev: JevReply? = null,
        /** Why Jev's pick wasn't used, when it was asked (or is at its spending limit): in a few words, and at length. */
        val jevNote: String? = null,
        val jevNoteDetail: String? = null,
        /** The steps the request took, as they ran ("Typed → Jev (Alarm, 0.93) → Needle 3 (0.81)"). */
        val path: List<Step> = emptyList(),
        /** For a rewritten request: its commands ("Understood as"), each with its steps. */
        val commands: List<CommandPath> = emptyList(),
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
         * [onPhone]: for Whistle on the phone, else for the computer through Ferb Link. [live]:
         * Gemini Nano hears it as it's said, streaming its text into the chat box (POC).
         */
        data class Recording(val startedAt: Long, val onPhone: Boolean = false, val live: GeminiLanguage? = null, val heard: String = "") : Voice
        /** What Gemini Nano heard, waiting for Send (or ✕), which writes it in English for Needle 3. */
        data class Heard(val text: String, val ms: Long) : Voice
        /** [audio] is [Recorder.MIME] for the computer, [pcm] 16 kHz float for Whistle; [ms] long. */
        class Recorded(val audio: ByteArray?, val ms: Long, val pcm: FloatArray? = null) : Voice
    }

    private val assistant = app as AssistantApp
    val engine: StateFlow<AssistantEngine.State> = assistant.engine.state
    val enabledTools: StateFlow<List<String>> = assistant.toolSettings.enabled
    /** How Ferb Link is used (Settings → Ferb Link), and who answers when the chat box offers a choice. */
    val linkMode: StateFlow<LinkMode> = assistant.linkSettings.mode
    val answerer: StateFlow<Answerer> = assistant.linkSettings.answerer
    /** "Use Jev to select the tool" (Settings → Ferb Link). */
    val jev: StateFlow<Boolean> = assistant.linkSettings.jev
    /** "Voice on this phone (Whistle)" (Settings). */
    val whistle: StateFlow<Boolean> = assistant.voice.whistle
    fun setAnswerer(answerer: Answerer) {
        // A recording is for the one who was picked when it started.
        if (answerer != this.answerer.value) discardRecording()
        assistant.linkSettings.setAnswerer(answerer)
        if (answerer == Answerer.Link) wakeComputer()
    }
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
        (_state.value.voice as? Voice.Heard)?.let { return sendGemini(it) }
        val request = text.trim()
        if (request.isEmpty() || _state.value.busy || engine.value !is AssistantEngine.State.Ready) return
        _state.update { it.copy(busy = true, input = "", notice = null, pending = request) }
        viewModelScope.launch { answer(request, heard = null) }
    }

    /** Answers [request] (typed, or the English command of a spoken one, [heard] as said) and runs what it can. */
    private suspend fun answer(request: String, heard: String?, phoneVoice: List<String>? = null) {
        try {
            val started = SystemClock.elapsedRealtime()
            val enabled = enabledTools.value.mapNotNull(Tools::byName)
            val configs = assistant.toolSettings.configs.value
            val answer = flow.answer(request, heard, enabled, configs, assistant.linkSettings.mode.value,
                assistant.linkSettings.answerer.value, assistant.linkSettings.jev.value, phoneVoice)
            val shown = answer.shown
            val verdict = answer.verdict
            val calls = shown.calls.mapIndexed { i, call -> CallState(call, prepare(call, answer.callTexts[i]), Status.Waiting) }
            val exchange = Exchange(nextId++, request, shown, verdict.decision, verdict.runAt, verdict.alwaysConfirm, verdict.unmentioned, verdict.ungrounded,
                answer.checked, calls, SystemClock.elapsedRealtime() - started,
                laptopError = answer.laptopError,
                laptopErrorDetail = answer.laptopErrorDetail,
                heard = heard,
                turnOn = if (shown.calls.isEmpty() && shown.error == null) ToolRouter.off(request, configs).map { it.name } else emptyList(),
                jev = answer.jev, jevNote = answer.jevNote, jevNoteDetail = answer.jevNoteDetail,
                path = answer.path, commands = answer.commands)
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

    private val flow = AnswerFlow(assistant.link, needle = ::ask, asking = { text -> _state.update { it.copy(asking = text) } })

    private val recorder by lazy { Recorder(app) }
    private val pcmRecorder = PcmRecorder()
    /** The phone recording under way, which ends with its clip. */
    private var pcmRecording: Deferred<FloatArray?>? = null

    /**
     * Starts recording a spoken request (the microphone permission is granted): [onPhone] for
     * Whistle; otherwise for the computer, and Ferb Link wakes its model meanwhile.
     */
    fun startRecording(onPhone: Boolean = false) {
        if (_state.value.busy || recorder.recording || pcmRecorder.recording || pcmRecording != null || nanoJob != null) return
        if (onPhone) {
            // Off: Whistle never loads (the mic is hidden then, so this is only a guard).
            if (!assistant.voice.whistle.value) return
            return if (assistant.voice.ear.value == PhoneEar.Gemini) startNano(assistant.voice.language.value) else startPhoneRecording()
        }
        try {
            recorder.start(onLimit = ::stopRecording)
        } catch (e: Exception) {
            _state.update { it.copy(notice = "The microphone can't be used: ${e.message ?: e}") }
            return
        }
        _state.update { it.copy(voice = Voice.Recording(SystemClock.elapsedRealtime()), notice = null) }
        viewModelScope.launch { assistant.link.wake() }
    }

    @SuppressLint("MissingPermission") // the screen asks for the microphone before starting
    private fun startPhoneRecording() {
        val startedAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(voice = Voice.Recording(startedAt, onPhone = true), notice = null) }
        val job = viewModelScope.async {
            try {
                // At 30 s (Whistle's most) it stops by itself and waits for Send, as when stopped.
                pcmRecorder.record()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(voice = null, notice = "The microphone can't be used: ${e.message ?: e}") }
                null
            }
        }
        pcmRecording = job
        viewModelScope.launch {
            val pcm = job.await() ?: return@launch
            if (pcmRecording !== job) return@launch
            pcmRecording = null
            val ms = SystemClock.elapsedRealtime() - startedAt
            _state.update {
                if (it.voice !is Voice.Recording) it
                else it.copy(voice = Voice.Recorded(null, ms, pcm).takeIf { pcm.isNotEmpty() },
                    notice = if (pcm.isEmpty()) "Nothing recorded: tap the mic, speak, then tap it again" else null)
            }
        }
    }

    /** Gemini Nano listening (POC): it ends with what was heard, waiting for Send. */
    private var nanoJob: kotlinx.coroutines.Job? = null

    /**
     * Gemini Nano hears the request (POC), what it hears so far showing in the box's place; once
     * stopped, it waits for Send ([sendGemini]). When it isn't set up or can't hear on this phone,
     * Whistle hears this one.
     */
    private fun startNano(language: GeminiLanguage) {
        if (nanoJob != null) return
        val startedAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(voice = Voice.Recording(startedAt, onPhone = true, live = language), notice = null) }
        fun live(heard: String) = _state.update { s -> (s.voice as? Voice.Recording)?.takeIf { it.live != null }?.let { s.copy(voice = it.copy(heard = heard)) } ?: s }
        nanoJob = viewModelScope.launch {
            val nano = assistant.nanoEars
            val choice = NanoChoice.of(nano.status(language))
            if (choice is NanoChoice.Whistle) {
                nanoJob = null
                Log.i(NanoEars.TAG, "${language.locale}: Whistle instead (${choice.notice})")
                startPhoneRecording()
                _state.update { it.copy(notice = choice.notice) }
                return@launch
            }
            val transcript = LiveTranscript()
            val listening = SystemClock.elapsedRealtime()
            var firstWords = -1L
            var failed = false
            try {
                nano.listen(language).collect { response ->
                    when (response) {
                        is SpeechRecognizerResponse.PartialTextResponse -> {
                            if (firstWords < 0) firstWords = SystemClock.elapsedRealtime() - listening
                            live(transcript.partial(response.text))
                        }
                        is SpeechRecognizerResponse.FinalTextResponse -> {
                            if (firstWords < 0) firstWords = SystemClock.elapsedRealtime() - listening
                            live(transcript.final(response.text))
                        }
                        is SpeechRecognizerResponse.ErrorResponse -> throw response.e
                        else -> {}
                    }
                }
                Log.i(NanoEars.TAG, "${language.locale}: status check ${listening - startedAt} ms, first words after $firstWords ms, " +
                    "${SystemClock.elapsedRealtime() - listening} ms listening; heard \"${transcript.heard}\"")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                Log.w(NanoEars.TAG, "${language.locale}: recognition failed", e)
                _state.update { it.copy(notice = "Gemini Nano couldn't hear it: ${e.message ?: e}") }
            } finally {
                nanoJob = null
                val heard = transcript.heard
                val ms = SystemClock.elapsedRealtime() - startedAt
                _state.update {
                    if ((it.voice as? Voice.Recording)?.live == null) it
                    else if (heard.isBlank()) it.copy(voice = null, notice = if (failed) it.notice else PhoneVoice.NO_WORDS)
                    else it.copy(voice = Voice.Heard(heard, ms))
                }
            }
        }
    }

    /**
     * What Gemini Nano heard, written as one plain English command for Needle 3 (the bench's
     * prompt, [dev.abhay.assistant.voice.GeminiRewrite]), then answered like a typed request, with
     * what was heard shown. NONE (nothing for the phone to do) answers without Needle 3. When the
     * rewrite fails, the words stay, to send again.
     */
    private fun sendGemini(clip: Voice.Heard) {
        if (_state.value.busy || engine.value !is AssistantEngine.State.Ready) return
        _state.update { it.copy(busy = true, voice = null, notice = null, pending = clip.text, asking = "Gemini Nano is writing it in English\u2026") }
        viewModelScope.launch {
            val rewritten = try {
                assistant.nanoEars.rewrite(clip.text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(NanoEars.TAG, "rewrite failed", e)
                _state.update { it.copy(busy = false, pending = null, asking = null, voice = clip,
                    notice = "Gemini Nano couldn't write it in English: ${e.message ?: e}") }
                return@launch
            }
            val command = rewritten.command
            if (command == null) {
                _state.update { it.copy(busy = false, pending = null, asking = null, notice = "Nothing for the phone to do in \u201c${clip.text}\u201d") }
                return@launch
            }
            _state.update { it.copy(pending = command, asking = null) }
            answer(command, heard = clip.text.takeUnless { it.equals(command, ignoreCase = true) }, phoneVoice = listOf("Gemini Nano", "Gemini Nano (English)"))
        }
    }

    /** Stops recording; the recording waits for Send (or [discardRecording]). */
    fun stopRecording() {
        val recording = _state.value.voice as? Voice.Recording ?: return
        recording.live?.let { language ->
            val job = nanoJob ?: return _state.update { it.copy(voice = null) }
            // ML Kit sends the last words, then ends; if it doesn't within 3 s, it's cut short.
            viewModelScope.launch {
                runCatching { assistant.nanoEars.stop(language) }.onFailure { Log.w(NanoEars.TAG, "stopRecognition failed", it) }
                if (kotlinx.coroutines.withTimeoutOrNull(3_000) { job.join() } == null) job.cancel()
            }
            return
        }
        if (recording.onPhone) return pcmRecorder.stop()
        val ms = SystemClock.elapsedRealtime() - recording.startedAt
        val audio = recorder.stop()
        _state.update {
            it.copy(voice = audio?.let { a -> Voice.Recorded(a, ms) },
                notice = if (audio == null) "Nothing recorded: tap the mic, speak, then tap it again" else null)
        }
    }

    fun discardRecording() {
        nanoJob?.cancel()
        nanoJob = null
        recorder.cancel()
        pcmRecording?.cancel()
        pcmRecording = null
        _state.update { it.copy(voice = null) }
    }

    /**
     * Sends the recording to the computer through Ferb Link, then answers its English command like a
     * typed request. When the computer fails, the recording is kept, to send again.
     */
    private fun sendVoice() {
        val clip = _state.value.voice as? Voice.Recorded ?: return
        if (_state.value.busy || engine.value !is AssistantEngine.State.Ready) return
        clip.pcm?.let { return sendPhoneVoice(clip, it) }
        val audio = clip.audio ?: return
        _state.update { it.copy(busy = true, voice = null, notice = null, pending = voiceLabel(clip.ms), asking = "Your computer is listening…") }
        viewModelScope.launch {
            val waking = (assistant.link.status() as? LinkStatus.Ready)?.asleep == true
            if (waking) _state.update { it.copy(asking = "Waking your computer\u2026") }
            when (val next = VoiceOutcome.of(assistant.link.transcribe(audio, Recorder.MIME, waking))) {
                is VoiceOutcome.Failed ->
                    _state.update { it.copy(busy = false, pending = null, asking = null, voice = clip.takeIf { next.keepClip }, notice = next.notice) }
                is VoiceOutcome.Answer -> {
                    _state.update { it.copy(pending = next.request, asking = null) }
                    answer(next.request, heard = next.heard)
                }
            }
        }
    }

    /**
     * Whistle transcribes the recording on the phone, in English, then its text is answered like a
     * typed request ("Voice → Whistle → …"). A clip without speech never reaches the model.
     */
    private fun sendPhoneVoice(clip: Voice.Recorded, pcm: FloatArray) {
        // Turned off after recording: dropped, so Whistle isn't loaded.
        if (!assistant.voice.whistle.value) return _state.update { it.copy(voice = null, notice = "Voice on this phone is off (Settings)") }
        PhoneVoice.silent(pcm)?.let { failed -> return _state.update { it.copy(voice = null, notice = failed.notice) } }
        _state.update { it.copy(busy = true, voice = null, notice = null, pending = voiceLabel(clip.ms), asking = "Transcribing\u2026", transcribing = true) }
        viewModelScope.launch {
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
                    answer(next.text, heard = null, phoneVoice = listOf("Whistle"))
                }
            }
        }
    }

    override fun onCleared() {
        nanoJob?.cancel()
        recorder.cancel()
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
            openNote = { context, note -> context.startActivity(dev.abhay.assistant.MainActivity.openNote(context, note)) },
            openReminders = { screens.trySend(dev.abhay.assistant.MainActivity.REMINDERS) },
            granted = { assistant.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED },
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
