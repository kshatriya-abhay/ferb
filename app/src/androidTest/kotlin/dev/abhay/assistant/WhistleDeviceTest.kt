package dev.abhay.assistant

import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.engine.NeedleReply
import dev.abhay.assistant.tools.SetTimer
import dev.abhay.assistant.tools.Tools
import dev.abhay.assistant.voice.PcmRecorder
import dev.abhay.assistant.voice.SpeechCheck
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whistle beside Needle 3 in Ferb's process, in the background (nothing shown or played): the
 * memory each adds, how long transcription takes (a short request, three times, and a full 30 s
 * clip), and that Needle 3 still answers after Whistle loads. The clip is the phone's own
 * text-to-speech written to a file. Run alone, so the process is fresh and Needle 3 not yet loaded:
 * `am instrument -w -e class dev.abhay.assistant.WhistleDeviceTest …`.
 */
@RunWith(AndroidJUnit4::class)
class WhistleDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AssistantApp

    @Test
    fun whistleBesideNeedle3() = runBlocking<Unit> {
        val clip = speak("Set a timer for ten minutes")
        assumeTrue("text-to-speech on this phone", clip != null)
        val base = memory()
        val tools = listOf(SetTimer)
        app.engine.configure(tools.map { it.name }, Tools.json(tools))
        val needle = memory()

        assertThat(SpeechCheck.mayHoldSpeech(clip!!)).isTrue()
        val first = app.engine.transcribe(clip)
        val both = memory()
        val runs = (1..3).map { app.engine.transcribe(clip).ms }.sorted()
        Log.i(TAG, "heard '${first.text}' (${first.language}); ${first.audioMs} ms of audio: first ${first.ms} ms, then ${runs.joinToString()} ms; Whistle load ${app.engine.whistleLoaded} ms")
        assertThat(first.text.lowercase()).contains("timer")

        // The longest a phone recording can be: 30 s, the request then silence.
        val full = FloatArray(PcmRecorder.MAX_SAMPLES).also { clip.copyInto(it, 0, 0, minOf(clip.size, it.size)) }
        val long = app.engine.transcribe(full)
        Log.i(TAG, "30 s clip: heard '${long.text}' in ${long.ms} ms")
        assertThat(long.text.lowercase()).contains("timer")
        val after = memory()

        // Needle 3 keeps its tools and answers after Whistle loaded beside it.
        val started = SystemClock.elapsedRealtime()
        val reply = NeedleReply.parse(app.engine.complete(first.text, tools.map { it.name }, Tools.json(tools)))
        Log.i(TAG, "Needle 3 after Whistle: ${reply.calls.map { it.signature() }} in ${SystemClock.elapsedRealtime() - started} ms")
        assertThat(reply.calls.map { it.name }).containsExactly(SetTimer.name)

        Log.i(TAG, "memory (private RssAnon / peak VmHWM, MB): start $base; Needle 3 $needle; + Whistle, after one transcription $both; after the 30 s clip $after")
    }

    /** Private memory (RssAnon) and the peak resident size (VmHWM), in MB, from /proc/self/status. */
    private data class Memory(val privateMb: Long, val peakMb: Long) {
        override fun toString() = "$privateMb / $peakMb"
    }

    private fun memory(): Memory {
        val status = File("/proc/self/status").readLines()
        fun kb(key: String) = status.first { it.startsWith("$key:") }.split(Regex("\\s+"))[1].toLong()
        return Memory(kb("RssAnon") / 1024, kb("VmHWM") / 1024)
    }

    /** [text] from the phone's text-to-speech as 16 kHz mono float PCM, written to a file and never played; null without an engine. */
    private fun speak(text: String): FloatArray? {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "whistle-clip.wav")
        val ready = CountDownLatch(1)
        val done = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        lateinit var tts: TextToSpeech
        InstrumentationRegistry.getInstrumentation().runOnMainSync { tts = TextToSpeech(context) { status = it; ready.countDown() } }
        try {
            if (!ready.await(10, TimeUnit.SECONDS) || status != TextToSpeech.SUCCESS) return null
            tts.language = Locale.UK
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) = done.countDown()
                @Deprecated("old API") override fun onError(id: String?) = done.countDown()
            })
            if (tts.synthesizeToFile(text, null, file, "clip") != TextToSpeech.SUCCESS) return null
            if (!done.await(20, TimeUnit.SECONDS) || file.length() < 1000) return null
            return pcm16k(file.readBytes())
        } finally {
            tts.shutdown()
            file.delete()
        }
    }

    /** A 16-bit PCM WAV file as 16 kHz mono floats (linear resampling: good enough for a test clip). */
    private fun pcm16k(wav: ByteArray): FloatArray {
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var at = 12
        var channels = 1
        var rate = 16_000
        while (at + 8 <= wav.size) {
            val id = String(wav, at, 4, Charsets.US_ASCII)
            val size = b.getInt(at + 4)
            if (id == "fmt ") { channels = b.getShort(at + 10).toInt(); rate = b.getInt(at + 12) }
            if (id == "data") {
                val count = minOf(size, wav.size - at - 8) / 2 / channels
                val src = FloatArray(count) { i -> b.getShort(at + 8 + i * 2 * channels) / 32768f }
                val n = (count.toLong() * PcmRecorder.SAMPLE_RATE / rate).toInt()
                return FloatArray(n) { i ->
                    val x = i.toDouble() * rate / PcmRecorder.SAMPLE_RATE
                    val j = x.toInt().coerceAtMost(count - 1)
                    val k = (j + 1).coerceAtMost(count - 1)
                    (src[j] + (src[k] - src[j]) * (x - j)).toFloat()
                }
            }
            at += 8 + size + (size and 1)
        }
        error("no data chunk")
    }

    private companion object {
        const val TAG = "WhistleDeviceTest"
    }
}
