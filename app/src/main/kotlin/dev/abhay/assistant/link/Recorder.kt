package dev.abhay.assistant.link

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/**
 * Records a spoken request for Ferb Link to transcribe on the computer: mono 16 kHz Opus in OGG,
 * about 3 KB a second, so 30 s (the most Ferb Link takes) is about 90 KB and fits in the call's
 * Bundle. Ferb records, not Ferb Link, as Android only lets the app in front use the microphone.
 * The recording stays in the app's cache until it's read, then is deleted.
 */
class Recorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private val file get() = File(context.cacheDir, "voice.ogg")

    val recording get() = recorder != null

    /** Starts recording; [onLimit] runs (on the main thread) when it reaches [MAX_MS] and stops by itself. */
    fun start(onLimit: () -> Unit) {
        cancel()
        val started = MediaRecorder(context)
        try {
            started.apply {
                // Tuned for speech recognition: no noise suppression or gain tricks that hurt it.
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.OGG)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                setAudioSamplingRate(16_000)
                setAudioChannels(1)
                setAudioEncodingBitRate(24_000)
                setMaxDuration(MAX_MS)
                setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onLimit() }
                setOutputFile(file)
                prepare()
                start()
            }
        } catch (e: Exception) {
            started.release()
            file.delete()
            throw e
        }
        recorder = started
    }

    /** Stops and returns the recording, or null when there's none or it was too short to keep. */
    fun stop(): ByteArray? {
        val stopping = recorder ?: return null
        recorder = null
        val ok = try {
            stopping.stop()
            true
        } catch (e: RuntimeException) {
            // Thrown when stopped before any audio was written (a tap-tap).
            false
        } finally {
            stopping.release()
        }
        val audio = if (ok && file.exists()) file.readBytes() else null
        file.delete()
        return audio
    }

    fun cancel() {
        recorder?.let { r ->
            try { r.stop() } catch (e: RuntimeException) { }
            r.release()
        }
        recorder = null
        file.delete()
    }

    companion object {
        const val MIME = "audio/ogg"
        /** Ferb Link refuses more. */
        const val MAX_MS = 30_000
    }
}
