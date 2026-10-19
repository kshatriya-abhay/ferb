package dev.abhay.assistant.ui

import android.Manifest
import androidx.compose.runtime.saveable.rememberSaveable
import android.provider.Settings
import android.net.Uri
import android.content.Intent
import android.app.Activity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.toShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import dev.abhay.assistant.tools.Flashlight
import dev.abhay.assistant.tools.GetDirections
import dev.abhay.assistant.tools.SendText
import dev.abhay.assistant.tools.SetAlarm
import dev.abhay.assistant.tools.ShowAgenda
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import dev.abhay.assistant.link.Answerer
import dev.abhay.assistant.link.LinkPolicy
import dev.abhay.assistant.link.LinkMode
import dev.abhay.assistant.link.LinkStatus
import dev.abhay.assistant.notes.NoteStore
import dev.abhay.assistant.onboarding.Tip
import dev.abhay.assistant.tools.Choice
import dev.abhay.assistant.tools.FollowUp
import dev.abhay.assistant.tools.Line
import dev.abhay.assistant.tools.Outcome
import dev.abhay.assistant.tools.Prepared
import dev.abhay.assistant.tools.Tools
import dev.abhay.assistant.ui.AssistantViewModel.Status
import java.util.Locale
import kotlinx.coroutines.delay
import dev.abhay.assistant.tools.LevelWatch
import androidx.compose.runtime.rememberUpdatedState

