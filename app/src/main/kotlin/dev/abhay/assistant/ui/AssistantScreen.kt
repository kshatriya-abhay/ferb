package dev.abhay.assistant.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.abhay.assistant.R
import dev.abhay.assistant.engine.AssistantEngine
import dev.abhay.assistant.engine.Decision
import dev.abhay.assistant.tools.FollowUp
import dev.abhay.assistant.tools.Outcome
import dev.abhay.assistant.tools.Prepared
import dev.abhay.assistant.tools.Tools
import dev.abhay.assistant.ui.AssistantViewModel.Status
import java.util.Locale

/**
 * The assistant, as a chat: requests on the right, what was done on the left, newest at the bottom
 * (the list stays there unless you scroll up through the history). Each result is one line with
 * the tool's icon, and its buttons (Turn off, Undo…); how sure Needle 3 was and why are behind
 * Details, in a bottom sheet. Settings are behind the top bar's icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(viewModel: AssistantViewModel, onOpenSettings: () -> Unit) {
    val engine by viewModel.engine.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val enabled by viewModel.enabledTools.collectAsStateWithLifecycle()
    var details by remember { mutableStateOf<AssistantViewModel.Exchange?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) { state.notice?.let { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Assistant") },
                subtitle = { Text(subtitle(engine)) },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Clear chat") },
                                onClick = { menu = false; viewModel.clearHistory() },
                                enabled = state.history.isNotEmpty(),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // The keyboard replaces the navigation-bar inset rather than adding to it.
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            when (val e = engine) {
                AssistantEngine.State.Loading -> Loading(Modifier.weight(1f))
                is AssistantEngine.State.Failed -> Failed(e.message, viewModel::retry, Modifier.weight(1f))
                is AssistantEngine.State.Ready ->
                    if (state.history.isEmpty() && state.pending == null) Welcome(enabled, onTry = viewModel::send, Modifier.weight(1f))
                    else History(state, viewModel, onDetails = { details = it }, Modifier.weight(1f))
            }
            InputBar(state, ready = engine is AssistantEngine.State.Ready, onChange = viewModel::onInputChange, onSend = { viewModel.send() })
        }
    }

    details?.let { DetailsSheet(it, onDismiss = { details = null }) }
}

private fun subtitle(engine: AssistantEngine.State) = when (engine) {
    AssistantEngine.State.Loading -> "Getting ready…"
    is AssistantEngine.State.Failed -> "Couldn't start"
    is AssistantEngine.State.Ready -> "On this phone · offline"
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Loading(modifier: Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ContainedLoadingIndicator(Modifier.size(80.dp)) }
}

@Composable
private fun Failed(message: String, onRetry: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Couldn't start", style = MaterialTheme.typography.titleLarge)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 12.dp))
        FilledTonalButton(onClick = onRetry) { Text("Try again") }
    }
}

/** Before the first request: what to say, as chips with the tools' icons (one per tool turned on). */
@Composable
private fun Welcome(enabled: List<String>, onTry: (String) -> Unit, modifier: Modifier) {
    val tools = enabled.mapNotNull(Tools::byName)
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(72.dp)) {
            Icon(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(4.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("How can I help?", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text("Everything stays on this phone", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tools.forEach { tool ->
                val example = tool.examples.first()
                SuggestionChip(
                    onClick = { onTry(example) },
                    label = { Text(example) },
                    icon = { Icon(painterResource(tool.icon), contentDescription = null, Modifier.size(18.dp)) },
                )
            }
        }
    }
}

/**
 * The conversation, laid out from the bottom (a reversed list), so the newest message is always
 * in view: a new one scrolls the list back down; scrolling up shows older ones.
 */
@Composable
private fun History(
    state: AssistantViewModel.UiState,
    viewModel: AssistantViewModel,
    onDetails: (AssistantViewModel.Exchange) -> Unit,
    modifier: Modifier,
) {
    val list = rememberLazyListState()
    // A request sent, or an answer arriving: back to the newest message.
    LaunchedEffect(state.pending, state.history.lastOrNull()?.id) { list.animateScrollToItem(0) }
    LazyColumn(
        modifier,
        state = list,
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom),
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
                    onDetails = { onDetails(exchange) },
                )
            }
        }
    }
}

