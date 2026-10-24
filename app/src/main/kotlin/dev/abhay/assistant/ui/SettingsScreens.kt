@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.abhay.assistant.ui

import android.Manifest
import dev.abhay.assistant.notes.Obsidian
import dev.abhay.assistant.notes.SafNotes
import dev.abhay.assistant.notes.VaultCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.abhay.assistant.onboarding.Tip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.ui.input.nestedscroll.nestedScroll
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.abhay.assistant.R
import dev.abhay.assistant.link.LinkMode
import dev.abhay.assistant.tools.CalendarBook
import dev.abhay.assistant.tools.CalendarInfo
import dev.abhay.assistant.tools.Keywords
import dev.abhay.assistant.tools.Tool
import dev.abhay.assistant.tools.ToolConfig
import dev.abhay.assistant.tools.ToolOption
import dev.abhay.assistant.tools.ToolSettings
import dev.abhay.assistant.tools.Tools
import dev.abhay.assistant.voice.GeminiLanguage
import dev.abhay.assistant.voice.Part
import dev.abhay.assistant.voice.PhoneEar
import dev.abhay.assistant.voice.VoiceSettings
import kotlinx.coroutines.launch
import java.util.Locale
import org.json.JSONObject
import kotlin.math.roundToInt

/** The settings home: the tools (a page of their own), Ferb Link (likewise), voice on the phone, and the model, which runs on the phone. */
@Composable
fun SettingsScreen(settings: SettingsViewModel, onOpenTools: () -> Unit, onOpenLink: () -> Unit, onOpenGemini: () -> Unit = {}, onBack: () -> Unit) {
    val saved by settings.saved.collectAsStateWithLifecycle()
    val linkMode by settings.linkMode.collectAsStateWithLifecycle()
    val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(Modifier.nestedScroll(bar.nestedScrollConnection), containerColor = MaterialTheme.colorScheme.surfaceContainer, topBar = { TopBar("Settings", onBack = onBack, scroll = bar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Group(listOf(
                { shapes ->
                    SegmentedListItem(
                        onClick = onOpenTools,
                        shapes = shapes,
                        leadingContent = { IconBadge(R.drawable.ic_tune) },
                        supportingContent = { Text("${ToolSettings.enabledNames(saved).size} of ${Tools.all.size} on") },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    ) { Text("Tools") }
                },
                { shapes -> LinkRow(settings, onOpen = onOpenLink, shapes = shapes) },
            ))
            // Voice: on, Whistle or Gemini Nano writes spoken requests down on the phone; off, neither loads.
            val onDevice by settings.whistle.collectAsStateWithLifecycle()
            val ear by settings.ear.collectAsStateWithLifecycle()
            val gemini by settings.gemini.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { settings.checkGemini() }
            SectionTitle("Voice")
            Group(listOf(
                { shapes ->
                    SegmentedListItem(
                        onClick = { settings.setWhistle(!onDevice) },
                        shapes = shapes,
                        leadingContent = { IconBadge(R.drawable.ic_mic, active = onDevice) },
                        supportingContent = { Text(VoiceSettings.DESCRIPTION) },
                        trailingContent = { Switch(checked = onDevice, onCheckedChange = null) },
                    ) { Text(VoiceSettings.TITLE) }
                },
                { shapes ->
                    SegmentedListItem(
                        selected = ear == PhoneEar.Whistle,
                        onClick = { settings.setEar(PhoneEar.Whistle) },
                        shapes = shapes,
                        enabled = onDevice,
                        leadingContent = { RadioButton(selected = ear == PhoneEar.Whistle, onClick = null, enabled = onDevice) },
                        supportingContent = { Text(PhoneEar.Whistle.hint) },
                    ) { Text(PhoneEar.Whistle.title) }
                },
                { shapes ->
                    // M3's split list item: the row only selects Gemini Nano, as Whistle's does; past
                    // a divider, its own settings button (48 dp, its own ripple) opens its page.
                    SegmentedListItem(
                        selected = ear == PhoneEar.Gemini,
                        onClick = { settings.setEar(PhoneEar.Gemini) },
                        shapes = shapes,
                        enabled = onDevice,
                        leadingContent = { RadioButton(selected = ear == PhoneEar.Gemini, onClick = null, enabled = onDevice) },
                        supportingContent = { Text("${PhoneEar.Gemini.hint} \u00b7 ${gemini.summary()}") },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                VerticalDivider(Modifier.height(32.dp).padding(end = 4.dp))
                                IconButton(onClick = onOpenGemini, enabled = onDevice, modifier = Modifier.size(48.dp)) {
                                    Icon(painterResource(R.drawable.ic_tune), contentDescription = PhoneEar.GEMINI_SETTINGS)
                                }
                            }
                        },
                    ) { Text(PhoneEar.Gemini.title) }
                },
            ))
            Group(listOf { shapes ->
                SegmentedListItem(
                    onClick = { settings.onboarding.reset() },
                    shapes = shapes,
                    leadingContent = { IconBadge(R.drawable.ic_lightbulb) },
                    supportingContent = { Text("The welcome screen and the tips, as on the first launch") },
                ) { Text("Show tips again") }
            })
            Group(listOf { shapes ->
                SegmentedListItem(
                    shapes = shapes,
                    leadingContent = { IconBadge(R.drawable.ic_lock) },
                    supportingContent = {
                        Text(if (linkMode == LinkMode.Off) "Needle 3 runs on this phone. The app has no internet access."
                            else "Needle 3 runs on this phone, and the app has no internet access. Ferb Link sends some requests to your computer.")
                    },
                ) { Text("Private by design") }
            })
        }
    }
}

