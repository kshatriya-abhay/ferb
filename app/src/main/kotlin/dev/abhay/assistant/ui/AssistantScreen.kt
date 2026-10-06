package dev.abhay.assistant.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.abhay.assistant.engine.AssistantEngine
import dev.abhay.assistant.engine.Decision
import dev.abhay.assistant.tools.FollowUp
import dev.abhay.assistant.tools.Outcome
import dev.abhay.assistant.tools.Prepared
import dev.abhay.assistant.tools.Tools
import dev.abhay.assistant.ui.AssistantViewModel.Status
import java.util.Locale

/**
 * The assistant, as a chat: requests on the right, what was done about them on the left, newest at
 * the bottom (where the list stays unless you scroll up through the history), and a box to type
 * the next request. Results can have buttons (Turn off, Undo…). The tools are behind the top bar's
 * settings icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(viewModel: AssistantViewModel, onOpenSettings: () -> Unit) {
    val engine by viewModel.engine.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val enabled by viewModel.enabledTools.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Assistant") },
                subtitle = { Text(subtitle(engine)) },
                actions = {
                    if (state.history.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearHistory) { Icon(Icons.Default.Delete, contentDescription = "Clear history") }
                    }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Tools") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            when (val e = engine) {
                AssistantEngine.State.Loading -> Loading(Modifier.weight(1f))
                is AssistantEngine.State.Failed -> Failed(e.message, viewModel::retry, Modifier.weight(1f))
                is AssistantEngine.State.Ready -> History(state, enabled, viewModel, Modifier.weight(1f))
            }
            state.notice?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
            InputBar(state, ready = engine is AssistantEngine.State.Ready, onChange = viewModel::onInputChange, onSend = { viewModel.send() })
        }
    }
}

private fun subtitle(engine: AssistantEngine.State) = when (engine) {
    AssistantEngine.State.Loading -> "Loading Needle 3…"
    is AssistantEngine.State.Failed -> "Needle 3 didn't load"
    is AssistantEngine.State.Ready -> "Needle 3 on this phone · ${engine.tools.size} tools · offline"
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Loading(modifier: Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        ContainedLoadingIndicator(Modifier.size(96.dp))
        Spacer(Modifier.height(24.dp))
        Text("Loading Needle 3", style = MaterialTheme.typography.titleLarge)
        Text("From the app itself; nothing is downloaded", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Failed(message: String, onRetry: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Needle 3 didn't load", style = MaterialTheme.typography.titleLarge)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp))
        FilledTonalButton(onClick = onRetry) { Text("Try again") }
    }
}

/**
 * The conversation, laid out from the bottom (a reversed list), so the newest message is always
 * in view: a new one scrolls the list back down; scrolling up shows older ones. The welcome, with
 * requests to try, is the oldest item.
 */
@Composable
private fun History(state: AssistantViewModel.UiState, enabled: List<String>, viewModel: AssistantViewModel, modifier: Modifier) {
    val list = rememberLazyListState()
    // A request sent, or an answer arriving: back to the newest message.
    LaunchedEffect(state.pending, state.history.lastOrNull()?.id) { list.animateScrollToItem(0) }
    LazyColumn(
        modifier,
        state = list,
        reverseLayout = true,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
    ) {
        state.pending?.let { request ->
            item(key = "thinking") { Thinking() }
            item(key = "pending") { RequestBubble(request) }
        }
        items(state.history.asReversed(), key = { it.id }) { exchange ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RequestBubble(exchange.request)
                ReplyBubble(
                    exchange,
                    onRun = { viewModel.run(exchange.id) },
                    onSkip = { viewModel.skip(exchange.id) },
                    onFollowUp = { index, action -> viewModel.followUp(exchange.id, index, action) },
                )
            }
        }
        item(key = "welcome") { Welcome(enabled, onTry = viewModel::send, busy = state.busy) }
    }
}

