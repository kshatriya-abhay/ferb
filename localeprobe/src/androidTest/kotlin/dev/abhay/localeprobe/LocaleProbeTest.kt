package dev.abhay.localeprobe

import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gemini Nano's speech recognition under several locales (tool-check/locales.py run):
 *  - files/status.jsonl: checkStatus per locale and mode, with no download (a locale that needs
 *    one is noted, never fetched), and the phone's own locales as the app sees them;
 *  - files/results.jsonl: each clip heard under each locale: the voice bench's recordings
 *    (files/clips.jsonl + files/clips/<id>.wav, pushed by locales.py) and TTS clips made here by
 *    the phone's TTS, in English, Hindi, Japanese, French and Tamil (a TTS language whose voice
 *    isn't on the phone is skipped, not downloaded).
 * Args: -e locales en-US,en-IN,hi-IN,ja-JP,fr-FR  -e modes advanced[,basic]  -e tts true|false.
 * Resumable: lines already in results.jsonl are skipped. The probe's screen is kept in front.
 */
@RunWith(AndroidJUnit4::class)
class LocaleProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val files = context.filesDir

    @Test
    fun probe() {
        val args = InstrumentationRegistry.getArguments()
        val locales = (args.getString("locales") ?: DEFAULT_LOCALES).split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val modes = (args.getString("modes") ?: "advanced").split(",").map { it.trim() }
        // The recogniser needs RECORD_AUDIO even for a file's audio: the probe's own, granted here.
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.RECORD_AUDIO)
        instrumentation.startActivitySync(Intent(context, ProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(1_500) // In front before AICore is asked.

        val status = File(files, "status.jsonl").also { it.delete() }
        status.appendText(JSONObject()
            .put("phone_locale", Locale.getDefault().toLanguageTag())
            .put("phone_locales", LocaleList.getDefault().toLanguageTags())
            .put("app_locales", context.resources.configuration.locales.toLanguageTags())
            .toString() + "\n")
        val usable = mutableListOf<Pair<String, String>>()
        runBlocking {
            for (mode in modes) for (tag in locales) {
                val line = JSONObject().put("locale", tag).put("mode", mode)
                val client = client(tag, mode)
                try {
                    val s = client.checkStatus()
                    line.put("status", name(s))
                    if (s == FeatureStatus.AVAILABLE) usable += tag to mode
                } catch (e: Exception) {
                    line.put("error", e.message ?: e.toString())
                } finally {
                    client.close()
                }
                Log.i(TAG, "status $line")
                status.appendText("$line\n")
                Thread.sleep(PACE_MS)
            }
        }

        val clips = benchClips() + if (args.getString("tts") != "false") ttsClips() else emptyList()
        val results = File(files, "results.jsonl")
        val done = results.takeIf { it.exists() }?.readLines().orEmpty().filter { it.isNotBlank() }
            .map(::JSONObject).map { Triple(it.getString("id"), it.getString("locale"), it.getString("mode")) }.toSet()
        var n = 0
        runBlocking {
            for ((tag, mode) in usable) for (clip in clips) {
                if (Triple(clip.id, tag, mode) in done) continue
                val line = JSONObject().put("id", clip.id).put("source", clip.source).put("lang", clip.lang)
                    .put("card", clip.card).put("text", clip.text).put("locale", tag).put("mode", mode)
                hear(clip.pcm16, tag, mode, line)
                results.appendText("$line\n")
                if (++n % 10 == 0) Log.i(TAG, "heard $n")
                Thread.sleep(PACE_MS)
            }
        }
        Log.i(TAG, "done: $n new results, ${clips.size} clips, ${usable.size} usable locale/mode pairs")
    }

    private fun client(tag: String, mode: String) = SpeechRecognition.getClient(speechRecognizerOptions {
        locale = Locale.forLanguageTag(tag)
        preferredMode = if (mode == "basic") SpeechRecognizerOptions.Mode.MODE_BASIC else SpeechRecognizerOptions.Mode.MODE_ADVANCED
    })

    /** One clip, one-shot from a pipe (as Ferb's voice bench, which found streaming from a pipe failing). */
    private suspend fun hear(pcm16: ByteArray, tag: String, mode: String, line: JSONObject) {
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val client = client(tag, mode)
        val (read, write) = ParcelFileDescriptor.createPipe()
        val feeder = thread(name = "probe-feed") {
            ParcelFileDescriptor.AutoCloseOutputStream(write).use { os -> runCatching { os.write(pcm16) } }
        }
        val started = SystemClock.elapsedRealtime()
        var final: String? = null
        var last: String? = null
        try {
            client.startRecognition(speechRecognizerRequest { audioSource = AudioSource.fromPfd(read, AudioSource.Mode.ONE_SHOT, format) })
                .takeWhile { it !is SpeechRecognizerResponse.CompletedResponse }.collect { r ->
                    when (r) {
                        is SpeechRecognizerResponse.PartialTextResponse -> last = r.text
                        is SpeechRecognizerResponse.FinalTextResponse -> final = (final?.let { "$it " } ?: "") + r.text
                        is SpeechRecognizerResponse.ErrorResponse -> throw r.e
                        else -> {}
                    }
                }
        } catch (e: Exception) {
            if ("NO_SPEECH" in (e.message ?: "")) line.put("no_speech", true) else line.put("error", e.message ?: e.toString())
        } finally {
            runCatching { read.close() }
            feeder.join(5_000)
            client.close()
        }
        line.put("heard", (final ?: last ?: "").trim()).put("ms", SystemClock.elapsedRealtime() - started)
    }

    private class Clip(val id: String, val source: String, val lang: String, val card: String, val text: String, val pcm16: ByteArray)

    /** The voice bench's recordings locales.py pushed: {"id", "lang", "card"} and clips/<id>.wav. */
    private fun benchClips(): List<Clip> {
        val list = File(files, "clips.jsonl").takeIf { it.exists() } ?: return emptyList()
        return list.readLines().filter { it.isNotBlank() }.map(::JSONObject).mapNotNull { o ->
            val wav = File(files, "clips/${o.getString("id")}.wav").takeIf { it.exists() } ?: return@mapNotNull null
            Clip(o.getString("id"), "bench", o.getString("lang"), o.optString("card"), "", pcm16k(wav))
        }
    }

    /** The phrases, said by the phone's TTS in each language whose voice is on the phone. */
    private fun ttsClips(): List<Clip> {
        val ready = CountDownLatch(1)
        var ok = false
        lateinit var tts: TextToSpeech
        instrumentation.runOnMainSync { tts = TextToSpeech(context) { ok = it == TextToSpeech.SUCCESS; ready.countDown() } }
        ready.await(20, TimeUnit.SECONDS)
        val status = File(files, "status.jsonl")
        if (!ok) { status.appendText(JSONObject().put("tts", "unavailable").toString() + "\n"); return emptyList() }
        val clips = mutableListOf<Clip>()
        try {
            for ((lang, phrases) in PHRASES) {
                val locale = Locale.forLanguageTag(TTS_LOCALES.getValue(lang))
                val has = tts.isLanguageAvailable(locale)
                status.appendText(JSONObject().put("tts", lang).put("voice", if (has >= TextToSpeech.LANG_AVAILABLE) "on the phone" else "missing ($has)").toString() + "\n")
                if (has < TextToSpeech.LANG_AVAILABLE) continue
                tts.language = locale
                phrases.forEachIndexed { i, text ->
                    val id = "tts-$lang-${i + 1}"
                    val out = File(files, "tts/$id.wav").also { it.parentFile!!.mkdirs(); it.delete() }
                    val done = CountDownLatch(1)
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) = done.countDown()
                        @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) = done.countDown()
                        override fun onError(utteranceId: String?, errorCode: Int) = done.countDown()
                    })
                    tts.synthesizeToFile(text, Bundle(), out, id)
                    done.await(30, TimeUnit.SECONDS)
                    if (out.length() > 44) clips += Clip(id, "tts", lang, "TTS: $text", text, pcm16k(out))
                    else status.appendText(JSONObject().put("tts", id).put("error", "nothing synthesized").toString() + "\n")
                }
            }
        } finally {
            tts.shutdown()
        }
        return clips
    }

    companion object {
        const val TAG = "LocaleProbe"
        const val RATE = 16_000
        /** Between AICore calls, as Ferb paces its checks. */
        const val PACE_MS = 500L
        const val DEFAULT_LOCALES = "en-US,en-IN,hi-IN,ja-JP,fr-FR"

        /** The same three requests in each language: an alarm with a time, a call, a media command. */
        val PHRASES = linkedMapOf(
            "en" to listOf("Set an alarm for seven tomorrow morning", "Call my mother", "Pause the music"),
            "hi" to listOf("कल सुबह सात बजे का अलार्म लगा दो", "मम्मी को फ़ोन करो", "गाना रोक दो"),
            "ja" to listOf("明日の朝七時にアラームをセットして", "母に電話して", "音楽を止めて"),
            "fr" to listOf("Mets une alarme demain matin à sept heures", "Appelle ma mère", "Mets la musique en pause"),
            "ta" to listOf("நாளை காலை ஏழு மணிக்கு அலாரம் வை", "அம்மாவுக்கு போன் பண்ணு", "பாட்டை நிறுத்து"),
        )
        val TTS_LOCALES = mapOf("en" to "en-US", "hi" to "hi-IN", "ja" to "ja-JP", "fr" to "fr-FR", "ta" to "ta-IN")

        fun name(status: Int) = when (status) {
            FeatureStatus.AVAILABLE -> "AVAILABLE"
            FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
            FeatureStatus.DOWNLOADING -> "DOWNLOADING"
            FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
            else -> "status $status"
        }

        /** A WAV's 16-bit PCM as 16 kHz mono (TTS writes its own rate; the bench's are 16 kHz already). */
        fun pcm16k(wav: File): ByteArray = RandomAccessFile(wav, "r").use { f ->
            val bytes = ByteArray(f.length().toInt()).also { f.readFully(it) }
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var rate = RATE
            var channels = 1
            var at = 12
            var data: ShortArray? = null
            while (at + 8 <= bytes.size) {
                val id = String(bytes, at, 4, Charsets.US_ASCII)
                val size = b.getInt(at + 4)
                if (id == "fmt ") { channels = b.getShort(at + 10).toInt(); rate = b.getInt(at + 12) }
                if (id == "data") {
                    val n = minOf(size, bytes.size - at - 8) / 2
                    data = ShortArray(n) { b.getShort(at + 8 + 2 * it) }
                    break
                }
                at += 8 + size + (size and 1)
            }
            val samples = data ?: ShortArray(0)
            val mono = if (channels <= 1) samples else ShortArray(samples.size / channels) { i -> (0 until channels).sumOf { samples[i * channels + it].toInt() }.div(channels).toShort() }
            val out = if (rate == RATE) mono else ShortArray((mono.size.toLong() * RATE / rate).toInt()) { i ->
                val x = i.toDouble() * rate / RATE
                val j = x.toInt().coerceAtMost(mono.size - 1)
                val k = (j + 1).coerceAtMost(mono.size - 1)
                (mono[j] + (mono[k] - mono[j]) * (x - j)).toInt().toShort()
            }
            ByteBuffer.allocate(out.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { buf -> out.forEach { buf.putShort(it) } }.array()
        }
    }
}