/**
 * The assistant, as a chat: requests on the right, what was done on the left, newest at the bottom
 * (the list stays there unless you scroll up through the history). Each result is one line with
 * the tool's icon, and its buttons (Turn off, Undo…); how sure Needle 3 was and why open from a
 * tap on the reply, in a bottom sheet. Settings are behind the top bar's icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(viewModel: AssistantViewModel, onOpenSettings: () -> Unit, onOpenLink: () -> Unit = onOpenSettings, onOpenNotes: () -> Unit = {},
                    /** Times Ferb was opened as the phone's assistant: each one focuses the box, keyboard up. */
                    assists: Int = 0) {
    val engine by viewModel.engine.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val enabled by viewModel.enabledTools.collectAsStateWithLifecycle()
    val linkMode by viewModel.linkMode.collectAsStateWithLifecycle()
    val answerer by viewModel.answerer.collectAsStateWithLifecycle()
    var details by remember { mutableStateOf<AssistantViewModel.Exchange?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) { state.notice?.let { snackbar.showSnackbar(it) } }
    val onboarding by viewModel.onboarding.state.collectAsStateWithLifecycle()
    val offered = answerer.takeIf { linkMode == LinkMode.Option }
    // "Use Jev to select the tool" is on, and Ferb Link has Jev on: shown under the box.
    val jevOn by viewModel.jev.collectAsStateWithLifecycle()
    val linkStatus = rememberLinkStatus(viewModel.link)
    val usingJev = jevOn && linkMode != LinkMode.Off && (linkStatus as? LinkStatus.Ready)?.jev == true
    // Voice: the computer hears it when it's picked in the chat box and takes audio (any language);
    // otherwise Whistle, on the phone (English).
    val computerHears = rememberVoiceAvailable(viewModel.link, active = offered == Answerer.Link)
    // The one tip to show, if any, among those whose thing is on screen.
    val startScreen = engine is AssistantEngine.State.Ready && state.history.isEmpty() && state.pending == null
    val places = chatTips(state.history, startScreen, answererShown = offered != null,
        micShown = state.voice == null, linkOff = linkMode == LinkMode.Off)
    val tip = Tip.next(places.map { it.tip }, onboarding.seen)?.let { t -> places.first { it.tip == t } }
    val seen = viewModel.onboarding::seen
    val context = LocalContext.current
    // A reply's Allow: the permission, then the request again if it was given.
    var allowing by remember { mutableStateOf<Long?>(null) }
    var refused by rememberSaveable { mutableStateOf(setOf<String>()) }
    val askAllow = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val activity = context as? Activity
        refused = refused + result.filter { (permission, granted) -> !granted && activity?.shouldShowRequestPermissionRationale(permission) == false }.keys
        val request = state.history.firstOrNull { it.id == allowing }?.request
        if (request != null && result.values.any { it }) viewModel.send(request)
        allowing = null
    }
    // A reply's "Pick a notes folder": the system's folder picker, then the request again.
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val request = state.history.firstOrNull { it.id == allowing }?.request
        if (tree != null) {
            viewModel.pickNotesFolder(tree)
            if (request != null) viewModel.send(request)
        }
        allowing = null
    }
    // Turning on a tool from a reply: its permissions first, then the request again (with or without them).
    var turningOn by remember { mutableStateOf<Pair<Long, String>?>(null) }
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        turningOn?.let { (id, tool) -> viewModel.turnOn(id, tool) }
        turningOn = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                subtitle = { Text(subtitle(engine, linkMode, answerer)) },
                actions = {
                    // Notes, once there's a folder for them.
                    if (viewModel.notes.ready) IconButton(onClick = onOpenNotes) { Icon(painterResource(R.drawable.ic_note), contentDescription = "Notes") }
                    IconButton(onClick = { seen(Tip.MoreTools); onOpenSettings() }) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
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
                    if (startScreen) Welcome(enabled, onTry = { seen(Tip.TryOne); viewModel.send(it) }, Modifier.weight(1f),
                        tip = tip?.tip?.takeIf { it == Tip.TryOne }, onTipSeen = seen)
                    else History(state, viewModel, onDetails = { seen(Tip.Details); details = it }, Modifier.weight(1f), tip = tip, onTipSeen = seen,
                        onTurnOn = { id, tool ->
                            val missing = Tools.byName(tool)?.permissions.orEmpty().filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                            if (missing.isEmpty()) viewModel.turnOn(id, tool)
                            else {
                                turningOn = id to tool
                                askPermissions.launch(missing.toTypedArray())
                            }
                        },
                        onAllow = { id, needs ->
                            if (NoteStore.NEEDS_FOLDER in needs) {
                                allowing = id
                                pickFolder.launch(null)
                                return@History
                            }
                            // Android asks no more once a permission was refused twice: then its page in Android's settings.
                            if (needs.any { it in refused }) {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                            } else {
                                allowing = id
                                askAllow.launch(needs.toTypedArray())
                            }
                        })
            }
            // A tip about the box, the toggle or the mic, above them.
            tip?.takeIf { it.exchange == null && it.tip != Tip.TryOne }?.let {
                TipCard(it.tip, onDismiss = { seen(it.tip) }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    action = ("Set up" to onOpenLink).takeIf { _ -> it.tip == Tip.SuggestLink })
            }
            // With the computer picked, keep Ferb Link's process up, so a request doesn't wait for it to start.
            DisposableEffect(offered == Answerer.Link) {
                val holding = offered == Answerer.Link
                if (holding) viewModel.link.hold()
                onDispose { if (holding) viewModel.link.release() }
            }
            val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) viewModel.startRecording(onPhone = !computerHears)
                else {
                    // Refused: typing still works. Once Android won't ask again, the mic opens Ferb's settings page.
                    if ((context as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false) refused = refused + Manifest.permission.RECORD_AUDIO
                    viewModel.notify("Ferb needs the microphone to hear you. You can still type.")
                }
            }
            InputBar(state, ready = engine is AssistantEngine.State.Ready, focus = assists, onChange = viewModel::onInputChange, onSend = { viewModel.send() },
                // With the computer picked, a tap on the box wakes its model while the request is typed.
                onFocus = { if (offered == Answerer.Link) viewModel.wakeComputer() },
                answerer = offered, usingJev = usingJev, onAnswerer = { seen(Tip.Answerer); viewModel.setAnswerer(it) },
                onMic = {
                    seen(Tip.Mic)
                    when {
                        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> viewModel.startRecording(onPhone = !computerHears)
                        Manifest.permission.RECORD_AUDIO in refused ->
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                        else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onStop = viewModel::stopRecording, onDiscard = viewModel::discardRecording)
        }
    }

    details?.let { DetailsSheet(it, onDismiss = { details = null }) }
}

