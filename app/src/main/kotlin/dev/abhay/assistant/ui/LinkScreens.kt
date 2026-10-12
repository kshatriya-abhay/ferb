package dev.abhay.assistant.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.launch

/** Each way to use Ferb Link, in words: its name and what it does. */
private fun LinkMode.title() = when (this) {
    LinkMode.Off -> "Off"
    LinkMode.Fallback -> "When Needle 3 is unsure"
    LinkMode.Option -> "Choose in the chat"
}

private fun LinkMode.description() = when (this) {
    LinkMode.Off -> "Needle 3 answers everything, on this phone."
    LinkMode.Fallback -> "Needle 3 answers first. When it's unsure, or finds no tool, Ferb asks your computer and uses its answer."
    LinkMode.Option -> "The chat box gets a choice of Needle 3 or Ferb Link. Needle 3 answers when your computer can't."
}

/** Ferb Link's status now, refreshed whenever the screen comes back (e.g. from Ferb Link or installing it). */
@Composable
private fun rememberLinkStatus(link: FerbLink): LinkStatus {
    var status by remember { mutableStateOf(link.status()) }
    LifecycleResumeEffect(Unit) {
        status = link.status()
        onPauseOrDispose { }
    }
    return status
}

/**
 * The settings home's Ferb Link row: the way it's used, or, when Ferb Link isn't installed, that,
 * dimmed, and a tap explains what it is.
 */
@Composable
fun LinkRow(settings: SettingsViewModel, onOpen: () -> Unit) {
    val mode by settings.linkMode.collectAsStateWithLifecycle()
    val status = rememberLinkStatus(settings.link)
    var explaining by rememberSaveable { mutableStateOf(false) }
    val installed = status != LinkStatus.NotInstalled
    if (explaining) AboutLinkDialog(onDismiss = { explaining = false })
    ListItem(
        onClick = { if (installed) onOpen() else explaining = true },
        leadingContent = { IconBadge(R.drawable.ic_link, active = installed && mode != LinkMode.Off) },
        supportingContent = {
            Text(
                when {
                    !installed -> "Not installed. Tap to learn more"
                    mode == LinkMode.Off -> "Off. Use a bigger model on your computer"
                    status is LinkStatus.Ready -> mode.title()
                    else -> "${mode.title()} · ${describe(status)}"
                },
            )
        },
        trailingContent = { if (installed) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
    ) { Text("Ferb Link", color = if (installed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** What Ferb Link is and how to get it, for when it isn't installed. */
@Composable
fun AboutLinkDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_link), contentDescription = null) },
        title = { Text("Ferb Link") },
        text = { AboutLink() },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
private fun AboutLink() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ferb Link is a companion app that lets Ferb use a bigger model on your own computer, such as a laptop on your Tailscale network.")
        Text("Ferb itself stays offline: Ferb Link holds the internet access and passes requests to your computer. Needle 3 answers whenever your computer can't.")
        Text("To use it, install Ferb Link and Tailscale on this phone, run Ferb Link's server on your computer, and enter its address and key in Ferb Link.")
        // The download link comes at release.
        Text("Download: coming soon (work in progress).", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Ferb Link's page: whether it's ready (with a way into Ferb Link and a connection check), and how
 * Ferb uses it. Saved at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkScreen(settings: SettingsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val mode by settings.linkMode.collectAsStateWithLifecycle()
    val status = rememberLinkStatus(settings.link)
    val installed = status != LinkStatus.NotInstalled
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)
    Scaffold(topBar = { TopBar("Ferb Link", onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            if (!installed) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { AboutLink() }
            } else {
                ListItem(
                    leadingContent = { IconBadge(R.drawable.ic_link, active = status is LinkStatus.Ready) },
                    supportingContent = { checked?.let { Text(it) } },
                ) {
                    Text(describe(status), color = if (status is LinkStatus.Ready) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                }
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    settings.link.openIntent()?.let { intent ->
                        FilledTonalButton(onClick = { context.startActivity(intent) }) { Text("Open Ferb Link") }
                    }
                    OutlinedButton(
                        onClick = {
                            checking = true
                            scope.launch {
                                val found = settings.link.check()
                                checked = when {
                                    found == null -> "Update Ferb Link to check the connection from here"
                                    found.reachable -> "Your computer answers" + (found.model?.let { ": $it" } ?: "")
                                    else -> "Your computer doesn't answer" + (found.error?.let { ": $it" } ?: "")
                                }
                                checking = false
                            }
                        },
                        enabled = status is LinkStatus.Ready && !checking,
                    ) { Text(if (checking) "Checking…" else "Check connection") }
                }
            }

            SectionTitle("Use Ferb Link")
            LinkMode.entries.forEach { option ->
                ListItem(
                    onClick = { settings.setLinkMode(option) },
                    enabled = installed || option == LinkMode.Off,
                    supportingContent = { Text(option.description()) },
                    leadingContent = { RadioButton(selected = option == mode, onClick = null, enabled = installed || option == LinkMode.Off) },
                ) { Text(option.title()) }
            }
            Text(
                "Your computer's answers run like Needle 3's: tools set to always ask (calls, messages, new events) still ask first. " +
                    "With Ferb Link on, the requests your computer answers leave this phone for it, over Tailscale. Ferb itself still has no internet access.",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
