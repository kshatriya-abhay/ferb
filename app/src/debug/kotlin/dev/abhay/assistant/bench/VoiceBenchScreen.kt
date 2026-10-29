package dev.abhay.assistant.bench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import dev.abhay.assistant.AssistantApp
import dev.abhay.assistant.voice.PcmRecorder
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Debug builds only: the voice bench screen (Settings → Bench → Voice bench). A card says what to
 * ask for; the user says it their own way, then taps ✓ (no auto-send here). Each entry shows what
 * Gemini Nano heard, the rewrite (and R2's), the tool call and the date Ferb read, nothing run.
 * Shown inside the bench screen's column.
 */
@Composable
fun VoiceBenchScreen(app: AssistantApp, onDone: () -> Unit) {
    val bench = remember { VoiceBench(app) }
    var entries by remember { mutableStateOf(bench.entries()) }
    val doneCards = entries.map { it.getString("card") }.toSet()
    var index by remember { mutableIntStateOf(VoiceCards.ALL.indexOfFirst { it !in doneCards }.coerceAtLeast(0)) }
    var card by remember { mutableStateOf(VoiceCards.ALL[index]) }
    var redo by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf<String?>(null) }
    val recorder = remember { PcmRecorder() }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false; recorder.stop(); bench.close() }
    }
    fun pick(i: Int) { index = (i + VoiceCards.ALL.size) % VoiceCards.ALL.size; card = VoiceCards.ALL[index]; redo = null }

    Text("${entries.size} entries · card ${index + 1} of ${VoiceCards.ALL.size}", style = MaterialTheme.typography.labelLarge)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (redo != null) "Redo: $card" else card, style = MaterialTheme.typography.titleLarge)
            Text("Say it your own way, then tap ✓.", style = MaterialTheme.typography.bodySmall)
            when {
                working != null -> Text(working!!, color = MaterialTheme.colorScheme.primary)
                recording -> Button(onClick = { recorder.stop() }) { Text("✓ Done speaking") }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        recording = true
                        scope.launch {
                            val pcm = runCatching { recorder.record() }.getOrNull()
                            recording = false
                            if (pcm == null || pcm.isEmpty()) return@launch
                            working = "Saving…"
                            runCatching { bench.process(card, pcm, replaces = redo) { working = it } }
                            entries = bench.entries()
                            working = null
                            if (redo == null) pick(index + 1) else redo = null
                        }
                    }) { Text("Record") }
                    OutlinedButton(onClick = { pick(index - 1) }) { Text("‹") }
                    OutlinedButton(onClick = { pick(index + 1) }) { Text("›") }
                    TextButton(onClick = { card = "Free: anything you like"; redo = null }) { Text("Free") }
                }
            }
        }
    }
    TextButton(onClick = onDone) { Text("Back to the bench") }
    entries.reversed().forEach { e -> Entry(e, onRedo = { card = e.getString("card"); redo = e.getString("id") }, onDelete = {
        bench.delete(e.getString("id")); entries = bench.entries()
    }) }
}

@Composable
private fun Entry(e: JSONObject, onRedo: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(e.getString("card"), style = MaterialTheme.typography.labelMedium)
            Text("Heard: ${e.optString("heard").ifBlank { "(nothing)" }}" + (e.optString("speech_error").takeIf { it.isNotBlank() }?.let { " · error: $it" } ?: ""),
                style = MaterialTheme.typography.bodyLarge)
            if (e.has("rewrite")) Text("Rewrite (R): ${e.optString("rewrite")}")
            if (e.has("r2")) Text("R2: ${e.optString("r2")}", style = MaterialTheme.typography.bodySmall)
            val calls = e.optJSONArray("calls")
            if (calls != null) {
                val shown = (0 until calls.length()).joinToString("; ") { i ->
                    val c = calls.getJSONObject(i)
                    c.getString("name") + " " + c.getJSONObject("arguments").toString()
                }
                Text("Call: ${shown.ifBlank { "none" }} · ${e.optString("decision")}", style = MaterialTheme.typography.bodySmall)
            }
            e.optString("path").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("Audio ${e.optLong("audio_ms")} ms · heard ${e.optLong("heard_after_audio_ms")} ms after · answer ${e.optLong("answer_ms")} ms",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRedo) { Text("Redo") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}