private fun subtitle(engine: AssistantEngine.State, link: LinkMode, answerer: Answerer) = when (engine) {
    AssistantEngine.State.Loading -> "Getting ready…"
    is AssistantEngine.State.Failed -> "Couldn't start"
    is AssistantEngine.State.Ready -> when {
        link == LinkMode.Fallback -> "On this phone · your computer when unsure"
        link == LinkMode.Option && answerer == Answerer.Link -> "Your computer, through Ferb Link"
        else -> "On this phone · offline"
    }
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

/** A few things to ask, a mix from the tools turned on: an alarm, the day, a message, a route… */
private val WELCOME = listOf(SetAlarm, ShowAgenda, SendText, GetDirections, Flashlight)

/** Before the first request: what to say, as chips with the tools' icons. Scrolls when the screen is short. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Welcome(enabled: List<String>, onTry: (String) -> Unit, modifier: Modifier, tip: Tip? = null, onTipSeen: (Tip) -> Unit = {}) {
    val tools = (WELCOME + Tools.all).distinct().filter { it.name in enabled }.take(5)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(shape = MaterialShapes.Cookie9Sided.toShape(), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(88.dp)) {
                Icon(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(8.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text("How can I help?", style = MaterialTheme.typography.headlineMedium)
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
            tip?.let {
                Spacer(Modifier.height(16.dp))
                TipCard(it, onDismiss = { onTipSeen(it) })
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
    /** The tip to show, when it belongs under one of the replies. */
    tip: TipPlace? = null,
    onTurnOn: (Long, String) -> Unit = { _, _ -> },
    onAllow: (Long, List<String>) -> Unit = { _, _ -> },
    onTipSeen: (Tip) -> Unit = {},
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
            item(key = "thinking") { Thinking(state.asking, still = state.transcribing) }
            item(key = "pending") { RequestBubble(request) }
        }
        items(state.history.asReversed(), key = { it.id }) { exchange ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RequestBubble(exchange.request, exchange.heard)
                ReplyBubble(
                    exchange,
                    onRun = { onTipSeen(Tip.AskFirst); viewModel.run(exchange.id) },
                    onSkip = { onTipSeen(Tip.AskFirst); viewModel.skip(exchange.id) },
                    onChoose = { index, choice -> onTipSeen(Tip.AskFirst); viewModel.choose(exchange.id, index, choice) },
                    onFollowUp = { index, action -> onTipSeen(Tip.Undo); viewModel.followUp(exchange.id, index, action) },
                    onAdjust = { index, value -> viewModel.adjust(exchange.id, index, value) },
                    onFollow = { index, value -> viewModel.follow(exchange.id, index, value) },
                    onDetails = { onDetails(exchange) },
                    onTurnOn = { tool -> onTurnOn(exchange.id, tool) },
                    onAllow = { needs -> onAllow(exchange.id, needs) },
                )
                tip?.takeIf { it.exchange == exchange.id }?.let { TipCard(it.tip, onDismiss = { onTipSeen(it.tip) }) }
            }
        }
    }
}

/** A request on the right; for a spoken one, [heard] (what was said) under its English command. */
@Composable
private fun RequestBubble(text: String, heard: String? = null) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(24.dp, 24.dp, 6.dp, 24.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(text, style = MaterialTheme.typography.bodyLarge)
                heard?.let {
                    Text("Heard: \u201c$it\u201d", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f))
                }
            }
        }
    }
}

