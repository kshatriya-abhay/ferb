package dev.abhay.assistant.link

import android.content.Context
import dev.abhay.assistant.engine.ToolCall
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * What became of each spoken request, one JSON line each, for the audio bench: what the computer
 * heard, the English command, the calls and how Ferb read them (e.g. "Open Paytm" from "ptm").
 * Kept in the app's own files, on the phone; tool-check/voice_score.py pulls it over adb
 * (`run-as`, debug builds) and scores it against tool-check/audio.txt.
 */
class VoiceLog(private val context: Context) {
    private val file get() = File(context.filesDir, FILE)

    fun add(heard: String, text: String, calls: List<ToolCall>, read: List<String>, asked: Boolean, fromComputer: Boolean, ms: Long) {
        val line = JSONObject()
            .put("at", System.currentTimeMillis())
            .put("heard", heard)
            .put("text", text)
            .put("calls", JSONArray(calls.map { JSONObject().put("name", it.name).put("arguments", JSONObject(it.arguments)) }))
            .put("read", JSONArray(read))
            .put("asked", asked)
            .put("computer", fromComputer)
            .put("ms", ms)
        try {
            file.appendText(line.toString() + "\n")
        } catch (e: Exception) {
            // Only the bench reads it; the request is answered either way.
        }
    }

    companion object {
        const val FILE = "voice-log.jsonl"
    }
}