@Composable
private fun RequestBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(24.dp, 24.dp, 6.dp, 24.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(text, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** The assistant's side of the chat: a bubble on the left. */
@Composable
private fun AssistantBubble(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(24.dp, 24.dp, 24.dp, 6.dp),
            modifier = Modifier.widthIn(max = 340.dp).animateContentSize(),
        ) { Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { content() } }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Thinking() {
    AssistantBubble { LoadingIndicator(Modifier.size(32.dp)) }
}

/**
 * What became of a request: each call as one line with its tool's icon and its buttons; "Shall I
 * do this?" when Needle 3 wasn't sure enough; a short apology when no tool fits. Details (the info
 * icon) opens the rest.
 */
@Composable
private fun ReplyBubble(
    exchange: AssistantViewModel.Exchange,
    onRun: () -> Unit,
    onSkip: () -> Unit,
    onFollowUp: (Int, FollowUp) -> Unit,
    onDetails: () -> Unit,
) {
    val reply = exchange.reply
    val asking = exchange.calls.any { it.status == Status.Waiting }
    AssistantBubble {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                reply.error != null -> Text("Something went wrong. Try again.", style = MaterialTheme.typography.bodyLarge)
                exchange.calls.isEmpty() -> Text("I can't do that yet.", style = MaterialTheme.typography.bodyLarge)
                asking -> Text("Shall I do this?", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            exchange.calls.forEachIndexed { i, call -> CallLine(call, onFollowUp = { action -> onFollowUp(i, action) }) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (asking) {
                    Button(onClick = onRun) { Text("Do it") }
                    TextButton(onClick = onSkip) { Text("Not now") }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDetails, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Info, contentDescription = "Details", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** One call: the tool's icon and what it does or did, then the result's buttons as chips. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CallLine(state: AssistantViewModel.CallState, onFollowUp: (FollowUp) -> Unit) {
    val tool = Tools.byName(state.call.name)
    val status = state.status
    val (text, failed) = when (status) {
        is Status.Finished -> when (val o = status.outcome) {
            is Outcome.Done -> o.message to false
            is Outcome.Failed -> o.reason to true
        }
        else -> summary(state.prepared) to (state.prepared is Prepared.Invalid)
    }
    val muted = status == Status.Skipped
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                shape = CircleShape,
                color = when {
                    failed -> MaterialTheme.colorScheme.errorContainer
                    status is Status.Finished -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                },
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when {
                        status == Status.Queued -> LoadingIndicator(Modifier.size(24.dp))
                        failed -> Icon(Icons.Default.Warning, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                        tool != null -> Icon(painterResource(tool.icon), contentDescription = null, Modifier.size(18.dp))
                        else -> Icon(Icons.Default.Check, contentDescription = null, Modifier.size(18.dp))
                    }
                }
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                textDecoration = if (muted) TextDecoration.LineThrough else null,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        val actions = ((status as? Status.Finished)?.outcome as? Outcome.Done)?.actions.orEmpty()
        if (actions.isNotEmpty()) {
            FlowRow(Modifier.padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { action -> AssistChip(onClick = { onFollowUp(action) }, label = { Text(action.label) }) }
            }
        }
    }
}

private fun summary(prepared: Prepared) = when (prepared) {
    is Prepared.Ready -> prepared.summary
    is Prepared.Invalid -> prepared.reason
}

/** Everything behind a reply: how sure Needle 3 was against the bar, its reasoning, the raw calls, timings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsSheet(exchange: AssistantViewModel.Exchange, onDismiss: () -> Unit) {
    val reply = exchange.reply
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("“${exchange.request}”", style = MaterialTheme.typography.titleMedium)
            reply.confidence?.let { confidence ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row {
                        Text("Confidence", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Text(String.format(Locale.US, "%.2f", confidence), style = MaterialTheme.typography.labelLarge)
                    }
                    LinearProgressIndicator(progress = { confidence.toFloat() }, Modifier.fillMaxWidth())
                    if (exchange.calls.isNotEmpty()) {
                        Text(
                            when {
                                exchange.alwaysConfirm -> "Asked first: the tool is set to always ask."
                                exchange.unmentioned -> "Asked first: the request doesn't mention what this tool does."
                                exchange.decision == Decision.Run -> String.format(Locale.US, "Ran without asking: needed %.2f.", exchange.runAt)
                                else -> String.format(Locale.US, "Asked first: runs without asking from %.2f.", exchange.runAt)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (reply.reasoning.isNotBlank()) {
                Text(reply.reasoning, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (exchange.calls.isNotEmpty() || reply.suppressed.isNotEmpty()) {
                HorizontalDivider()
                (exchange.calls.map { it.call.signature() } + reply.suppressed.map { "held back: ${it.signature()}" }).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            reply.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Text(
                String.format(Locale.US, "%d ms on this phone · prefill %.0f tok/s · decode %.0f tok/s", exchange.wallMs, reply.prefillTps, reply.decodeTps),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A rounded input with the send button inside it; a loading indicator while Needle 3 thinks. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun InputBar(state: AssistantViewModel.UiState, ready: Boolean, onChange: (String) -> Unit, onSend: () -> Unit) {
    TextField(
        value = state.input,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        placeholder = { Text("Ask me to do something") },
        enabled = ready,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSend() }),
        maxLines = 4,
        trailingIcon = {
            Box(Modifier.padding(end = 4.dp).width(48.dp), contentAlignment = Alignment.Center) {
                if (state.busy) LoadingIndicator(Modifier.size(36.dp))
                else FilledIconButton(onClick = onSend, enabled = ready && state.input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        },
    )
}
