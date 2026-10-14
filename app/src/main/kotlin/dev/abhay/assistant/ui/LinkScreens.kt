@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.abhay.assistant.ui

import androidx.activity.compose.BackHandler
import dev.abhay.assistant.link.setupSteps
import dev.abhay.assistant.link.SetupStep
import dev.abhay.assistant.link.SetupAction
import androidx.compose.material.icons.filled.CheckCircle
import android.net.Uri
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.nestedScroll
import dev.abhay.assistant.link.Reachability
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.abhay.assistant.R
import dev.abhay.assistant.link.FerbLink
import dev.abhay.assistant.link.LinkMode
import dev.abhay.assistant.link.LinkStatus
import dev.abhay.assistant.link.describe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Each way to use Ferb Link, in words: its name and what it does. */
private fun LinkMode.title() = when (this) {
    LinkMode.Off -> "Off"
    LinkMode.Fallback -> "When Needle 3 is unsure"
    LinkMode.Option -> "Choose in the chat"
}

private fun LinkMode.description() = when (this) {
    LinkMode.Off -> "Phone only"
    LinkMode.Fallback -> "Phone first, your computer when unsure"
    LinkMode.Option -> "Pick Phone or Computer under the chat box"
}

/** Ferb Link's status now, refreshed whenever the screen comes back (e.g. from Ferb Link or installing it). */
@Composable
internal fun rememberLinkStatus(link: FerbLink): LinkStatus {
    var status by remember { mutableStateOf(link.status()) }
    LifecycleResumeEffect(Unit) {
        status = link.status()
        onPauseOrDispose { }
    }
    return status
}

/**
 * Whether to offer voice: Ferb Link is ready and the computer's model takes audio. Status keeps
 * the last value Ferb Link saw, false until it has checked once (e.g. after its server restarted),
 * so then a check (2 s at most, without waking the model) asks the computer. Again on each resume.
 */
@Composable
internal fun rememberVoiceAvailable(link: FerbLink, active: Boolean): Boolean {
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    val available by produceState(false, active, resumed) {
        value = active && withContext(Dispatchers.IO) {
            val status = link.status()
            status is LinkStatus.Ready && (status.audio || link.check()?.audio == true)
        }
    }
    return available
}

/**
 * The settings home's Ferb Link row: the way it's used, or, until it's set up, an invitation to
 * set it up (the page then has the checklist).
 */
