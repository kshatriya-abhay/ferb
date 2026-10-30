package dev.abhay.assistant.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.abhay.assistant.R
import dev.abhay.assistant.link.FerbLink
import dev.abhay.assistant.link.LinkStatus
import dev.abhay.assistant.link.describe
import dev.abhay.assistant.pipeline.Abilities
import dev.abhay.assistant.pipeline.AudioStep
import dev.abhay.assistant.pipeline.Availability
import dev.abhay.assistant.pipeline.ModelChoice
import dev.abhay.assistant.pipeline.RewriteStep
import dev.abhay.assistant.pipeline.ToolStep
import dev.abhay.assistant.voice.GeminiSetup
import dev.abhay.assistant.voice.GeminiWatch
import dev.abhay.assistant.voice.Part

/** One option of a step, as Settings and onboarding list it. */
internal data class StepOption<T>(val value: T, val title: String, val hint: String, val gemini: Boolean = false)

internal val AudioOptions = AudioStep.entries.map { StepOption(it, it.title, it.hint, gemini = it == AudioStep.Gemini) }
internal val RewriteOptions = RewriteStep.entries.map { StepOption(it, it.title, it.hint, gemini = it == RewriteStep.Gemini) }
internal val ToolOptions = ToolStep.entries.map {
    // Jev's hint says plainly that every request's text leaves for TypeSafe, not only some.
    StepOption(it, it.title, if (it == ToolStep.Link) "${it.hint}. Requests go to TypeSafe (US)" else it.hint)
}

/** What each option needs now ([Availability]): Ferb Link's state, and Gemini Nano's setup. */
internal fun availability(status: LinkStatus, computerHears: Boolean, setup: GeminiSetup) = Availability(
    linkReason = when (status) {
        is LinkStatus.Ready -> null
        LinkStatus.NotInstalled -> Availability.LINK_NOT_INSTALLED
        LinkStatus.NotSetUp -> Availability.LINK_NOT_PAIRED
        else -> describe(status)
    },
    geminiSpeech = setup.speech.let { if (it == Part.Checking) null else it != Part.Unavailable },
    geminiRewrite = setup.rewrite.let { if (it == Part.Checking) null else it != Part.Unavailable },
    linkAudioReason = Availability.LINK_NO_AUDIO.takeUnless { computerHears },
    jevReason = (status as? LinkStatus.Ready)?.let { if (it.jevCapped) Availability.JEV_CAPPED else if (!it.jev) Availability.JEV_OFF else null },
)

/** [availability], kept up to date: Ferb Link's state on each resume, whether its model hears, and Gemini Nano's setup. */
@Composable
internal fun rememberAvailability(link: FerbLink, gemini: GeminiSetup): Availability {
    val status = rememberLinkStatus(link)
    val hears = rememberVoiceAvailable(link, active = status is LinkStatus.Ready)
    return availability(status, hears, gemini)
}

/**
 * A step's options as radio rows: the one in use selected, those that can't be used now greyed
 * out with why, and Gemini Nano's with its setup and, past a divider, its own settings button
 * (M3's split list item: the row selects, the button opens Settings → Gemini Nano).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun <T : Any> StepOptions(
    options: List<StepOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    available: Availability,
    gemini: GeminiSetup,
    onOpenGemini: () -> Unit,
    /** Marked "Recommended" (onboarding). */
    recommended: T? = null,
    /** Gemini Nano's settings button (not in onboarding, which has no page for it). */
    geminiButton: Boolean = true,
) {
    // One that can't ever work here isn't offered, unless it's the one in use.
    Group(options.filter { !available.hidden(it.value) || it.value == selected }.map { option ->
        { shapes ->
            val reason = available.reason(option.value)
            val enabled = reason == null || option.value == selected
            SegmentedListItem(
                selected = option.value == selected,
                onClick = { onSelect(option.value) },
                shapes = shapes,
                enabled = enabled,
                leadingContent = { RadioButton(selected = option.value == selected, onClick = null, enabled = enabled) },
                supportingContent = {
                    val line = when {
                        reason != null -> reason
                        option.gemini -> "${option.hint} · ${gemini.summary()}"
                        else -> option.hint
                    }
                    Text(if (option.value == recommended && reason == null) "${stringResource(RECOMMENDED)} · $line" else line)
                },
                trailingContent = if (option.gemini && geminiButton) {
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            VerticalDivider(Modifier.height(32.dp).padding(end = 4.dp))
                            IconButton(onClick = onOpenGemini, modifier = Modifier.size(48.dp)) {
                                Icon(painterResource(R.drawable.ic_tune), contentDescription = stringResource(GEMINI_SETTINGS))
                            }
                        }
                    }
                } else null,
            ) { Text(option.title) }
        }
    })
}

internal val RECOMMENDED = R.string.ui_recommended