private const val CHECKING = "Checking\u2026"

/**
 * Gemini Nano (POC): the language it expects, whether each part is on the phone (hearing, and
 * writing what was heard in English for Needle 3), and Set up, which downloads what's missing
 * with its progress. Downloads go through Android; Ferb has no internet access.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GeminiScreen(settings: SettingsViewModel, onBack: () -> Unit) {
    val setup by settings.gemini.collectAsStateWithLifecycle()
    val language by settings.language.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { settings.checkGemini() }
    val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(Modifier.nestedScroll(bar.nestedScrollConnection), containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = { TopBar("Gemini Nano", subtitle = setup.summary(), onBack = onBack, scroll = bar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            Text(
                "Gemini Nano hears your request on this phone, then writes it as a plain English command for Needle 3, as Computer mode does. " +
                    "It runs in Android's AICore and needs a one-time download.",
                Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SectionTitle("You speak")
            Group(GeminiLanguage.entries.map { option ->
                { shapes ->
                    SegmentedListItem(
                        selected = option == language,
                        onClick = { settings.setLanguage(option) },
                        shapes = shapes,
                        leadingContent = { RadioButton(selected = option == language, onClick = null) },
                        supportingContent = if (option == GeminiLanguage.English) { { Text("Hindi is heard too") } } else null,
                    ) { Text(option.title) }
                }
            })
            SectionTitle("On this phone")
            Group(listOf(
                { shapes -> PartRow("Hearing you", setup.speech, R.drawable.ic_mic, shapes, onDownload = settings::setUpGeminiSpeech) },
                { shapes -> PartRow("Writing it in English", setup.rewrite, R.drawable.ic_sms, shapes, onDownload = settings::setUpGeminiRewrite) },
            ))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), horizontalArrangement = Arrangement.End) {
                when {
                    setup.ready -> OutlinedButton(onClick = settings::checkGemini) { Text("Check again") }
                    setup.downloading && !setup.needsSetUp -> Button(onClick = {}, enabled = false) { Text("Downloading\u2026") }
                    setup.parts.any { it == Part.Unavailable } -> OutlinedButton(onClick = settings::checkGemini) { Text("Check again") }
                    else -> Button(onClick = settings::setUpGemini, enabled = setup.parts.none { it == Part.Checking }) { Text("Set up") }
                }
            }
            Text(
                "Downloads go through Android, not Ferb, which has no internet access. Keep Ferb open until they finish.",
                Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Debug builds only: one Prompt API request, for FAST and FULL's status and latency.
            if (dev.abhay.assistant.NanoPromptCheck.AVAILABLE) {
                var result by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()
                Spacer(Modifier.height(16.dp))
                Group(listOf { shapes ->
                    SegmentedListItem(
                        onClick = {
                            if (result != CHECKING) {
                                result = CHECKING
                                scope.launch { result = dev.abhay.assistant.NanoPromptCheck.run() }
                            }
                        },
                        shapes = shapes,
                        leadingContent = { IconBadge(R.drawable.ic_lightbulb) },
                        supportingContent = { Text(result ?: "Tap to check FAST and FULL and time one request") },
                    ) { Text(dev.abhay.assistant.NanoPromptCheck.TITLE) }
                })
            }
        }
    }
}

/**
 * One part of Gemini Nano's setup: where it stands, with a progress bar while it downloads. Only a
 * part to download can be tapped, which downloads it; one that needs a download also links to
 * AICore's settings, where turning it on made the rewriting model available (2026-10-24).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PartRow(title: String, part: Part, @DrawableRes icon: Int, shapes: ListItemShapes, onDownload: () -> Unit) {
    val context = LocalContext.current
    val canDownload = part == Part.NeedsDownload || part is Part.Failed
    val leading: @Composable () -> Unit = { IconBadge(if (part == Part.Ready) R.drawable.ic_check else icon, active = part == Part.Ready) }
    val supporting: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (canDownload) "${part.label()}: tap to download" else part.label(),
                color = if (part is Part.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (part is Part.Downloading) {
                val fraction = part.fraction
                if (fraction != null) LinearWavyProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth())
                else LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
    val trailing: @Composable () -> Unit = {
        if (part == Part.NeedsDownload) TextButton(onClick = { dev.abhay.assistant.voice.NanoEars.openAiCoreSettings(context) }) { Text("AICore settings") }
    }
    if (canDownload) SegmentedListItem(onClick = onDownload, shapes = shapes, leadingContent = leading, supportingContent = supporting, trailingContent = trailing) { Text(title) }
    else SegmentedListItem(shapes = shapes, leadingContent = leading, supportingContent = supporting) { Text(title) }
}

/**
 * Rows grouped the Material 3 Expressive way: each in its own rounded container, a small gap
 * between them, the group's outer corners rounder. Each row gets its [ListItemShapes] by position.
 */
