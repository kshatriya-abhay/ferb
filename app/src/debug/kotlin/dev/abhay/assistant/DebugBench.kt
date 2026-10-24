package dev.abhay.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.abhay.assistant.bench.PhoneBench
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Debug builds only: the phone bench's screen (Settings → Bench). Gemini Nano answers only with
 * Ferb in front, so its passes run here, with the screen kept on; a pass survives leaving this
 * screen, and a stopped one resumes where it was. Needle 3's pass can also run with no screen
 * (androidTest PhoneBenchTest). Text only: no speech recognition, no rewrite.
 */
object DebugBench {
    const val AVAILABLE = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    /** What's running and how far: "gemini-full 120/462", the last result line, or null. */
    val status = MutableStateFlow<String?>(null)
    val running = MutableStateFlow(false)
    val fraction = MutableStateFlow(0f)

    fun start(app: AssistantApp, model: PhoneBench.Model, limit: Int = Int.MAX_VALUE) {
        if (job?.isActive == true) return
        running.value = true
        job = scope.launch {
            status.value = try {
                PhoneBench(app).run(model, limit) { done, total, error ->
                    fraction.value = done.toFloat() / total
                    status.value = "${model.file}: $done of $total" + (error?.let { " (last: $it)" } ?: "")
                }
            } catch (e: Exception) {
                "${model.file} failed: ${e.message ?: e}"
            } finally {
                running.value = false
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Screen(onBack: () -> Unit) {
        val app = LocalContext.current.applicationContext as AssistantApp
        val bench = remember { PhoneBench(app) }
        val isRunning by running.collectAsStateWithLifecycle()
        val said by status.collectAsStateWithLifecycle()
        val progress by fraction.collectAsStateWithLifecycle()
        var counts by remember { mutableStateOf("") }
        LaunchedEffect(isRunning, said) {
            val total = bench.items().size
            counts = "$total requests loaded\n" + PhoneBench.Model.entries.joinToString("\n") { "${it.file}: ${bench.done(it).size} answered" }
        }
        // The phone sleeps after 120 s otherwise, and Gemini Nano stops with Ferb out of front.
        val view = LocalView.current
        DisposableEffect(isRunning) {
            view.keepScreenOn = isRunning
            onDispose { view.keepScreenOn = false }
        }
        Scaffold(topBar = {
            TopAppBar(title = { Text("Bench (debug)") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Text only: each request goes straight to the model, with its routed tools. Keep Ferb open while it runs; the screen stays on.",
                    style = MaterialTheme.typography.bodyMedium)
                Text(counts, style = MaterialTheme.typography.bodyMedium)
                said?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                if (isRunning) {
                    LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
                    OutlinedButton(onClick = ::stop) { Text("Stop") }
                } else {
                    Button(onClick = { start(app, PhoneBench.Model.GeminiFull, limit = 10) }) { Text("Trial: 10 requests, Gemini Nano FULL") }
                    Button(onClick = { start(app, PhoneBench.Model.GeminiFull) }) { Text("Run Gemini Nano FULL") }
                    Button(onClick = { start(app, PhoneBench.Model.GeminiFast) }) { Text("Run Gemini Nano FAST") }
                    OutlinedButton(onClick = { start(app, PhoneBench.Model.Needle3) }) { Text("Run Needle 3") }
                }
            }
        }
    }
}
