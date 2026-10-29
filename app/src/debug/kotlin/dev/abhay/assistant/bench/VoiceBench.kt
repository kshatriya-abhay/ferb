package dev.abhay.assistant.bench

import android.media.AudioFormat
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import dev.abhay.assistant.AssistantApp
import dev.abhay.assistant.engine.NeedleReply
import dev.abhay.assistant.pipeline.AudioStep
import dev.abhay.assistant.pipeline.Pipeline
import dev.abhay.assistant.pipeline.RewriteStep
import dev.abhay.assistant.pipeline.ToolStep
import dev.abhay.assistant.tools.ToolSettings
import dev.abhay.assistant.tools.Tools
import dev.abhay.assistant.ui.AnswerFlow
import dev.abhay.assistant.ui.Step
import dev.abhay.assistant.voice.GeminiRewrite
import dev.abhay.assistant.voice.NanoEars
import dev.abhay.assistant.voice.NanoSpeech
import dev.abhay.assistant.voice.PcmRecorder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.takeWhile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug builds only: the voice bench (2026-10-29), an observation tool for the user, not a scored
 * test. For a scenario card ([VoiceCards]) the user speaks in their own words; each entry keeps the
 * audio (files/bench/voice-study/<id>.wav, 16 kHz mono 16-bit), what Gemini Nano's speech heard
 * (the shipped en-US recogniser, the clip played to it at real-time pace, with each partial
 * result's time), then the shipped answer: the rewrite (prompt R), Needle 3's call and DateGate's
 * reading, read-only (nothing runs), with R2's rewrite alongside. Entries in entries.jsonl.
 */
class VoiceBench(private val app: AssistantApp) {
    val dir = File(app.filesDir, "bench/voice-study")
    private val file get() = File(dir, "entries.jsonl")

    fun entries(): List<JSONObject> = file.takeIf { it.exists() }?.readLines().orEmpty().filter { it.isNotBlank() }.map(::JSONObject)

    private fun write(entries: List<JSONObject>) {
        dir.mkdirs()
        file.writeText(entries.joinToString("") { it.toString() + "\n" })
    }

    fun delete(id: String) {
        write(entries().filter { it.getString("id") != id })
        File(dir, "$id.wav").delete()
    }

    /**
     * A take of [card]: saved, heard, answered. [replaces]: a redo's entry, whose place it takes.
     * [progress] says which step it's on.
     */
    suspend fun process(card: String, pcm: FloatArray, replaces: String? = null, progress: (String) -> Unit = {}): JSONObject {
        dir.mkdirs()
        val id = replaces ?: "e${System.currentTimeMillis()}"
        writeWav(File(dir, "$id.wav"), pcm)
        val entry = JSONObject().put("id", id).put("card", card).put("at", System.currentTimeMillis())
            .put("audio_ms", pcm.size * 1000L / PcmRecorder.SAMPLE_RATE)
        progress("Gemini Nano is listening…")
        hear(readPcm16(File(dir, "$id.wav")), entry)
        val heard = entry.optString("heard")
        if (heard.isNotBlank()) {
            progress("Rewriting and choosing the tool…")
            answer(heard, entry)
            progress("R2's rewrite…")
            entry.put("r2", runCatching { rewriteR2(heard) }.getOrElse { "failed: ${it.message}" })
        }
        val all = entries().filter { it.getString("id") != id } + entry
        write(all.sortedBy { it.getLong("at") })
        return entry
    }

    /**
     * Gemini Nano's speech on the clip, played at real-time pace: partials [[ms, text]], heard, and
     * how long after the audio ended. AudioSource.fromPfd's modes are tried in turn (streaming,
     * one-shot, its defaults) and the one that worked is kept ("speech_mode").
     */
    private suspend fun hear(pcm16: ByteArray, entry: JSONObject) {
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(PcmRecorder.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        // One-shot first: streaming from a pipe failed in the recogniser (ERROR_TYPE_SODA, 2026-10-29),
        // so the partials' times are processing times, not live; the WAV keeps the real pauses.
        val modes = listOf<Pair<String, (ParcelFileDescriptor) -> AudioSource>>(
            "one-shot" to { pfd -> AudioSource.fromPfd(pfd, AudioSource.Mode.ONE_SHOT, format) },
            "default" to { pfd -> AudioSource.fromPfd(pfd) },
            "streaming" to { pfd -> AudioSource.fromPfd(pfd, AudioSource.Mode.STREAMING, format) },
        )
        val errors = mutableListOf<String>()
        for ((name, source) in modes) {
            val got = JSONObject()
            if (hearOnce(pcm16, source, paced = name == "streaming", got)) {
                got.keys().forEach { entry.put(it, got.get(it)) }
                entry.put("speech_mode", name)
                if (errors.isNotEmpty()) entry.put("speech_tries", JSONArray(errors))
                return
            }
            errors += "$name: ${got.optString("speech_error")}"
        }
        entry.put("speech_error", errors.joinToString("; ")).put("partials", JSONArray()).put("heard", "")
    }

    /** One try: true when the recogniser ran to its end without an error. */
    private suspend fun hearOnce(pcm16: ByteArray, source: (ParcelFileDescriptor) -> AudioSource, paced: Boolean, entry: JSONObject): Boolean {
        val client = SpeechRecognition.getClient(speechRecognizerOptions {
            locale = NanoSpeech.LOCALE
            preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED
        })
        val partials = JSONArray()
        val (read, write) = ParcelFileDescriptor.createPipe()
        val started = SystemClock.elapsedRealtime()
        val feeder = thread(name = "voice-feed") {
            ParcelFileDescriptor.AutoCloseOutputStream(write).use { os ->
                val chunk = PcmRecorder.SAMPLE_RATE / 10 * 2
                var at = 0
                while (at < pcm16.size) {
                    val n = minOf(chunk, pcm16.size - at)
                    if (runCatching { os.write(pcm16, at, n) }.isFailure) return@thread
                    at += n
                    if (!paced) continue
                    val wait = started + at.toLong() / 2 * 1000 / PcmRecorder.SAMPLE_RATE - SystemClock.elapsedRealtime()
                    if (wait > 0) Thread.sleep(wait)
                }
            }
        }
        var final: String? = null
        var ok = true
        try {
            client.startRecognition(speechRecognizerRequest { audioSource = source(read) })
                .takeWhile { it !is SpeechRecognizerResponse.CompletedResponse }.collect { r ->
                    val ms = SystemClock.elapsedRealtime() - started
                    when (r) {
                        is SpeechRecognizerResponse.PartialTextResponse -> partials.put(JSONArray().put(ms).put(r.text))
                        is SpeechRecognizerResponse.FinalTextResponse -> { final = (final?.let { "$it " } ?: "") + r.text; entry.put("final_at_ms", ms) }
                        is SpeechRecognizerResponse.ErrorResponse -> throw r.e
                        else -> {}
                    }
                }
        } catch (e: Exception) {
            // Nothing said is an answer (heard nothing), not a failure.
            if ("NO_SPEECH" in (e.message ?: "")) entry.put("no_speech", true) else {
                ok = false
                entry.put("speech_error", e.message ?: e.toString())
            }
        } finally {
            runCatching { read.close() }
            feeder.join(5_000)
            client.close()
        }
        if (!ok) return false
        entry.put("partials", partials)
        val heard = final ?: (partials.length().takeIf { it > 0 }?.let { partials.getJSONArray(it - 1).getString(1) } ?: "")
        entry.put("heard", heard.trim())
        entry.optLong("final_at_ms").takeIf { it > 0 }?.let { entry.put("heard_after_audio_ms", it - (pcm16.size / 2 * 1000L / PcmRecorder.SAMPLE_RATE)) }
        return true
    }

    /** The shipped answer, read-only: Gemini Nano's rewrite (prompt R), Needle 3, DateGate. Nothing runs. */
    private suspend fun answer(heard: String, entry: JSONObject) {
        val configs = app.toolSettings.configs.value
        val enabled = ToolSettings.enabledNames(configs).mapNotNull(Tools::byName)
        val needle: suspend (String, List<dev.abhay.assistant.tools.Tool>) -> NeedleReply = { request, tools ->
            NeedleReply.parse(app.engine.complete(request, tools.map { it.name }, Tools.json(tools)))
        }
        val started = SystemClock.elapsedRealtime()
        val flow = AnswerFlow(app.link, needle = needle, gemini = { app.nanoEars.rewrite(it) })
        val answer = flow.answer(heard, enabled, configs, Pipeline(AudioStep.Gemini, RewriteStep.Gemini, ToolStep.Off),
            heardBy = listOf(Step("Voice: Gemini Nano")), geminiUsable = true)
        entry.put("answer_ms", SystemClock.elapsedRealtime() - started)
        entry.put("rewrite", answer.command ?: if (answer.nothingAsked) "NONE" else heard)
        entry.put("path", Step.chain(answer.path))
        entry.put("decision", answer.verdict.decision.name)
        entry.put("confidence", answer.shown.confidence ?: JSONObject.NULL)
        entry.put("calls", JSONArray(answer.shown.calls.map { c -> JSONObject().put("name", c.name).put("arguments", JSONObject(c.arguments.mapValues { it.value?.toString() })) }))
        entry.put("held_back", JSONArray(answer.shown.suppressed.map { it.name }))
    }

    private var r2: com.google.mlkit.genai.prompt.GenerativeModel? = null

    /** R2 (the event rule, not shipped) on the same heard text, labelled apart. */
    private suspend fun rewriteR2(heard: String): String {
        val client = r2 ?: Generation.getClient(generationConfig {
            modelConfig = modelConfig { releaseStage = ModelReleaseStage.STABLE; preference = ModelPreference.FAST }
        }).also { r2 = it }
        val raw = client.generateContent(NanoEars.rewriteRequest(heard, GeminiRewrite.PROMPT_R2)).candidates.firstOrNull()?.text.orEmpty()
        return runCatching { GeminiRewrite.parse(heard, raw).command ?: "NONE" }.getOrElse { "unreadable: ${raw.trim()}" }
    }

    fun close() {
        r2?.close()
        r2 = null
    }

    companion object {
        fun writeWav(file: File, pcm: FloatArray) {
            val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (x in pcm) data.putShort((x.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
            val rate = PcmRecorder.SAMPLE_RATE
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + pcm.size * 2); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(pcm.size * 2)
            }
            FileOutputStream(file).use { it.write(header.array()); it.write(data.array()) }
        }

        /** A WAV's 16-bit samples, as bytes (the 44-byte header skipped). */
        fun readPcm16(file: File): ByteArray = RandomAccessFile(file, "r").use { f ->
            val bytes = ByteArray((f.length() - 44).toInt().coerceAtLeast(0))
            f.seek(44); f.readFully(bytes); bytes
        }
    }
}