@Composable
internal fun Group(rows: List<@Composable (ListItemShapes) -> Unit>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        rows.forEachIndexed { i, row -> row(ListItemDefaults.segmentedShapes(i, rows.size)) }
    }
}

/**
 * Every tool, one row each: the switch turns it on or off, the rest of the row opens its page.
 * Changes here and on the tools' pages are one draft, saved together; leaving with unsaved
 * changes asks first.
 */
@Composable
fun ToolsScreen(settings: SettingsViewModel, onOpenTool: (String) -> Unit, onBack: () -> Unit) {
    val draft by settings.draft.collectAsStateWithLifecycle()
    val saved by settings.saved.collectAsStateWithLifecycle()
    var confirmLeave by remember { mutableStateOf(false) }
    val back = { if (draft != saved) confirmLeave = true else onBack() }
    // Unsaved changes: back asks first (no predictive peek then); otherwise the navigation's own back.
    BackHandler(enabled = draft != saved, onBack = back)
    val on = ToolSettings.enabledNames(draft).size
    val askPermissions = rememberPermissionAsk()
    val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        Modifier.nestedScroll(bar.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = { TopBar("Tools", subtitle = "$on of ${Tools.all.size} on", onBack = back, scroll = bar) },
        bottomBar = { SaveBar(settings) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            itemsIndexed(Tools.all, key = { _, tool -> tool.name }) { i, tool ->
                val config = draft.getValue(tool.name)
                ToolRow(tool, config, ListItemDefaults.segmentedShapes(i, Tools.all.size), onOpen = { onOpenTool(tool.name) }, onToggle = { enabled ->
                    if (enabled) askPermissions(tool)
                    settings.update(tool.name) { it.copy(enabled = enabled) }
                })
            }
        }
    }

    if (confirmLeave) UnsavedDialog(settings, onDone = { confirmLeave = false; onBack() }, onCancel = { confirmLeave = false })
}

