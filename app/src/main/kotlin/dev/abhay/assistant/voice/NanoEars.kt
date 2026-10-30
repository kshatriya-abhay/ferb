package dev.abhay.assistant.voice

import android.os.SystemClock
import dev.abhay.assistant.Private
import android.util.Log
import kotlinx.coroutines.sync.withLock
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Gemini Nano on this phone, for the process (POC): speech recognition (ML Kit GenAI, Advanced
 * mode), and the Prompt API's model, which writes what was heard as English for Needle 3. Both run
 * in AICore, on the phone, and only with Ferb in front. ML Kit's telemetry can't send anything:
 * Ferb has no INTERNET permission.
 */
class NanoEars {
    private val listeners = mutableMapOf<Locale, SpeechRecognizer>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val downloads = mutableMapOf<String, Job>()

    /** Where the setup stands, as last checked ([refresh]). */
    private val _setup = MutableStateFlow(GeminiSetup())
    val setup: StateFlow<GeminiSetup> = _setup.asStateFlow()

    @Synchronized
    // Called from the main thread and from the downloads' background ones: one client per locale.
    private fun listener(locale: Locale): SpeechRecognizer = synchronized(listeners) { listeners.getOrPut(locale) {
        SpeechRecognition.getClient(speechRecognizerOptions {
            this.locale = locale
            preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED
        })
    } }

    /** The rewriting model: FAST (the smaller one, quicker to answer) unless only FULL is there. */
    @Volatile private var writer: GenerativeModel? = null
    private var writerName = "FAST"

    private val writerLock = kotlinx.coroutines.sync.Mutex()

    /** Made once: two screens (and the downloads) asking at the same time share one client, none leaked. */
    private suspend fun writer(): GenerativeModel = writer ?: writerLock.withLock { writer ?: newWriter() }

    private suspend fun newWriter(): GenerativeModel {
        fun model(preference: Int) = Generation.getClient(generationConfig {
            modelConfig = modelConfig { releaseStage = ModelReleaseStage.STABLE; this.preference = preference }
        })
        val fast = model(ModelPreference.FAST)
        val chosen = if (status { fast.checkStatus() } == FeatureStatus.UNAVAILABLE) {
            fast.close()
            writerName = "FULL"
            model(ModelPreference.FULL)
        } else fast
        return chosen.also { writer = it }
    }

    private suspend fun status(check: suspend () -> Int): Int = try {
        check()
    } catch (e: Exception) {
        Log.w(TAG, "checkStatus failed", e)
        FeatureStatus.UNAVAILABLE
    }

    /** checkStatus() for hearing, in [NanoSpeech.LOCALE]. */
    suspend fun status(): Int =
        status { listener(NanoSpeech.LOCALE).checkStatus() }.also { Log.i(TAG, "${NanoSpeech.LOCALE}: checkStatus ${NanoChoice.name(it)}") }

    /**
     * Checks both parts; a part already downloading keeps its progress, and one
     * Android is fetching on its own is followed, so its progress shows.
     */
    suspend fun refresh() {
        _setup.update { s -> s.copy(speech = s.speech.takeIf { it !is Part.Unchecked } ?: Part.Checking, rewrite = s.rewrite.takeIf { it !is Part.Unchecked } ?: Part.Checking) }
        val speech = part(SPEECH) { listener(NanoSpeech.LOCALE).checkStatus() }
        val rewrite = part(REWRITE) { writer().checkStatus() }
        _setup.update {
            GeminiSetup(
                speech = if (it.speech is Part.Downloading && downloads[SPEECH]?.isActive == true) it.speech else speech,
                rewrite = if (it.rewrite is Part.Downloading && downloads[REWRITE]?.isActive == true) it.rewrite else rewrite,
            )
        }
        if (speech is Part.Downloading) download(SPEECH)
        if (rewrite is Part.Downloading) download(REWRITE)
    }

    /**
     * One part's checkStatus() as a [Part]. A check that throws is [Part.Unchecked], never
     * "can't run": in release 0.1 a stripped ML Kit constructor made every check throw, and the
     * phone was told Gemini Nano can't run.
     */
    private suspend fun part(part: String, check: suspend () -> Int): Part = try {
        Part.of(check()).also { Log.i(TAG, "$part${if (part == REWRITE) " ($writerName)" else ""}: checkStatus $it") }
    } catch (e: Exception) {
        Log.w(TAG, "$part: checkStatus failed", e)
        Part.Unchecked(reason(e))
    }

    /** One part's download, when its row is tapped. */
    fun setUpSpeech() = download(SPEECH)
    fun setUpRewrite() = download(REWRITE)