/** The Gemini Nano rows' settings button, which opens its page (the row itself only selects it). */
internal val GEMINI_SETTINGS = R.string.ui_gemini_settings

/** A step's name and what it's for, above its options. */
@Composable
internal fun StepTitle(title: String, hint: String) {
    Column(Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The model choice as radio rows: the one in use selected (none once a step was changed under
 * Advanced), each with its line, and one that can't ever work here greyed out with why. Under
 * them, what the one picked still needs: Gemini Nano's download and its progress ([GeminiSetUp]),
 * or Ferb Link's pairing, with a button to open it ([onOpenLink], null when it can't be opened).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ModelOptions(
    selected: ModelChoice?,
    can: Abilities,
    onSelect: (ModelChoice) -> Unit,
    gemini: GeminiSetup,
    onSetUpGemini: () -> Unit,
    onCheckGemini: () -> Unit,
    onOpenLink: (() -> Unit)?,
    recommended: ModelChoice? = null,
    showReady: Boolean = false,
    watch: GeminiWatch = GeminiWatch.Off,
) {
    Group(ModelChoice.entries.map { model ->
        { shapes ->
            val reason = model.reason(can)
            val enabled = reason == null || model == selected
            SegmentedListItem(
                selected = model == selected,
                onClick = { onSelect(model) },
                shapes = shapes,
                enabled = enabled,
                leadingContent = { RadioButton(selected = model == selected, onClick = null, enabled = enabled) },
                supportingContent = {
                    val line = reason ?: model.hint
                    Text(if (model == recommended && reason == null) "${stringResource(RECOMMENDED)} · $line" else line)
                },
            ) { Text(model.title) }
        }
    })
    when {
        selected == ModelChoice.Gemini -> GeminiSetUp(gemini, onSetUpGemini, onCheckGemini, showReady = showReady, watch = watch)
        selected?.needsPairing(can) == true -> Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ModelChoice.PAIR_LINK, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            onOpenLink?.let { OutlinedButton(onClick = it) { Text(stringResource(OPEN_FERB_LINK)) } }
        }
    }
}

internal val OPEN_FERB_LINK = R.string.link_open_ferb_link

/** Opens Ferb Link, or null when it isn't installed. */
@Composable
internal fun rememberOpenLink(link: FerbLink): (() -> Unit)? {
    val context = androidx.compose.ui.platform.LocalContext.current
    return link.openIntent()?.let { intent -> { context.startActivity(intent) } }
}

/**
 * What this phone can use now, kept up to date while shown: Gemini Nano (checked once) and Ferb
 * Link (on each resume, with a live check of whether the computer hears).
 */
@Composable
internal fun rememberAbilities(settings: SettingsViewModel): Abilities {
    LaunchedEffect(Unit) { settings.checkGemini() }
    val status = rememberLinkStatus(settings.link)
    val hears = rememberVoiceAvailable(settings.link, active = status is LinkStatus.Ready)
    LaunchedEffect(status, hears) { settings.linkChanged(hears.takeIf { it }) }
    val can by settings.abilities.collectAsStateWithLifecycle()
    return can
}