/** The split list item: the row opens the tool's page; the switch, past a divider, toggles it. */
@Composable
private fun ToolRow(tool: Tool, config: ToolConfig, shapes: ListItemShapes, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    val note = policy(tool, config)
    SegmentedListItem(
        onClick = onOpen,
        shapes = shapes,
        leadingContent = { IconBadge(tool.icon, active = config.enabled) },
        supportingContent = note?.let { { Text(it) } },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VerticalDivider(Modifier.height(32.dp).padding(end = 16.dp))
                Switch(checked = config.enabled, onCheckedChange = onToggle)
            }
        },
    ) { Text(tool.title) }
}

/** Only what's worth a glance: a tool that always asks, or a bar changed from the tool's own. */
private fun policy(tool: Tool, config: ToolConfig) = when {
    !config.enabled -> null
    config.alwaysConfirm -> "Always asks first"
    config.runFrom != ToolConfig.defaults(tool).runFrom -> String.format(Locale.US, "Runs from %.2f", config.runFrom)
    else -> null
}

/**
 * One tool's page: on or off, always confirm, the confidence from which it runs without asking,
 * its keywords (a page of their own), its own options (e.g. which clock), examples, and, on
 * request, the schema Needle 3 gets.
 */
@Composable
fun ToolConfigScreen(settings: SettingsViewModel, name: String, onOpenKeywords: () -> Unit, onTry: (String) -> Unit, onBack: () -> Unit) {
    val tool = Tools.byName(name)
    if (tool == null) {
        // A tool a newer version removed: go back, from an effect rather than mid-composition.
        LaunchedEffect(name) { onBack() }
        return
    }
    val draft by settings.draft.collectAsStateWithLifecycle()
    val saved by settings.saved.collectAsStateWithLifecycle()
    val onboarding by settings.onboarding.state.collectAsStateWithLifecycle()
    val config = draft.getValue(name)
    val default = ToolConfig.defaults(tool)
    fun change(change: (ToolConfig) -> ToolConfig) = settings.update(name, change)
    val askPermissions = rememberPermissionAsk()
    var confirmStopAsking by rememberSaveable { mutableStateOf(false) }

    if (confirmStopAsking) {
        AlertDialog(
            onDismissRequest = { confirmStopAsking = false },
            title = { Text("Stop asking first?") },
            text = {
                Text("Not recommended for ${tool.title.lowercase()}: ${tool.whyAsk}")
            },
            confirmButton = { TextButton(onClick = { change { it.copy(alwaysConfirm = false) }; confirmStopAsking = false }) { Text("Stop asking") } },
            dismissButton = { TextButton(onClick = { confirmStopAsking = false }) { Text("Keep asking") } },
        )
    }

    val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        Modifier.nestedScroll(bar.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = { TopBar(tool.title, onBack = onBack, scroll = bar) },
        bottomBar = { SaveBar(settings) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Header(tool)
            if (tool.permissions.isNotEmpty()) PermissionsCard(tool)
            Group(listOf(
                { shapes ->
                    SwitchRow("Use this tool", checked = config.enabled, shapes = shapes) { on ->
                        if (on) askPermissions(tool)
                        change { it.copy(enabled = on) }
                    }
                },
                { shapes ->
                    SwitchRow(
                        "Always ask first",
                        checked = config.alwaysConfirm,
                        shapes = shapes,
                        enabled = config.enabled,
                        warning = "Not recommended for this tool".takeIf { tool.confirmByDefault && !config.alwaysConfirm },
                    ) { on -> if (!on && tool.confirmByDefault) confirmStopAsking = true else change { it.copy(alwaysConfirm = on) } }
                },
                { shapes -> ConfidenceSetting(config, default, shapes, enabled = config.enabled && !config.alwaysConfirm) { value -> change { it.copy(runFrom = value) } } },
                { shapes ->
                    SegmentedListItem(
                        onClick = { settings.onboarding.seen(Tip.Keywords); onOpenKeywords() },
                        shapes = shapes,
                        supportingContent = {
                            Text(config.keywords.joinToString(", ").ifEmpty { "None: asked only when a request names no tool" },
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    ) { Text(if (config.keywords == default.keywords) "Keywords" else "Keywords (changed)") }
                },
            ))
            if (Tip.Keywords !in onboarding.seen) {
                TipCard(Tip.Keywords, onDismiss = { settings.onboarding.seen(Tip.Keywords) }, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
            }

            val context = LocalContext.current
            val obsidian = remember { Obsidian.installed(context) }
            tool.options.forEach { option ->
                when (option) {
                    is ToolOption.Choice -> ChoiceSetting(option, config.choice(option), enabled = config.enabled, obsidian = obsidian) { id ->
                        change { it.copy(options = it.options + (option.key to id)) }
                    }
                    is ToolOption.Calendar -> CalendarSetting(option, config.options[option.key].orEmpty(), enabled = config.enabled) { id ->
                        change { it.copy(options = it.options + (option.key to id)) }
                    }
                    is ToolOption.NotesFolder -> NotesFolderSetting(settings.notes, enabled = config.enabled)
                }
            }

            SectionTitle("Try it")
            // Sends the example from the chat; only once the tool is on and saved, so it can answer.
            val usable = saved.getValue(name).enabled
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tool.examples.forEach { example ->
                    SuggestionChip(
                        onClick = { onTry(example) },
                        enabled = usable,
                        label = { Text(example) },
                        icon = { Icon(painterResource(tool.icon), contentDescription = null, Modifier.size(18.dp)) },
                    )
                }
            }
            Schema(tool)
        }
    }
}

/**
 * A tool's keywords: a request containing one goes to the tool, and a call to it from a request
 * without one asks first. Add, edit (tap a word), delete, or go back to the tool's own words; part
 * of the same draft as the rest of the Tools screens.
 */
@Composable
fun KeywordsScreen(settings: SettingsViewModel, name: String, onBack: () -> Unit) {
    val tool = Tools.byName(name)
    if (tool == null) {
        LaunchedEffect(name) { onBack() }
        return
    }
    val draft by settings.draft.collectAsStateWithLifecycle()
    val words = draft.getValue(name).keywords
    fun change(words: List<String>) = settings.update(name) { it.copy(keywords = words) }
    // The word being edited (its index), -1 for a new one, null when no dialog is open.
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }

    val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        Modifier.nestedScroll(bar.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = { TopBar("Keywords", subtitle = tool.title, onBack = onBack, scroll = bar) },
        bottomBar = { SaveBar(settings) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = -1 },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add") },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Text("A request with one of these words or phrases goes to this tool. They match whole words, also with an s " +
                    "on the end (\"alarm\" finds \"alarms\"). A call from a request without any of them asks first.",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (words.isEmpty()) item {
                Text("No keywords: this tool is only asked when a request has no tool's keywords, and its calls always ask first.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            items(words.size, key = { words[it] }) { index ->
                SegmentedListItem(
                    onClick = { editing = index },
                    shapes = ListItemDefaults.segmentedShapes(index, words.size),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = ListItemDefaults.SegmentedGap / 2),
                    trailingContent = {
                        IconButton(onClick = { change(words - words[index]) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete ${words[index]}")
                        }
                    },
                ) { Text(words[index]) }
            }
            if (words != tool.keywords) item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { change(tool.keywords) }) { Text("Reset to default words") }
                }
            }
        }
    }

    editing?.let { index ->
        KeywordDialog(
            initial = words.getOrNull(index).orEmpty(),
            taken = words.filterIndexed { i, _ -> i != index },
            onDone = { word ->
                change(if (index in words.indices) words.toMutableList().apply { this[index] = word } else words + word)
                editing = null
            },
            onCancel = { editing = null },
        )
    }
}

