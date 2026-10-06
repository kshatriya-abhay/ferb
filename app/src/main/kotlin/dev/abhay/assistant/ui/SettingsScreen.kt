package dev.abhay.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.abhay.assistant.engine.AssistantEngine
import dev.abhay.assistant.tools.Tools
import java.util.Locale

/**
 * Which tools the assistant has. Switches change a draft; Save keeps it and gives the new tools to
 * Needle 3. Leaving with unsaved changes asks first. [onBack] returns to the assistant.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: AssistantViewModel, onBack: () -> Unit) {
    val saved by viewModel.enabledTools.collectAsStateWithLifecycle()
    val engine by viewModel.engine.collectAsStateWithLifecycle()
    // A list, not a set: lists of strings survive rotation in rememberSaveable.
    var draft by rememberSaveable(saved) { mutableStateOf(saved) }
    val changed = draft.toSet() != saved.toSet()
    var confirmLeave by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val back = { if (changed) confirmLeave = true else onBack() }

    // After a save, say what Needle 3 has now, once it's ready again.
    LaunchedEffect(engine, saving) {
        val ready = engine as? AssistantEngine.State.Ready ?: return@LaunchedEffect
        if (!saving || ready.tools.toSet() != saved.toSet()) return@LaunchedEffect
        saving = false
        snackbar.showSnackbar("Saved. Needle 3 has ${ready.tools.size} tools now (${ready.prefixTokens} tokens, set up in ${ready.initMs} ms).")
    }
    BackHandler(onBack = back)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tools") },
                subtitle = { Text("What the assistant can do") },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when {
                            draft.isEmpty() -> "Turn on at least one tool."
                            draft.size <= MAX_ALWAYS_SEEN -> "${draft.size} on: Needle 3 considers all of them for every request."
                            else -> "${draft.size} on: Needle 3 considers the $MAX_ALWAYS_SEEN that best match each request."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { saving = true; viewModel.saveTools(draft.toSet()) },
                        enabled = changed && draft.isNotEmpty() && engine !is AssistantEngine.State.Loading,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text(if (changed) "Save" else "Saved") }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(Tools.all, key = { it.name }) { tool ->
                val on = tool.name in draft
                ListItem(
                    headlineContent = { Text(tool.title) },
                    supportingContent = {
                        Text(tool.description + "\n“${tool.examples.first()}”\n" +
                            String.format(Locale.US, "Runs without asking from confidence %.1f", tool.runFrom))
                    },
                    trailingContent = {
                        Switch(checked = on, onCheckedChange = { checked ->
                            draft = if (checked) Tools.all.map { it.name }.filter { it in draft || it == tool.name }
                            else draft - tool.name
                        })
                    },
                )
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Save your changes?") },
            text = { Text("The assistant keeps its current tools until you save.") },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; viewModel.saveTools(draft.toSet()); onBack() }, enabled = draft.isNotEmpty()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false; onBack() }) { Text("Discard") } },
        )
    }
}

/** Above this many tools, Needle 3 only sees the best matches for each request. */
private const val MAX_ALWAYS_SEEN = 5
