package dev.abhay.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.abhay.assistant.engine.AssistantEngine
import dev.abhay.assistant.tools.Tool
import dev.abhay.assistant.tools.ToolConfig
import dev.abhay.assistant.tools.ToolOption
import dev.abhay.assistant.tools.ToolSettings
import dev.abhay.assistant.tools.Tools
import java.util.Locale
import kotlin.math.roundToInt

/** Above this many tools turned on, Needle 3 only considers the best matches for each request. */
private const val MAX_ALWAYS_SEEN = 5

/** The settings home: the tools (a page of their own), and what runs the assistant. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: SettingsViewModel, engine: AssistantEngine.State, onOpenTools: () -> Unit, onBack: () -> Unit) {
    val saved by settings.saved.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            ListItem(
                onClick = onOpenTools,
                supportingContent = { Text("${ToolSettings.enabledNames(saved).size} of ${Tools.all.size} on · what the assistant can do, and when it asks first") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            ) { Text("Tools") }
            HorizontalDivider()
            ListItem(
                overlineContent = { Text("Model") },
                supportingContent = {
                    Text(
                        "Bundled in the app (35 MB) and run on this phone's CPU. The app has no internet access: nothing you type leaves the phone." +
                            ((engine as? AssistantEngine.State.Ready)?.let {
                                "\nLoaded in ${it.loadMs} ms; tools set up in ${it.initMs} ms (${it.prefixTokens} tokens)."
                            } ?: ""),
                    )
                },
            ) { Text("Needle 3 by Cactus Compute") }
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
        topBar = {
            TopAppBar(
                title = { Text("Tools") },
                subtitle = { Text("Tap a tool to set it up") },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        bottomBar = { SaveBar(settings) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 8.dp)) {
            item {
                Text(
                    when {
                        on == 0 -> "Turn on at least one tool."
                        on <= MAX_ALWAYS_SEEN -> "$on on: Needle 3 considers all of them for every request."
                        else -> "$on on: Needle 3 considers the $MAX_ALWAYS_SEEN that best match each request, so some requests may reach the wrong tool."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(Tools.all, key = { it.name }) { tool ->
                val config = draft.getValue(tool.name)
                ToolRow(tool, config, onOpen = { onOpenTool(tool.name) }, onToggle = { enabled -> settings.update(tool.name) { it.copy(enabled = enabled) } })
            }
        }
    }

    if (confirmLeave) UnsavedDialog(settings, onDone = { confirmLeave = false; onBack() }, onCancel = { confirmLeave = false })
}

/** Material 3's split list item: the text part opens the tool's page, the switch (past a divider) toggles it. */
@Composable
private fun ToolRow(tool: Tool, config: ToolConfig, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    ListItem(
        onClick = onOpen,
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
    config.alwaysConfirm -> "On · always asks first"
    else -> String.format(Locale.US, "On · runs without asking from %.2f", config.runFrom)
}

/**
 * One tool's page: on or off, always confirm, the confidence from which it runs without asking,
 * its own options (e.g. which clock), examples, and the schema Needle 3 gets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolConfigScreen(settings: SettingsViewModel, name: String, onBack: () -> Unit) {
    val tool = Tools.byName(name)
    if (tool == null) {
        // A tool a newer version removed: go back, from an effect rather than mid-composition.
        LaunchedEffect(name) { onBack() }
        return
    }
    val draft by settings.draft.collectAsStateWithLifecycle()
    val config = draft.getValue(name)
    val default = ToolConfig.defaults(tool)
    fun change(change: (ToolConfig) -> ToolConfig) = settings.update(name, change)
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tool.title) },
                subtitle = { Text(tool.description) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        bottomBar = { SaveBar(settings) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            SwitchRow("Use this tool", "Off: Needle 3 isn't told about it.", config.enabled) { on -> change { it.copy(enabled = on) } }
            SwitchRow(
                "Always confirm before running",
                "Ask Do it / Skip every time, whatever the confidence.",
                config.alwaysConfirm,
                enabled = config.enabled,
            ) { on -> change { it.copy(alwaysConfirm = on) } }
            ConfidenceSetting(config, default, enabled = config.enabled && !config.alwaysConfirm) { value -> change { it.copy(runFrom = value) } }

            tool.options.forEach { option ->
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                when (option) {
                    is ToolOption.Choice -> ChoiceSetting(option, config.choice(option), enabled = config.enabled) { id ->
                        change { it.copy(options = it.options + (option.key to id)) }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Try")
            tool.examples.forEach { Text("“$it”", Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodyLarge) }
            SectionTitle("What Needle 3 sees")
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Text(tool.schema, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        supportingContent = { Text(description) },
        // The whole row toggles; the switch only shows the state.
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    ) { Text(title) }
}

/** The confidence slider, in steps of 0.05, with a way back to the tool's default. */
@Composable
private fun ConfidenceSetting(config: ToolConfig, default: ToolConfig, enabled: Boolean, onChange: (Double) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(String.format(Locale.US, "Run without asking from confidence %.2f", config.runFrom), style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        val slider = remember {
            SliderState(
                value = config.runFrom.toFloat(),
                steps = ((ToolConfig.MAX_RUN_FROM - ToolConfig.MIN_RUN_FROM) / 0.05).roundToInt() - 1,
                trackRange = ToolConfig.MIN_RUN_FROM.toFloat()..ToolConfig.MAX_RUN_FROM.toFloat(),
            )
        }
        slider.value = config.runFrom.toFloat() // follows the draft, e.g. after Reset or Discard
        Slider(state = slider, onValueChange = { onChange((it * 20).roundToInt() / 20.0) }, enabled = enabled)
        Text(
            "Needle 3 gives each answer a confidence from 0 to 1. Lower: fewer questions, more mistakes run without asking. " +
                "Its confidence varies a lot with wording, so a good request can score anywhere from 0.3 to 0.9.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (config.runFrom != default.runFrom) {
            TextButton(onClick = { onChange(default.runFrom) }, enabled = enabled) {
                Text(String.format(Locale.US, "Reset to %.2f (default)", default.runFrom))
            }
        }
    }
}

@Composable
private fun ChoiceSetting(option: ToolOption.Choice, chosen: String, enabled: Boolean, onChoose: (String) -> Unit) {
    SectionTitle(option.title)
    option.items.forEach { item ->
        ListItem(
            selected = item.id == chosen,
            onClick = { onChoose(item.id) },
            enabled = enabled,
            supportingContent = { Text(item.description) },
            leadingContent = { RadioButton(selected = item.id == chosen, onClick = null, enabled = enabled) },
        ) { Text(item.label) }
    }
    option.note?.let {
        Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Save, at the bottom of the Tools screens, for the whole draft. Edge to edge, its colour runs
 * behind the navigation buttons and its contents stay above them.
 */
@Composable
private fun SaveBar(settings: SettingsViewModel) {
    val draft by settings.draft.collectAsStateWithLifecycle()
    val saved by settings.saved.collectAsStateWithLifecycle()
    val changed = draft != saved
    val valid = ToolSettings.enabledNames(draft).isNotEmpty()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when {
                    !valid -> "Turn on at least one tool"
                    changed -> "Unsaved changes"
                    else -> "All changes saved"
                },
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (changed) TextButton(onClick = settings::discard) { Text("Discard") }
            Button(onClick = settings::save, enabled = changed && valid) { Text("Save") }
        }
    }
}

@Composable
private fun UnsavedDialog(settings: SettingsViewModel, onDone: () -> Unit, onCancel: () -> Unit) {
    val draft by settings.draft.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Save your changes?") },
        text = { Text("The assistant keeps its current tool settings until you save.") },
        confirmButton = {
            TextButton(onClick = { settings.save(); onDone() }, enabled = ToolSettings.enabledNames(draft).isNotEmpty()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { settings.discard(); onDone() }) { Text("Discard") } },
    )
}
