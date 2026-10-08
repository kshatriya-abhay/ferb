package dev.abhay.assistant.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.abhay.assistant.R
import dev.abhay.assistant.tools.Keywords
import dev.abhay.assistant.tools.Tool
import dev.abhay.assistant.tools.ToolConfig
import dev.abhay.assistant.tools.ToolOption
import dev.abhay.assistant.tools.ToolSettings
import dev.abhay.assistant.tools.Tools
import java.util.Locale
import org.json.JSONObject
import kotlin.math.roundToInt

/** The settings home: the tools (a page of their own), and the model, which runs on the phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: SettingsViewModel, onOpenTools: () -> Unit, onBack: () -> Unit) {
    val saved by settings.saved.collectAsStateWithLifecycle()
    val checkAll by settings.checkAllAsFallback.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    Scaffold(topBar = { TopBar("Settings", onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            ListItem(
                onClick = onOpenTools,
                leadingContent = { IconBadge(R.drawable.ic_tune) },
                supportingContent = { Text("${ToolSettings.enabledNames(saved).size} of ${Tools.all.size} on") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            ) { Text("Tools") }
            ListItem(
                onClick = { settings.setCheckAllAsFallback(!checkAll) },
                leadingContent = { IconBadge(R.drawable.ic_search) },
                supportingContent = { Text("When a request names no tool, try every tool instead of the best five. Slower with many tools on.") },
                trailingContent = { Switch(checked = checkAll, onCheckedChange = null) },
            ) { Text("Check all tools as a fallback") }
            ListItem(
                leadingContent = { IconBadge(R.drawable.ic_lock) },
                supportingContent = { Text("Needle 3 runs on this phone. The app has no internet access.") },
            ) { Text("Private by design") }
        }
    }
}

/**
 * Every tool, one row each: the switch turns it on or off, the rest of the row opens its page.
 * Changes here and on the tools' pages are one draft, saved together; leaving with unsaved
 * changes asks first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(settings: SettingsViewModel, onOpenTool: (String) -> Unit, onBack: () -> Unit) {
    val draft by settings.draft.collectAsStateWithLifecycle()
    val saved by settings.saved.collectAsStateWithLifecycle()
    var confirmLeave by remember { mutableStateOf(false) }
    val back = { if (draft != saved) confirmLeave = true else onBack() }
    BackHandler(onBack = back)
    val on = ToolSettings.enabledNames(draft).size

    Scaffold(
        topBar = { TopBar("Tools", subtitle = "$on of ${Tools.all.size} on", onBack = back) },
        bottomBar = { SaveBar(settings) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(Tools.all, key = { it.name }) { tool ->
                val config = draft.getValue(tool.name)
                ToolRow(tool, config, onOpen = { onOpenTool(tool.name) }, onToggle = { enabled -> settings.update(tool.name) { it.copy(enabled = enabled) } })
            }
        }
    }

    if (confirmLeave) UnsavedDialog(settings, onDone = { confirmLeave = false; onBack() }, onCancel = { confirmLeave = false })
}

/** Material 3's split list item: the row opens the tool's page; the switch, past a divider, toggles it. */
@Composable
private fun ToolRow(tool: Tool, config: ToolConfig, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    ListItem(
        onClick = onOpen,
        leadingContent = { IconBadge(tool.icon, active = config.enabled) },
        supportingContent = { Text(policy(config)) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VerticalDivider(Modifier.height(32.dp).padding(end = 16.dp))
                Switch(checked = config.enabled, onCheckedChange = onToggle)
            }
        },
    ) { Text(tool.title) }
}

private fun policy(config: ToolConfig) = when {
    !config.enabled -> "Off"
    config.alwaysConfirm -> "Always asks first"
    else -> String.format(Locale.US, "Runs from %.2f", config.runFrom)
}

/**
 * One tool's page: on or off, always confirm, the confidence from which it runs without asking,
 * its keywords (a page of their own), its own options (e.g. which clock), examples, and, on
 * request, the schema Needle 3 gets.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val config = draft.getValue(name)
    val default = ToolConfig.defaults(tool)
    fun change(change: (ToolConfig) -> ToolConfig) = settings.update(name, change)
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = { TopBar(tool.title, onBack = onBack) },
        bottomBar = { SaveBar(settings) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Header(tool)
            SwitchRow("Use this tool", checked = config.enabled) { on -> change { it.copy(enabled = on) } }
            SwitchRow("Always ask first", checked = config.alwaysConfirm, enabled = config.enabled) { on -> change { it.copy(alwaysConfirm = on) } }
            ConfidenceSetting(config, default, enabled = config.enabled && !config.alwaysConfirm) { value -> change { it.copy(runFrom = value) } }
            ListItem(
                onClick = onOpenKeywords,
                supportingContent = {
                    Text(config.keywords.joinToString(", ").ifEmpty { "None: asked only when a request names no tool" },
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            ) { Text(if (config.keywords == default.keywords) "Keywords" else "Keywords (changed)") }

            tool.options.forEach { option ->
                when (option) {
                    is ToolOption.Choice -> ChoiceSetting(option, config.choice(option), enabled = config.enabled) { id ->
                        change { it.copy(options = it.options + (option.key to id)) }
                    }
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
@OptIn(ExperimentalMaterial3Api::class)
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
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = { TopBar("Keywords", subtitle = tool.title, onBack = onBack) },
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
                ListItem(
                    onClick = { editing = index },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(title: String, subtitle: String? = null, onBack: () -> Unit) {
    val navigation = @Composable { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
    if (subtitle == null) TopAppBar(title = { Text(title) }, navigationIcon = navigation)
    else TopAppBar(title = { Text(title) }, subtitle = { Text(subtitle) }, navigationIcon = navigation)
}

/** An icon in a tonal circle, for list rows; muted when [active] is false (a tool that's off). */
@Composable
private fun IconBadge(@DrawableRes icon: Int, active: Boolean = true, size: Int = 40) {
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
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

/**
 * A row that toggles as a whole; the switch only shows the state. A plain clickable row: the
 * checkable ListItem fills checked rows with a tint, which read as a highlight, not a setting.
 */
@Composable
private fun SwitchRow(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        onClick = { onChange(!checked) },
        enabled = enabled,
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    ) { Text(title) }
}

/** The confidence slider, in steps of 0.05, with the value in a badge and a way back to the default. */
@Composable
private fun ConfidenceSetting(config: ToolConfig, default: ToolConfig, enabled: Boolean, onChange: (Double) -> Unit) {
    val dim = if (enabled) 1f else 0.38f
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
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
    }
}

@Composable
private fun ChoiceSetting(option: ToolOption.Choice, chosen: String, enabled: Boolean, onChoose: (String) -> Unit) {
    SectionTitle(option.title)
    option.items.forEach { item ->
        ListItem(
            onClick = { onChoose(item.id) },
            enabled = enabled,
            supportingContent = { Text(item.description) },
            leadingContent = { RadioButton(selected = item.id == chosen, onClick = null, enabled = enabled) },
        ) { Text(item.label) }
    }
    option.note?.let {
        Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The JSON schema Needle 3 gets, behind a toggle: useful when tuning, noise otherwise. */
@Composable
private fun Schema(tool: Tool) {
    var shown by rememberSaveable { mutableStateOf(false) }
    ListItem(
        onClick = { shown = !shown },
        modifier = Modifier.padding(top = 16.dp),
        trailingContent = {
            Icon(if (shown) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
        },
    ) { Text("What Needle 3 sees") }
    AnimatedVisibility(shown, enter = expandVertically(), exit = shrinkVertically()) {
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
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
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
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