/** Adds a keyword ([initial] empty) or edits one; [taken] are the tool's other words, which it can't repeat. */
@Composable
private fun KeywordDialog(initial: String, taken: List<String>, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val word = Keywords.clean(text)
    val problem = when {
        text.isBlank() -> null
        word == null -> "At most ${Keywords.MAX_LENGTH} characters"
        word in taken -> "Already a keyword"
        else -> null
    }
    val ok = word != null && problem == null
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (initial.isEmpty()) "Add keyword" else "Edit keyword") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.focusRequester(focus),
                label = { Text("Word or phrase") },
                singleLine = true,
                isError = problem != null,
                supportingText = { Text(problem ?: "For example \"lamp\" or \"night light\"") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (ok) onDone(word!!) }),
            )
        },
        confirmButton = { TextButton(onClick = { onDone(word!!) }, enabled = ok) { Text(if (initial.isEmpty()) "Add" else "Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/**
 * A settings page's top bar: Material 3 Expressive's large flexible bar, which collapses into a
 * small one as the page scrolls ([scroll], whose nested scroll the page's Scaffold takes). Settings
 * pages sit on surfaceContainer, so their grouped rows (surface) stand out, as in Android's settings.
 */
@Composable
internal fun TopBar(title: String, subtitle: String? = null, onBack: () -> Unit, scroll: TopAppBarScrollBehavior) {
    LargeFlexibleTopAppBar(
        title = { Text(title) },
        subtitle = subtitle?.let { { Text(it) } },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        scrollBehavior = scroll,
    )
}

/** An icon in a tonal circle, for list rows; muted when [active] is false (a tool that's off). */
@Composable
internal fun IconBadge(@DrawableRes icon: Int, active: Boolean = true, size: Int = 40) {
    Surface(
        shape = CircleShape,
        color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(size.dp),
    ) { Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), contentDescription = null, Modifier.size((size * 0.5).dp)) } }
}

@Composable
private fun Header(tool: Tool) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        IconBadge(tool.icon, size = 56)
        Text(tool.description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

/** Asks Android for the permissions [Tool.permissions] that aren't granted yet; nothing if none are missing. */
@Composable
private fun rememberPermissionAsk(onResult: (Boolean) -> Unit = {}): (Tool) -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result -> onResult(result.values.all { it }) }
    return { tool ->
        val missing = tool.permissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
    }
}