@Composable
fun LinkRow(settings: SettingsViewModel, onOpen: () -> Unit, shapes: ListItemShapes) {
    val mode by settings.linkMode.collectAsStateWithLifecycle()
    val status = rememberLinkStatus(settings.link)
    val installed = status != LinkStatus.NotInstalled
    SegmentedListItem(
        onClick = onOpen,
        shapes = shapes,
        leadingContent = { IconBadge(R.drawable.ic_link, active = installed && mode != LinkMode.Off) },
        supportingContent = {
            Text(
                when {
                    !installed -> "A bigger model on your computer. Tap to set up"
                    status == LinkStatus.NotSetUp -> "Not connected yet. Tap to set up"
                    mode == LinkMode.Off -> "Off. Use a bigger model on your computer"
                    status is LinkStatus.Ready -> mode.title()
                    else -> "${mode.title()} · ${describe(status)}"
                },
            )
        },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
    ) { Text("Ferb Link", color = if (installed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** What Ferb Link is, above its checklist while it isn't working yet. */
@Composable
private fun AboutLink() {
    Text("Ferb Link lets Ferb use a bigger model on a computer of your own, for more requests, Hinglish and voice. Ferb stays offline: Ferb Link holds the internet access.",
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Where a step's button goes: Ferb Link's releases, its quick start (in the browser; Ferb itself stays offline), or Ferb Link. */
private const val RELEASES = "https://github.com/kshatriya-abhay/ferb-link/releases/latest"
private const val QUICK_START = "https://github.com/kshatriya-abhay/ferb-link#quick-start"

/** The steps to a working Ferb Link ([setupSteps]), each ticked when done, with a button where Ferb can help. */
@Composable
private fun SetupChecklist(steps: List<SetupStep>, onOpenFerbLink: (() -> Unit)?) {
    val context = LocalContext.current
    fun browse(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    SectionTitle("Set up")
    Group(steps.mapIndexed { i, step ->
        { shapes ->
            SegmentedListItem(
                shapes = shapes,
                leadingContent = {
                    if (step.done) Icon(Icons.Default.CheckCircle, contentDescription = "Done", tint = MaterialTheme.colorScheme.primary)
                    else Text("${i + 1}", Modifier.size(24.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                supportingContent = { Text(step.detail) },
                trailingContent = step.action?.let { action ->
                    {
                        when (action) {
                            SetupAction.Download -> TextButton(onClick = { browse(RELEASES) }) { Text("Get it") }
                            SetupAction.Guide -> TextButton(onClick = { browse(QUICK_START) }) { Text("How") }
                            SetupAction.OpenFerbLink -> onOpenFerbLink?.let { TextButton(onClick = it) { Text("Open") } }
                        }
                    }
                },
            ) { Text(step.title, color = if (step.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface) }
        }
    })
}

/**
 * Ferb Link's page: a card with whether the computer answers (checked when the page opens, again
 * with refresh) and a way into Ferb Link, then how Ferb uses it. Saved at once.
 */
@Composable
fun LinkScreen(settings: SettingsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val mode by settings.linkMode.collectAsStateWithLifecycle()
    val status = rememberLinkStatus(settings.link)
    val installed = status != LinkStatus.NotInstalled
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf<Reachability?>(null) }
    fun check() {
        if (checking) return
        checking = true
        scope.launch {
            // Null from a Ferb Link too old to check: keep its status alone.
            checked = settings.link.check()
            checking = false
        }
    }
    // Ready only means set up: ask the computer once the page shows, so the card says whether it answers.
    LaunchedEffect(status is LinkStatus.Ready) { if (status is LinkStatus.Ready) check() }
    val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    BackHandler(onBack = onBack)
    Scaffold(Modifier.nestedScroll(bar.nestedScrollConnection), containerColor = MaterialTheme.colorScheme.surfaceContainer, topBar = { TopBar("Ferb Link", onBack = onBack, scroll = bar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp)) {
            // Until it works: what it is and the steps, ticking themselves off (once a check has run, when Ferb Link is ready).
            val steps = setupSteps(status, checked?.reachable)
            val openFerbLink = settings.link.openIntent()?.let { intent -> { context.startActivity(intent) } }
            if (steps.isNotEmpty() && !(status is LinkStatus.Ready && checked == null)) {
                AboutLink()
                SetupChecklist(steps, openFerbLink)
            }
            if (installed) {
                StatusCard(
                    status, checked, checking,
                    onCheck = ::check,
                    onOpen = openFerbLink,
                )
            }

            SectionTitle("Use Ferb Link")
            Group(LinkMode.entries.map { option ->
                { shapes ->
                    val enabled = installed || option == LinkMode.Off
                    SegmentedListItem(
                        selected = option == mode,
                        onClick = { settings.setLinkMode(option) },
                        shapes = shapes,
                        enabled = enabled,
                        supportingContent = { Text(option.description()) },
                        leadingContent = { RadioButton(selected = option == mode, onClick = null, enabled = enabled) },
                    ) { Text(option.title()) }
                }
            })
            Text(
                "Requests your computer answers leave this phone over Tailscale.",
                Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Ferb Link's state at a glance: "Connected · model" in the primary colours once the computer
 * answers a check, the error colours when it doesn't or Ferb Link isn't ready (what to do in a
 * line), a refresh to check again, and Ferb Link itself behind the open icon.
 */
@Composable
private fun StatusCard(status: LinkStatus, checked: Reachability?, checking: Boolean, onCheck: () -> Unit, onOpen: (() -> Unit)?) {
    val ready = status is LinkStatus.Ready
    val ok = ready && checked?.reachable != false
    val title = when {
        !ready -> describe(status)
        checked?.reachable == false -> "Your computer doesn't answer"
        else -> describe(LinkStatus.Ready(null))
    }
    // Under the title: why the computer doesn't answer, or its model.
    val detail = when {
        !ready -> null
        checked?.reachable == false -> (checked.errorDetail ?: checked.error)?.replaceFirstChar { it.uppercase() }
        else -> checked?.model ?: (status as LinkStatus.Ready).model
    }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_link), contentDescription = null, Modifier.size(28.dp))
            Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            if (ready) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (checking) LoadingIndicator(Modifier.size(36.dp))
                    else IconButton(onClick = onCheck) { Icon(Icons.Default.Refresh, contentDescription = "Check connection") }
                }
            }
            onOpen?.let { IconButton(onClick = it) { Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = "Open Ferb Link") } }
        }
    }
}