    /** Set up: downloads every part that needs it, with progress in [setup], then checks again. */
    fun setUp() {
        val now = _setup.value
        if (now.speech !is Part.Ready && now.speech != Part.Unavailable && now.speech !is Part.Unchecked) download(SPEECH)
        if (now.rewrite !is Part.Ready && now.rewrite != Part.Unavailable && now.rewrite !is Part.Unchecked) download(REWRITE)
    }

    private fun download(part: String) {
        if (downloads[part]?.isActive == true) return
        fun set(state: Part) = _setup.update { if (part == SPEECH) it.copy(speech = state) else it.copy(rewrite = state) }
        downloads[part] = scope.launch {
            set(Part.Downloading())
            var total: Long? = null
            try {
                val steps = if (part == SPEECH) listener(NanoSpeech.LOCALE).download() else writer().download()
                steps.collect { step ->
                    when (step) {
                        is DownloadStatus.DownloadStarted -> {
                            total = step.bytesToDownload
                            Log.i(TAG, "$part: download started, ${step.bytesToDownload} bytes")
                            set(Part.Downloading(0, total))
                        }
                        is DownloadStatus.DownloadProgress -> {
                            // Every step, so a real download shows what AICore reports (the screens show no amount).
                            Log.i(TAG, "$part: ${step.totalBytesDownloaded} of ${total ?: "?"} bytes")
                            set(Part.Downloading(step.totalBytesDownloaded, total))
                        }
                        is DownloadStatus.DownloadCompleted -> {
                            Log.i(TAG, "$part: download completed")
                            set(Part.Ready)
                        }
                        is DownloadStatus.DownloadFailed -> {
                            Log.w(TAG, "$part: download failed", step.e)
                            set(Part.Failed(reason(step.e)))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "$part: download failed", e)
                set(Part.Failed(reason(e)))
            }
            // Downloads that were already there end without steps: ask again.
            val after = part(part) { if (part == SPEECH) listener(NanoSpeech.LOCALE).checkStatus() else writer().checkStatus() }
            if (_setup.value.let { if (part == SPEECH) it.speech else it.rewrite } !is Part.Failed) set(after)
        }
    }

    private fun reason(e: Throwable): String = when ((e as? GenAiException)?.errorCode) {
        GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE -> "not enough space on the phone"
        GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> "the phone needs a system update"
        GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> "keep Ferb open while it downloads"
        else -> e.message ?: e.javaClass.simpleName
    }

    /** Listens on the microphone until [stop] (or ML Kit ends it), streaming what it hears. */
    fun listen(): Flow<SpeechRecognizerResponse> =
        listener(NanoSpeech.LOCALE).startRecognition(speechRecognizerRequest { audioSource = AudioSource.fromMic() })
            .takeWhile { it !is SpeechRecognizerResponse.CompletedResponse }

    suspend fun stop() = listener(NanoSpeech.LOCALE).stopRecognition()

    /** [said] (typed or heard) as one English command for Needle 3, or none ([GeminiRewrite]). */
    suspend fun rewrite(said: String): Rewritten {
        val started = SystemClock.elapsedRealtime()
        val candidate = writer().generateContent(rewriteRequest(said)).candidates.firstOrNull()
        val answer = candidate?.text.orEmpty()
        val cut = candidate?.finishReason == Candidate.FinishReason.MAX_TOKENS
        Log.i(TAG, "rewrite ($writerName) in ${SystemClock.elapsedRealtime() - started} ms: ${Private.text(said)} -> ${Private.text(answer.trim())}")
        return GeminiRewrite.parse(said, answer, cut)
    }

    companion object {
        const val TAG = "FerbNano"

        /** The rewrite as Gemini Nano is asked it, in the app and on the phone bench alike: greedy, short. */
        fun rewriteRequest(said: String, prompt: String = GeminiRewrite.PROMPT, maxTokens: Int = GeminiRewrite.MAX_TOKENS) = generateContentRequest(SystemInstruction(prompt), TextPart(said)) {
            temperature = 0f
            topK = 1
            maxOutputTokens = maxTokens
        }

        /**
         * AICore's own settings page (Android Settings shows it through IA_SETTINGS): seen to make
         * the Prompt API's model available (2026-10-24). If Android won't open it for Ferb, AICore's
         * app info.
         */
        fun openAiCoreSettings(context: android.content.Context) {
            val settings = android.content.Intent("com.android.settings.action.IA_SETTINGS")
                .setClassName(AICORE, "com.google.android.apps.aicore.app.settings.AiCoreSettingsActivity")
            val info = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$AICORE"))
            for (intent in listOf(settings, info)) {
                try {
                    context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "can't open ${intent.action}", e)
                }
            }
        }

        private const val AICORE = "com.google.android.aicore"
        private const val SPEECH = "speech"
        private const val REWRITE = "rewrite"
    }
}