/** What a permission lets the tool see, in words. */
private fun permissionTitle(permission: String) = when (permission) {
    Manifest.permission.READ_CONTACTS -> "Contacts"
    Manifest.permission.CALL_PHONE -> "Calls"
    Manifest.permission.READ_CALENDAR -> "Calendar"
    Manifest.permission.WRITE_CALENDAR -> "Adding events"
    else -> permission.substringAfterLast('.').lowercase().replace('_', ' ')
}

/**
 * What the tool needs from Android (e.g. the contacts), shown only while it's missing: Allow asks;
 * once refused, Android won't ask again, so the button opens the app's page in Android's settings.
 */
@Composable
private fun PermissionsCard(tool: Tool) {
    val context = LocalContext.current
    fun granted() = tool.permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    var allowed by remember { mutableStateOf(granted()) }
    var refused by rememberSaveable { mutableStateOf(false) }
    val ask = rememberPermissionAsk { ok -> allowed = ok; refused = !ok }
    // Back from Android's settings, or the permission changed while away.
    LifecycleResumeEffect(tool) {
        allowed = granted()
        onPauseOrDispose { }
    }
    AnimatedVisibility(!allowed, enter = expandVertically(), exit = shrinkVertically()) {
        Card(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Needs ${tool.permissions.joinToString { permissionTitle(it) }.lowercase()}", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge)
                Button(onClick = {
                    if (refused) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    else ask(tool)
                }) { Text(if (refused) "Open settings" else "Allow") }
            }
        }
    }
}

