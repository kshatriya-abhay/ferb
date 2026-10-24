package dev.abhay.assistant.voice

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * Gemini Nano's speech recognition (ML Kit GenAI, Advanced mode), one client per language, for the
 * process. It records from the microphone itself and runs in AICore, on the phone. Only works with
 * Ferb in front. ML Kit's telemetry can't send anything: Ferb has no INTERNET permission.
 */
class NanoEars {
    private val clients = mutableMapOf<PhoneEar, SpeechRecognizer>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var download: Job? = null

    @Synchronized
    private fun client(ear: PhoneEar): SpeechRecognizer = clients.getOrPut(ear) {
        SpeechRecognition.getClient(speechRecognizerOptions {
            locale = requireNotNull(ear.locale)
            preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED
        })
    }

    /** checkStatus() for [ear]: a [FeatureStatus]; UNAVAILABLE when the check itself fails. */
    suspend fun status(ear: PhoneEar): Int = try {
        client(ear).checkStatus().also { Log.i(TAG, "${ear.locale}: checkStatus ${NanoChoice.name(it)}") }
    } catch (e: Exception) {
        Log.w(TAG, "${ear.locale}: checkStatus failed", e)
        FeatureStatus.UNAVAILABLE
    }

    /** Starts the model's download through AICore, once at a time; progress goes to the log. */
    fun download(ear: PhoneEar) {
        if (download?.isActive == true) return
        download = scope.launch {
            try {
                client(ear).download().collect { step ->
                    when (step) {
                        is DownloadStatus.DownloadStarted -> Log.i(TAG, "${ear.locale}: download started, ${step.bytesToDownload} bytes")
                        is DownloadStatus.DownloadProgress -> Log.i(TAG, "${ear.locale}: downloaded ${step.totalBytesDownloaded} bytes")
                        is DownloadStatus.DownloadCompleted -> Log.i(TAG, "${ear.locale}: download completed")
                        is DownloadStatus.DownloadFailed -> Log.w(TAG, "${ear.locale}: download failed", step.e)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "${ear.locale}: download failed", e)
            }
        }
    }

    /** Listens on the microphone until [stop] (or ML Kit ends it), streaming what it hears. */
    fun listen(ear: PhoneEar): Flow<SpeechRecognizerResponse> =
        client(ear).startRecognition(speechRecognizerRequest { audioSource = AudioSource.fromMic() })
            .takeWhile { it !is SpeechRecognizerResponse.CompletedResponse }

    suspend fun stop(ear: PhoneEar) = client(ear).stopRecognition()

    companion object {
        const val TAG = "FerbNano"
    }
}
