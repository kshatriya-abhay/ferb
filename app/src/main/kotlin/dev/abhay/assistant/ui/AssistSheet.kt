package dev.abhay.assistant.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.abhay.assistant.R
import dev.abhay.assistant.engine.AssistantEngine
import dev.abhay.assistant.pipeline.AudioStep
import dev.abhay.assistant.voice.Mic

/** What the sheet shows of the conversation: the requests made since it opened, the newest last. */
internal fun sheetHistory(state: AssistantViewModel.UiState, since: Long): List<AssistantViewModel.Exchange> =
    state.history.filter { it.id >= since }.takeLast(SHEET_EXCHANGES)

/** At most this many exchanges in the sheet; the rest are in the full chat. */
internal const val SHEET_EXCHANGES = 2

internal const val OPEN_CHAT = "Open the chat"

/**
 * Ferb as the phone's assistant (holding the power button, or the corner swipe), over the app in
 * front: an M3 Expressive bottom sheet that starts listening at once (with audio processing off,
 * or the microphone refused, the box takes focus with the keyboard up), shows what's heard as it
 * comes, then the reply and any "Shall I do this?", with ✕ and ✓ as in the chat. Tapping the
 * conversation, its open button, or dragging the sheet up opens the full chat, which carries the
 * same conversation ([dev.abhay.assistant.AssistantApp.chat]). Swiping down, a tap outside or back
 * (with predictive back) dismisses it, dropping a recording. [opened] counts the assist invocations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistSheet(viewModel: AssistantViewModel, opened: Int, onOpenChat: () -> Unit, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val engine by viewModel.engine.collectAsStateWithLifecycle()
    val pipeline by viewModel.pipeline.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The conversation since the sheet opened (the first exchange id it can show).
    var since by rememberSaveable { mutableStateOf(Long.MAX_VALUE) }
    var focus by remember { mutableIntStateOf(0) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startRecording() else focus++
    }
    val ready = engine is AssistantEngine.State.Ready
    // Each invocation: listen at once, or focus the box.
    LaunchedEffect(opened, ready) {
        if (opened == 0) return@LaunchedEffect
        if (since == Long.MAX_VALUE) since = (state.history.lastOrNull()?.id ?: -1) + 1
        if (!ready || state.busy || state.voice != null) return@LaunchedEffect
        when {
            !Mic.shows(pipeline.audio) -> focus++
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> viewModel.startRecording()
            else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    // Dragged up from half height: the full chat. A recording under way is sent first.
    var wasPartial by remember { mutableStateOf(false) }
    fun openChat() {
        if (state.voice is AssistantViewModel.Voice.Recording) viewModel.finishRecording()
        onOpenChat()
    }
    LaunchedEffect(sheet.currentValue) {
        when (sheet.currentValue) {
            SheetValue.PartiallyExpanded -> wasPartial = true
            SheetValue.Expanded -> if (wasPartial) openChat()
            else -> {}
        }
    }
    ModalBottomSheet(onDismissRequest = { viewModel.discardRecording(); onDismiss() }, sheetState = sheet) {
        // Taller than half the screen, so the sheet opens half way and can be dragged up.
        val minHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
        Column(Modifier.fillMaxWidth().heightIn(min = minHeight).padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ferb", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = ::openChat) { Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = OPEN_CHAT) }
            }
            // The conversation: a tap opens the full chat (buttons on a reply still do their own thing).
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .clickable(onClickLabel = OPEN_CHAT, onClick = ::openChat)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
            ) {
                val shown = sheetHistory(state, since)
                if (shown.isEmpty() && state.pending == null) {
                    Text(if (Mic.shows(pipeline.audio)) "Listening for what you'd like done…" else "What would you like done?",
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                shown.forEach { exchange ->
                    RequestBubble(exchange.request, exchange.heard, exchange.understood)
                    ReplyBubble(
                        exchange,
                        onRun = { viewModel.run(exchange.id) },
                        onSkip = { viewModel.skip(exchange.id) },
                        onChoose = { index, choice -> viewModel.choose(exchange.id, index, choice) },
                        onFollowUp = { index, action -> viewModel.followUp(exchange.id, index, action) },
                        onAdjust = { index, value -> viewModel.adjust(exchange.id, index, value) },
                        onFollow = { index, value -> viewModel.follow(exchange.id, index, value) },
                        // Details, turning tools on and permissions are the full chat's.
                        onDetails = ::openChat,
                        onTurnOn = { openChat() },
                        onAllow = { openChat() },
                    )
                }
                state.pending?.let { request ->
                    RequestBubble(request)
                    Thinking(state.asking, still = state.transcribing)
                }
            }
            Spacer(Modifier.padding(4.dp))
            val clip = state.voice
            if (clip != null) VoiceField(clip, viewModel::finishRecording, viewModel::discardRecording, onSend = { viewModel.send() })
            else InputField(state, ready, viewModel::onInputChange, onSend = { viewModel.send() },
                onMic = {
                    when {
                        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> viewModel.startRecording()
                        else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }.takeIf { Mic.shows(pipeline.audio) },
                onFocus = { if (viewModel.usesComputer) viewModel.wakeComputer() }, focus = focus)
        }
    }
}