/**
 * A row that toggles as a whole; the switch only shows the state. A plain clickable row: the
 * checkable ListItem fills checked rows with a tint, which read as a highlight, not a setting.
 */
@Composable
private fun SwitchRow(title: String, checked: Boolean, shapes: ListItemShapes, enabled: Boolean = true, warning: String? = null, onChange: (Boolean) -> Unit) {
    SegmentedListItem(
        onClick = { onChange(!checked) },
        shapes = shapes,
        enabled = enabled,
        supportingContent = warning?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    ) { Text(title) }
}

/** The confidence slider, in steps of 0.05, with the value in a badge and a way back to the default. */
@Composable
private fun ConfidenceSetting(config: ToolConfig, default: ToolConfig, shapes: ListItemShapes, enabled: Boolean, onChange: (Double) -> Unit) {
    val dim = if (enabled) 1f else 0.38f
    SegmentedListItem(shapes = shapes) { Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Run without asking from", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim))
                Text("Lower means fewer questions", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim))
            }
            Surface(shape = CircleShape, color = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest) {
                Text(String.format(Locale.US, "%.2f", config.runFrom), Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge, color = LocalContentColor.current.copy(alpha = dim))
            }
        }
        // Continuous (18 tick marks were busy); values snap to 0.05 in onValueChange.
        val slider = remember {
            SliderState(value = config.runFrom.toFloat(), trackRange = ToolConfig.MIN_RUN_FROM.toFloat()..ToolConfig.MAX_RUN_FROM.toFloat())
        }
        slider.value = config.runFrom.toFloat() // follows the draft, e.g. after Reset or Discard
        Slider(state = slider, onValueChange = { onChange((it * 20).roundToInt() / 20.0) }, enabled = enabled)
        if (config.runFrom != default.runFrom) {
            TextButton(onClick = { onChange(default.runFrom) }, enabled = enabled, modifier = Modifier.align(Alignment.End)) {
                Text(String.format(Locale.US, "Reset to %.2f", default.runFrom))
            }
        }
    } }
}

@Composable
private fun ChoiceSetting(option: ToolOption.Choice, chosen: String, enabled: Boolean, obsidian: Boolean, onChoose: (String) -> Unit) {
    SectionTitle(option.title)
    // A choice that needs Obsidian is offered only with it installed, or while it's the one chosen.
    Group(option.items.filter { !it.needsObsidian || obsidian || it.id == chosen }.map { item ->
        { shapes ->
            SegmentedListItem(
                selected = item.id == chosen,
                onClick = { onChoose(item.id) },
                shapes = shapes,
                enabled = enabled,
                supportingContent = { Text(item.description) },
                leadingContent = { RadioButton(selected = item.id == chosen, onClick = null, enabled = enabled) },
            ) { Text(item.label) }
        }
    })
    option.note?.let {
        Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Which calendar new events go to, as one row that opens a menu of the phone's calendars that take
 * events, read whenever the page shows (so after allowing access). Until one is picked, the
 * account's own ([CalendarBook.target]).
 */
@Composable
private fun CalendarSetting(option: ToolOption.Calendar, chosen: String, enabled: Boolean, onChoose: (String) -> Unit) {
    val context = LocalContext.current
    var calendars by remember { mutableStateOf<List<CalendarInfo>?>(null) }
    LifecycleResumeEffect(Unit) {
        calendars = CalendarBook.from(context).calendars()
        onPauseOrDispose { }
    }
    val all = calendars
    val writable = all?.filter { it.writable }.orEmpty()
    val current = all?.let { CalendarBook({ it }, { _, _ -> null }).target(chosen) }
    var open by remember { mutableStateOf(false) }
    Spacer(Modifier.height(16.dp))
    Group(listOf { shapes ->
        Box {
            SegmentedListItem(
                onClick = { open = true },
                shapes = shapes,
                enabled = enabled && writable.isNotEmpty(),
                supportingContent = {
                    Text(when {
                        all == null -> "Allow the calendar to choose one"
                        writable.isEmpty() -> "No calendar on this phone takes events"
                        else -> current?.name ?: "None"
                    })
                },
                trailingContent = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            ) { Text(option.title) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                writable.forEach { calendar ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(calendar.name)
                                where(calendar)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        },
                        leadingIcon = { RadioButton(selected = calendar.id == current?.id, onClick = null) },
                        onClick = { open = false; onChoose(calendar.id.toString()) },
                    )
                }
            }
        }
    })
}