/** Settings → Choose your preferred model, and the row to Advanced (the steps one by one). */
@Composable
internal fun ModelSection(settings: SettingsViewModel, onOpenAdvanced: () -> Unit) {
    val chosen by settings.chosen.collectAsStateWithLifecycle()
    val can = rememberAbilities(settings)
    SectionTitle(ModelChoice.TITLE)
    val selected = chosen.model ?: if (chosen.custom) null else ModelChoice.default(can)
    val gemini by settings.gemini.collectAsStateWithLifecycle()
    val watch by settings.geminiWatch.collectAsStateWithLifecycle()
    ModelOptions(selected, can, { settings.setModel(it) }, gemini, settings::setUpGemini, settings::checkGemini,
        rememberOpenLink(settings.link), watch = watch)
    if (selected == null) Note(stringResource(CUSTOM_STEPS))
    Group(listOf { shapes ->
        SegmentedListItem(
            onClick = onOpenAdvanced,
            shapes = shapes,
            leadingContent = { IconBadge(R.drawable.ic_tune) },
            supportingContent = { Text(stringResource(R.string.pipeline_each_step_one_by_one)) },
            trailingContent = { Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        ) { Text(stringResource(ADVANCED)) }
    })
}

/**
 * Gemini Nano's setup under the model choice, when Gemini Nano is picked: the check, a download button, its progress
 * (Ferb works on what's there meanwhile, and switches when it's ready), a failed download's reason
 * with Try again, a status check that threw ("couldn't check", Try again: [onCheckAgain]), or one
 * line when AICore says it can't run on this phone. [showReady]: say when it's ready; it's said
 * anyway when it became ready while Ferb waited ([watch]). A download still going after Ferb
 * stopped asking ([GeminiWatch.GaveUp]) has Try again.
 */
@Composable
internal fun GeminiSetUp(setup: GeminiSetup, onSetUp: () -> Unit, onCheckAgain: () -> Unit, showReady: Boolean = false, watch: GeminiWatch = GeminiWatch.Off) {
    val failed = setup.parts.filterIsInstance<Part.Failed>().firstOrNull()
    val unchecked = setup.parts.filterIsInstance<Part.Unchecked>().firstOrNull()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            setup.ready -> if (showReady || watch == GeminiWatch.Ready) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.ui_gemini_ready), style = MaterialTheme.typography.bodyMedium)
            }
            unchecked != null -> {
                Text(stringResource(R.string.ui_gemini_unchecked, unchecked.why), style = MaterialTheme.typography.bodyMedium, color = muted)
                OutlinedButton(onClick = onCheckAgain) { Text(stringResource(R.string.ui_try_again)) }
            }
            setup.speech == Part.Unavailable -> Text(stringResource(R.string.ui_gemini_cant_run), style = MaterialTheme.typography.bodyMedium, color = muted)
            setup.downloading && watch == GeminiWatch.GaveUp -> {
                Text(stringResource(R.string.ui_gemini_still_downloading), style = MaterialTheme.typography.bodyMedium, color = muted)
                OutlinedButton(onClick = onCheckAgain) { Text(stringResource(R.string.ui_try_again)) }
            }
            setup.downloading -> {
                // No percentage: AICore's byte counts may be misleading (its model is shared across apps).
                Text(stringResource(R.string.ui_gemini_downloading), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.ui_gemini_meanwhile), style = MaterialTheme.typography.bodySmall, color = muted)
            }
            failed != null -> {
                Text(stringResource(R.string.ui_gemini_failed, failed.why), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onSetUp) { Text(stringResource(R.string.ui_try_again)) }
            }
            setup.needsSetUp -> {
                Text(stringResource(R.string.ui_gemini_offer), style = MaterialTheme.typography.bodyMedium, color = muted)
                FilledTonalButton(onClick = onSetUp) { Text(stringResource(SET_UP_GEMINI)) }
            }
            setup.parts.any { it == Part.Checking } -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.ui_gemini_checking), style = MaterialTheme.typography.bodyMedium, color = muted)
            }
        }
    }
}

internal val SET_UP_GEMINI = R.string.ui_set_up_gemini
internal val ADVANCED = R.string.ui_advanced
internal val CUSTOM_STEPS = R.string.ui_custom_steps

/** Settings → Advanced: the three steps, one by one. */
@Composable
fun AdvancedScreen(settings: SettingsViewModel, onOpenGemini: () -> Unit, onBack: () -> Unit) {
    val bar = androidx.compose.material3.TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    androidx.compose.material3.Scaffold(Modifier.nestedScroll(bar.nestedScrollConnection), containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = { TopBar(stringResource(ADVANCED), onBack = onBack, scroll = bar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            UnderstandingSection(settings, onOpenGemini)
        }
    }
}

/**
 * Settings → Advanced → How Ferb understands you: the three steps in order (audio processing, prompt
 * rewrite, tool selection), each with its options. Needle 3 always makes the tool call. Saved at once.
 */
@Composable
internal fun UnderstandingSection(settings: SettingsViewModel, onOpenGemini: () -> Unit) {
    val pipeline by settings.pipeline.collectAsStateWithLifecycle()
    val gemini by settings.gemini.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { settings.checkGemini() }
    val available = rememberAvailability(settings.link, gemini)
    SectionTitle(stringResource(UNDERSTANDING))
    Note("Needle 3 always picks the action, on this phone.")
    // What isn't offered here, and why, in a line each.
    if (available.linkReason == Availability.LINK_NOT_INSTALLED) Note(Availability.LINK_NOT_INSTALLED)
    if (available.geminiSpeech == false && available.geminiRewrite == false) Note("Gemini Nano: ${Availability.GEMINI_UNAVAILABLE.lowercase()}")
    StepTitle(AudioStep.TITLE, AudioStep.HINT)
    StepOptions(AudioOptions, pipeline.audio, settings::setAudio, available, gemini, onOpenGemini)
    StepTitle(RewriteStep.TITLE, RewriteStep.HINT)
    StepOptions(RewriteOptions, pipeline.rewrite, settings::setRewrite, available, gemini, onOpenGemini)
    // Tool selection has only Jev besides off: without Ferb Link there's nothing to choose.
    if (!available.hidden(ToolStep.Link) || pipeline.tools != ToolStep.Off) {
        StepTitle(ToolStep.TITLE, ToolStep.HINT)
        StepOptions(ToolOptions, pipeline.tools, settings::setTools, available, gemini, onOpenGemini)
    }
}

/** A short line under a section title. */
@Composable
private fun Note(text: String) = Text(text, Modifier.padding(horizontal = 32.dp),
    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

internal val UNDERSTANDING = R.string.ui_understanding