/** The assistant's side of the chat: a bubble on the left. */
@Composable
private fun AssistantBubble(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp),
            modifier = Modifier.widthIn(max = 360.dp),
        ) { Box(Modifier.padding(16.dp)) { content() } }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Thinking() {
    AssistantBubble {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LoadingIndicator(Modifier.size(32.dp))
            Text("Thinking…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What the assistant can do right now (the tools turned on), with requests to try. */
@Composable
private fun Welcome(enabled: List<String>, onTry: (String) -> Unit, busy: Boolean) {
    val tools = enabled.mapNotNull(Tools::byName)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ask me to: " + tools.joinToString { it.title.lowercase() } + ".", style = MaterialTheme.typography.bodyMedium)
        Text("Everything runs on this phone. The app has no internet access.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tools.flatMap { it.examples }.forEach { example ->
                AssistChip(onClick = { onTry(example) }, enabled = !busy, label = { Text(example) })
            }
        }
    }
}

@Composable
private fun RequestBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
            modifier = Modifier.widthIn(max = 300.dp)) {
            Text(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * What Needle 3 made of the request: each call with what became of it and its buttons, the
 * confidence against the bar it needed, and Do it / Skip when it was below that bar.
 */
@Composable
private fun ReplyBubble(
    exchange: AssistantViewModel.Exchange,
    onRun: () -> Unit,
    onSkip: () -> Unit,
    onFollowUp: (Int, FollowUp) -> Unit,
) {
    val reply = exchange.reply
    AssistantBubble {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                reply.error != null -> Text(reply.error, color = MaterialTheme.colorScheme.error)
                exchange.calls.isEmpty() -> Text("I can't do that with the tools I have.", style = MaterialTheme.typography.bodyLarge)
                exchange.decision == Decision.Confirm && exchange.calls.any { it.status == Status.Waiting } ->
                    Text("Not sure I understood. Shall I do this?", style = MaterialTheme.typography.bodyLarge)
            }
            exchange.calls.forEachIndexed { i, call -> CallRow(call, onFollowUp = { action -> onFollowUp(i, action) }) }
            if (exchange.calls.any { it.status == Status.Waiting }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRun) { Text("Do it") }
                    OutlinedButton(onClick = onSkip) { Text("Skip") }
                }
            }
            Confidence(reply.confidence, exchange.runAt, exchange.decision, hasCalls = exchange.calls.isNotEmpty())
            if (reply.reasoning.isNotBlank()) {
                Text(reply.reasoning, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(String.format(Locale.US, "%d ms on this phone", exchange.wallMs), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One call: what it does or did, the raw call, and the result's buttons (Turn off, Undo…). */
@Composable
private fun CallRow(state: AssistantViewModel.CallState, onFollowUp: (FollowUp) -> Unit) {
    val status = state.status
    val (title, color, struck) = when (status) {
        is Status.Finished -> when (val o = status.outcome) {
            is Outcome.Done -> Triple("✓ ${o.message}", MaterialTheme.colorScheme.primary, false)
            is Outcome.Failed -> Triple("✕ ${o.reason}", MaterialTheme.colorScheme.error, false)
        }
        Status.Skipped -> Triple("Skipped: ${summary(state.prepared)}", MaterialTheme.colorScheme.onSurfaceVariant, true)
        Status.Waiting -> Triple(summary(state.prepared), MaterialTheme.colorScheme.onSurface, false)
        Status.Queued -> Triple("… ${summary(state.prepared)}", MaterialTheme.colorScheme.onSurfaceVariant, false)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = color, textDecoration = if (struck) TextDecoration.LineThrough else null)
        Text(state.call.signature(), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val actions = ((status as? Status.Finished)?.outcome as? Outcome.Done)?.actions.orEmpty()
        if (actions.isNotEmpty()) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { action -> FilledTonalButton(onClick = { onFollowUp(action) }) { Text(action.label) } }
            }
        }
    }
}

private fun summary(prepared: Prepared) = when (prepared) {
    is Prepared.Ready -> prepared.summary
    is Prepared.Invalid -> "Can't: ${prepared.reason}"
}

/** The confidence as a bar, and the bar it needed to run without asking (each tool has its own). */
@Composable
private fun Confidence(confidence: Double?, runAt: Double, decision: Decision, hasCalls: Boolean) {
    if (confidence == null) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Confidence", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(
                progress = { confidence.toFloat() },
                modifier = Modifier.weight(1f),
                color = if (decision == Decision.Run) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
            )
            Text(String.format(Locale.US, "%.2f", confidence), style = MaterialTheme.typography.labelSmall)
        }
        if (hasCalls) {
            Text(
                String.format(Locale.US, if (decision == Decision.Run) "Ran without asking (needs %.2f)" else "Asked first (runs without asking from %.2f)", runAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun InputBar(state: AssistantViewModel.UiState, ready: Boolean, onChange: (String) -> Unit, onSend: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.input,
                onValueChange = onChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask me to do something") },
                enabled = ready,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                maxLines = 3,
            )
            Box(Modifier.padding(start = 8.dp).size(48.dp), contentAlignment = Alignment.Center) {
                if (state.busy) LoadingIndicator(Modifier.size(40.dp))
                else FilledIconButton(onClick = onSend, enabled = ready && state.input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}