/** Where a calendar lives, when its name doesn't say: only on this phone, or another account's. */
private fun where(calendar: CalendarInfo) = when {
    calendar.local -> "Only on this phone"
    calendar.account != calendar.name -> calendar.account
    else -> null
}

/**
 * The notes folder: the one the user picked in their vault (Storage Access Framework), or none.
 * Picking it is saved at once, apart from the tools' draft: Android grants it there and then.
 */
@Composable
private fun NotesFolderSetting(notes: SafNotes, enabled: Boolean) {
    var name by remember { mutableStateOf(notes.folderName().takeIf { notes.ready }) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            notes.pick(tree)
            name = notes.folderName()
        }
    }
    // Whether it's a vault's top folder: a warning when not, nothing blocked.
    val vault by produceState<VaultCheck.Outcome?>(null, name) {
        value = if (name == null) null else withContext(Dispatchers.IO) { notes.vaultCheck() }
    }
    Spacer(Modifier.height(16.dp))
    Group(listOf { shapes ->
        SegmentedListItem(
            onClick = { pick.launch(null) },
            shapes = shapes,
            enabled = enabled,
            supportingContent = { Text(name ?: "None yet: notes go to your notes app") },
            trailingContent = { Text(if (name == null) "Pick" else "Change", color = MaterialTheme.colorScheme.primary) },
        ) { Text("Notes folder") }
    })
    vault?.let(VaultCheck::warning)?.let {
        Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/** The JSON schema Needle 3 gets, behind a toggle: useful when tuning, noise otherwise. */
@Composable
private fun Schema(tool: Tool) {
    var shown by rememberSaveable { mutableStateOf(false) }
    Spacer(Modifier.height(16.dp))
    Group(listOf { shapes ->
        SegmentedListItem(
            onClick = { shown = !shown },
            shapes = shapes,
            trailingContent = {
                Icon(if (shown) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
            },
        ) { Text("What Needle 3 sees") }
    })
    AnimatedVisibility(shown, enter = expandVertically(), exit = shrinkVertically()) {
        Card(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Text(remember(tool) { JSONObject(tool.schema).toString(2) }, Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

/**
 * Save and Discard, only while there are unsaved changes across the Tools screens. Edge to edge,
 * its colour runs behind the navigation buttons and its contents stay above them.
 */
@Composable
private fun SaveBar(settings: SettingsViewModel) {
    val draft by settings.draft.collectAsStateWithLifecycle()
    val saved by settings.saved.collectAsStateWithLifecycle()
    val valid = ToolSettings.enabledNames(draft).isNotEmpty()
    AnimatedVisibility(draft != saved, enter = expandVertically(), exit = shrinkVertically()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Column(Modifier.navigationBarsPadding()) {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (valid) "Unsaved changes" else "Turn on at least one tool", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                    TextButton(onClick = settings::discard) { Text("Discard") }
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = settings::save, enabled = valid) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun UnsavedDialog(settings: SettingsViewModel, onDone: () -> Unit, onCancel: () -> Unit) {
    val draft by settings.draft.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Save changes?") },
        text = { Text("The assistant keeps its current settings until you save.") },
        confirmButton = {
            TextButton(onClick = { settings.save(); onDone() }, enabled = ToolSettings.enabledNames(draft).isNotEmpty()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { settings.discard(); onDone() }) { Text("Discard") } },
    )
}