/** The assistant's side of the chat: a bubble on the left; tapping it, when [onClick] is set, opens its details. */
@Composable
private fun AssistantBubble(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(24.dp, 24.dp, 24.dp, 6.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = shape,
            modifier = Modifier.widthIn(max = 340.dp).animateContentSize()
                .then(if (onClick != null) Modifier.clip(shape).clickable(onClickLabel = "Show details", onClick = onClick) else Modifier),
        ) { Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { content() } }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Thinking(asking: String?, still: Boolean = false) {
    AssistantBubble {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Still while Whistle transcribes: an animation competes with the engine for its cores.
            if (!still) LoadingIndicator(Modifier.size(32.dp))
            asking?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/**
 * What became of a request: each call as one line with its tool's icon and its buttons (or a
 * slider, e.g. for the volume); "Shall I do this?" when Needle 3 wasn't sure enough; a short
 * apology when no tool fits. Tapping the bubble opens the rest (Details).
 */
@Composable
private fun ReplyBubble(
    exchange: AssistantViewModel.Exchange,
    onRun: () -> Unit,
    onSkip: () -> Unit,
    onChoose: (Int, Choice) -> Unit,
    onFollowUp: (Int, FollowUp) -> Unit,
    onAdjust: (Int, Float) -> Unit,
    /** A slider's value changed outside Ferb: show it, changing nothing. */
    onFollow: (Int, Float) -> Unit = { _, _ -> },
    onDetails: () -> Unit,
    onTurnOn: (String) -> Unit = {},
    onAllow: (List<String>) -> Unit = {},
) {
    val reply = exchange.reply
    val asking = exchange.calls.any { it.status == Status.Waiting }
    val waiting = exchange.calls.filter { it.status == Status.Waiting }.mapNotNull { it.prepared as? Prepared.Ready }
    // A call with choices (contacts) is answered by picking one; its tool's words on the buttons when it's alone.
    val choosing = waiting.any { it.choices.isNotEmpty() }
    val only = exchange.calls.singleOrNull()?.prepared as? Prepared.Ready
    // No tool on could do it, but one that's off could: offer it.
    val offered = exchange.turnOn.firstOrNull()?.let(Tools::byName)
    AssistantBubble(onClick = onDetails) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                reply.error != null -> Text("Something went wrong. Try again.", style = MaterialTheme.typography.bodyLarge)
                offered != null -> Text("${offered.title} is off.", style = MaterialTheme.typography.bodyLarge)
                exchange.calls.isEmpty() -> Text("I can't do that yet.", style = MaterialTheme.typography.bodyLarge)
                asking && !choosing -> Text("Shall I do this?", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            exchange.calls.forEachIndexed { i, call ->
                CallLine(call, onChoose = { choice -> onChoose(i, choice) }, onFollowUp = { action -> onFollowUp(i, action) }, onAdjust = { value -> onAdjust(i, value) }, onFollow = { value -> onFollow(i, value) }, onAllow = onAllow)
            }
            source(exchange)?.let { (icon, text) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(painterResource(icon), contentDescription = null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            offered?.let { tool -> Button(onClick = { onTurnOn(tool.name) }) { Text("Turn on") } }
            if (asking) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!choosing) Button(onClick = onRun) { Text(only?.confirm ?: "Do it") }
                    TextButton(onClick = onSkip) { Text(only?.cancel ?: "Not now") }
                }
            }
        }
    }
}

/**
 * Where an answer came from, when Ferb Link was involved, as an icon and a few words: the computer
 * and how long it took, or this phone and why the computer didn't answer. The model and the
 * reasons at length are in the details. Nothing for Needle 3's usual answers.
 */
private fun source(exchange: AssistantViewModel.Exchange): Pair<Int, String>? {
    val reply = exchange.reply
    val base = when {
        reply.fromLaptop -> R.drawable.ic_computer to (reply.ms?.let { String.format(Locale.US, "%.1f s", it / 1000.0) } ?: "Computer")
        exchange.laptopError != null -> R.drawable.ic_phone to "On phone \u00b7 ${exchange.laptopError.replaceFirstChar { it.lowercase() }}"
        else -> null
    }
    // Why Jev's pick wasn't used ("Jev wasn't sure"), after the rest.
    val note = exchange.jevNote ?: return base
    return base?.let { it.first to "${it.second} \u00b7 $note" } ?: ((if (reply.fromLaptop) R.drawable.ic_computer else R.drawable.ic_phone) to note)
}

/** The Android permissions a call couldn't do without, if that's why it can't run or didn't work. */
internal fun AssistantViewModel.CallState.needs(): List<String> =
    ((status as? Status.Finished)?.outcome as? Outcome.Failed)?.needs?.takeIf { it.isNotEmpty() }
        ?: when (val p = prepared) {
            is Prepared.Invalid -> p.needs
            is Prepared.Ready -> p.needs
        }

/** One call: the tool's icon and what it does or did, then the result's buttons as chips. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CallLine(state: AssistantViewModel.CallState, onChoose: (Choice) -> Unit, onFollowUp: (FollowUp) -> Unit, onAdjust: (Float) -> Unit, onFollow: (Float) -> Unit = {}, onAllow: (List<String>) -> Unit = {}) {
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
        // Couldn't for want of a permission (contacts, calendar): ask for it here, then try again.
        state.needs().takeIf { it.isNotEmpty() }?.let { needs ->
            FilledTonalButton(onClick = { onAllow(needs) }, Modifier.padding(start = 48.dp)) {
                Text(if (NoteStore.NEEDS_FOLDER in needs) "Pick a notes folder" else "Allow")
            }
        }
        val ready = state.prepared as? Prepared.Ready
        if (ready != null && status !is Status.Finished) {
            ready.detail?.let { detail ->
                Text(detail, Modifier.padding(start = 48.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (ready != null && status == Status.Waiting && ready.choices.isNotEmpty()) {
            Column(Modifier.padding(start = 48.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ready.choices.forEach { choice ->
                    FilledTonalButton(onClick = { onChoose(choice) }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(choice.label, style = MaterialTheme.typography.labelLarge)
                            choice.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                ready.other?.let { other -> TextButton(onClick = { onChoose(other) }) { Text(other.label) } }
            }
        }
        val done = (status as? Status.Finished)?.outcome as? Outcome.Done
        done?.level?.let { level ->
            val context = LocalContext.current
            var dragging by remember { mutableStateOf(false) }
            val shown by rememberUpdatedState(level.value)
            val follow by rememberUpdatedState(onFollow)
            // While on screen, a value changed outside Ferb (the volume keys) moves the slider,
            // except while the user drags it; listening stops when the reply leaves the screen.
            level.watch?.let { watch ->
                DisposableEffect(watch) {
                    val stop = watch.start(context) { now ->
                        LevelWatch.follow(shown, now, dragging)?.let(follow)
                    }
                    onDispose { stop() }
                }
            }
            Slider(value = level.value, onValueChange = { dragging = true; onAdjust(it) },
                onValueChangeFinished = { dragging = false }, modifier = Modifier.padding(start = 48.dp))
        }
        val lines = done?.lines.orEmpty()
        if (lines.isNotEmpty()) ResultLines(lines, onTick = onFollowUp)
        val actions = done?.actions.orEmpty()
        if (actions.isNotEmpty()) {
            FlowRow(Modifier.padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { action -> AssistChip(onClick = { onFollowUp(action) }, label = { Text(action.label) }) }
            }
        }
    }
}

/** A result's list, e.g. a day's events: the lead (a time) in a column of its own; a line with something to open is tappable. */
@Composable
private fun ResultLines(lines: List<Line>, onTick: (FollowUp) -> Unit = {}) {
    val context = LocalContext.current
    Column(Modifier.padding(start = 40.dp)) {
        lines.forEach { line ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = line.open != null || line.tick != null) {
                        line.tick?.let(onTick) ?: line.open?.let { Tools.start(context, it(), "") }
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A list's item: a box to tick it (in the note), else the lead (e.g. a time).
                if (line.tick != null) Checkbox(checked = false, onCheckedChange = { onTick(line.tick) }, Modifier.size(24.dp))
                else Text(line.lead, Modifier.width(92.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(line.text, style = MaterialTheme.typography.bodyMedium)
                    line.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
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
            exchange.heard?.let {
                Text("Spoken. Heard: \u201c$it\u201d", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // The path the request took, as it ran, with each step's pick or confidence.
            if (exchange.path.isNotEmpty()) {
                Text(Step.chain(exchange.path), style = MaterialTheme.typography.bodyMedium)
            }
            // A rewritten request: what Ferb understood, and each command's own steps when there are several.
            if (exchange.heard == null && exchange.commands.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Understood as", style = MaterialTheme.typography.labelLarge)
                    exchange.commands.forEach { command ->
                        Text(if (exchange.commands.size == 1) "\u201c${command.text}\u201d" else "\u201c${command.text}\u201d: ${Step.chain(command.steps)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            reply.confidence?.let { confidence ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row {
                        Text(if (reply.fromLaptop) "Your computer's first-token probability" else "Confidence",
                            style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Text(String.format(Locale.US, "%.2f", confidence), style = MaterialTheme.typography.labelLarge)
                    }
                    LinearProgressIndicator(progress = { confidence.toFloat() }, Modifier.fillMaxWidth())
                    if (exchange.calls.isNotEmpty()) {
                        Text(
                            when {
                                exchange.alwaysConfirm -> "Asked first: the tool is set to always ask."
                                exchange.unmentioned -> "Asked first: the request doesn't mention what this tool does."
                                exchange.ungrounded -> "Asked first: the request doesn't say these numbers."
                                reply.fromLaptop -> "Ran without asking: your computer's answer passed the usual checks."
                                exchange.decision == Decision.Run -> String.format(Locale.US, "Ran without asking: needed %.2f.", exchange.runAt)
                                else -> String.format(Locale.US, "Asked first: runs without asking from %.2f.", exchange.runAt)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val titles = exchange.routed.mapNotNull { Tools.byName(it)?.title }
            Text(
                when {
                    titles.isNotEmpty() -> "Checked: ${titles.joinToString()}"
                    else -> "No tool's words in this request: Needle 3 chose from all tools"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            // With Ferb Link: Needle 3's own answer when the computer's replaced it, or why the computer didn't answer.
            exchange.needleReply?.let { needle ->
                HorizontalDivider()
                Text(String.format(Locale.US, "Needle 3 said (confidence %.2f):", needle.confidence ?: 0.0), style = MaterialTheme.typography.labelLarge)
                Text(needle.calls.joinToString("\n") { it.signature() }.ifEmpty { "no tool" },
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            (LinkPolicy.jevLine(exchange.jev) ?: exchange.jevNoteDetail)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            (exchange.laptopErrorDetail ?: exchange.laptopError)?.let {
                Text("Your computer didn't answer: ${it.replaceFirstChar { c -> c.lowercase() }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                if (reply.fromLaptop) String.format(Locale.US, "%d ms in all · %s took %d ms on your computer", exchange.wallMs, reply.model ?: "the model", reply.ms ?: 0L)
                else String.format(Locale.US, "%d ms on this phone · prefill %.0f tok/s · decode %.0f tok/s", exchange.wallMs, reply.prefillTps, reply.decodeTps),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The icon for who answers: this phone (Needle 3) or the computer (Ferb Link). */
private val Answerer.icon get() = if (this == Answerer.Needle) R.drawable.ic_phone else R.drawable.ic_computer

/** The chat box's choice of who answers, as connected toggle buttons: Phone (Needle 3) or Computer (Ferb Link). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AnswererToggle(chosen: Answerer, usingJev: Boolean, onChoose: (Answerer) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        Answerer.entries.forEachIndexed { i, answerer ->
            ToggleButton(
                checked = answerer == chosen,
                onCheckedChange = { onChoose(answerer) },
                shapes = if (i == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                modifier = Modifier.semantics { role = Role.RadioButton },
            ) {
                Icon(painterResource(answerer.icon), contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(answerer.label(usingJev))
            }
        }
    }
}

/**
 * A rounded input with the mic and the send button inside it (a loading indicator while Needle 3
 * thinks) and, when Ferb Link is offered as an option, the choice of who answers under it. A
 * recording takes the box's place.
 */
@Composable
private fun InputBar(
    state: AssistantViewModel.UiState,
    ready: Boolean,
    /** Bumped to focus the box and bring the keyboard up (Ferb opened as the assistant). */
    focus: Int = 0,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    onFocus: () -> Unit = {},
    /** Who answers, when Ferb Link is offered as an option (Settings → Ferb Link); null: no choice to show. */
    answerer: Answerer? = null,
    /** Whether Jev selects the tool (Settings → Ferb Link), shown next to the choice. */
    usingJev: Boolean = false,
    onAnswerer: (Answerer) -> Unit = {},
    onMic: () -> Unit = {},
    onStop: () -> Unit = {},
    onDiscard: () -> Unit = {},
) {
    Column(Modifier.padding(bottom = 12.dp)) {
        when (val clip = state.voice) {
            null -> InputField(state, ready, onChange, onSend, onMic, onFocus, focus)
            else -> VoiceField(clip, onStop, onDiscard, onSend)
        }
        // Who answers, in a row of its own under the box, so the box keeps its width for the request.
        // Always enabled, so the choice stays readable: a request on its way has read it already,
        // and picking Phone drops a recording.
        val jevLine = Answerer.usingJevLine(usingJev, answerer)
        if (answerer != null || jevLine) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                answerer?.let { AnswererToggle(it, usingJev, onChoose = onAnswerer) }
                Spacer(Modifier.weight(1f))
                if (jevLine) UsingJev()
            }
        }
    }
}

/** "Using Jev": requests go to Jev first, which picks the tool (Settings → Ferb Link). */
@Composable
private fun UsingJev() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Icon(painterResource(R.drawable.ic_cloud), contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(6.dp))
        Text("Using Jev", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The shape and colour of the chat box, shared by the text field and a recording in its place. */
private val BoxShape = RoundedCornerShape(28.dp)

/**
 * A spoken request in the box's place: while recording, the time so far (30 s at most, for Ferb
 * Link and Whistle alike) and Stop; once stopped, its length, ✕ to drop it, and Send.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VoiceField(voice: AssistantViewModel.Voice, onStop: () -> Unit, onDiscard: () -> Unit, onSend: () -> Unit) {
    Surface(
        shape = BoxShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp).heightIn(min = 56.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            when (voice) {
                is AssistantViewModel.Voice.Recording -> {
                    val now by produceState(SystemClock.elapsedRealtime()) {
                        while (true) {
                            delay(250)
                            value = SystemClock.elapsedRealtime()
                        }
                    }
                    Icon(painterResource(R.drawable.ic_mic), contentDescription = "Recording", tint = MaterialTheme.colorScheme.error)
                    Text(elapsed(now - voice.startedAt), Modifier.padding(start = 12.dp).weight(1f),
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    FilledIconButton(onClick = onStop, Modifier.padding(end = 4.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Icon(painterResource(R.drawable.ic_stop), contentDescription = "Stop recording")
                    }
                }
                is AssistantViewModel.Voice.Recorded -> {
                    Icon(painterResource(R.drawable.ic_mic), contentDescription = "Recorded", tint = MaterialTheme.colorScheme.primary)
                    Text(elapsed(voice.ms), Modifier.padding(start = 12.dp).weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = onDiscard) { Icon(Icons.Default.Close, contentDescription = "Discard the recording") }
                    FilledIconButton(onClick = onSend, Modifier.padding(end = 4.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

/** "0:04". */
private fun elapsed(ms: Long) = String.format(Locale.US, "%d:%02d", ms / 60_000, ms / 1000 % 60)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun InputField(
    state: AssistantViewModel.UiState, ready: Boolean,
    onChange: (String) -> Unit, onSend: () -> Unit, onMic: () -> Unit, onFocus: () -> Unit,
    focus: Int = 0,
) {
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Opened as the assistant: the box takes focus once the model is ready, with the keyboard up.
    LaunchedEffect(focus, ready) {
        if (focus > 0 && ready) {
            requester.requestFocus()
            keyboard?.show()
        }
    }
    TextField(
        value = state.input,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocus() },
        placeholder = { Text("Ask me to do something") },
        enabled = ready,
        shape = BoxShape,
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
        // Both, always: the mic as the field's trailing icon (Material 3's voice input icon), and
        // Send, enabled once there's text (ferb-manager, for the user, 2026-10-19).
        trailingIcon = {
            Row(Modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onMic, enabled = ready && !state.busy) {
                    Icon(painterResource(R.drawable.ic_mic), contentDescription = "Speak")
                }
                Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                    if (state.busy && !state.transcribing) LoadingIndicator(Modifier.size(36.dp))
                    else FilledIconButton(onClick = onSend, enabled = ready && !state.busy && state.input.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        },
    )
}
