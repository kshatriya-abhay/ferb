package dev.abhay.assistant.voice

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Records a spoken request for Whistle on the phone: mono float PCM at 16 kHz, exactly what it
 * takes, up to [MAX_SAMPLES] (its 30 s per pass). Stops on [stop], on cancellation, or at the cap.
 * Adapted from Local Models Demo's recorder. Computer mode records Opus instead ([dev.abhay.assistant.link.Recorder]).
 */
class PcmRecorder {
    @Volatile private var stopRequested = false
    @Volatile var recording = false
        private set

    /** Records until [stop] or the cap; [onLimit] runs (on the recording thread) when the cap ended it. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun record(onLimit: () -> Unit = {}): FloatArray = withContext(Dispatchers.IO) {
        stopRequested = false
        val chunk = FloatArray(CHUNK_SAMPLES)
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        // Tuned for speech recognition: no automatic gain or noise effects from the call path.
        val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING,
            maxOf(minBuffer, chunk.size * Float.SIZE_BYTES * 4))
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            throw IllegalStateException("the microphone is in use")
        }
        val clip = FloatArray(MAX_SAMPLES)
        var size = 0
        recording = true
        try {
            audio.startRecording()
            while (isActive && !stopRequested && size < MAX_SAMPLES) {
                val read = audio.read(chunk, 0, minOf(chunk.size, MAX_SAMPLES - size), AudioRecord.READ_BLOCKING)
                check(read >= 0) { "the microphone stopped ($read)" }
                chunk.copyInto(clip, size, 0, read)
                size += read
            }
            if (size >= MAX_SAMPLES) onLimit()
        } finally {
            recording = false
            audio.stop()
            audio.release()
        }
        clip.copyOf(size)
    }

    fun stop() {
        stopRequested = true
    }

    companion object {
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_FLOAT
        const val SAMPLE_RATE = 16_000
        /** Whistle's most per pass: 30 s. */
        const val MAX_SAMPLES = 30 * SAMPLE_RATE
        /** 100 ms per read, so Stop is prompt. */
        private const val CHUNK_SAMPLES = SAMPLE_RATE / 10
    }
}
