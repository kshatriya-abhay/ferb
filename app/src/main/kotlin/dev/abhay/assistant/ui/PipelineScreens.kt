package dev.abhay.assistant.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
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
import dev.abhay.assistant.pipeline.AudioStep
import dev.abhay.assistant.pipeline.Availability
import dev.abhay.assistant.pipeline.RewriteStep
import dev.abhay.assistant.pipeline.ToolStep
import dev.abhay.assistant.voice.GeminiSetup
import dev.abhay.assistant.voice.Part

/** One option of a step, as Settings and onboarding list it. */
internal data class StepOption<T>(val value: T, val title: String, val hint: String, val gemini: Boolean = false)

internal val AudioOptions = AudioStep.entries.map { StepOption(it, it.title, it.hint, gemini = it == AudioStep.Gemini) }
internal val RewriteOptions = RewriteStep.entries.map { StepOption(it, it.title, it.hint, gemini = it == RewriteStep.Gemini) }
internal val ToolOptions = ToolStep.entries.map {
    // Jev's hint says plainly that every request's text leaves for TypeSafe, not only some.
    StepOption(it, it.title, if (it == ToolStep.Link) "${it.hint}. Every request's text and your tools' names go to TypeSafe (US)" else it.hint)
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
    Group(options.map { option ->
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
                    Text(if (option.value == recommended && reason == null) "$RECOMMENDED · $line" else line)
                },
                trailingContent = if (option.gemini && geminiButton) {
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            VerticalDivider(Modifier.height(32.dp).padding(end = 4.dp))
                            IconButton(onClick = onOpenGemini, modifier = Modifier.size(48.dp)) {
                                Icon(painterResource(R.drawable.ic_tune), contentDescription = GEMINI_SETTINGS)
                            }
                        }
                    }
                } else null,
            ) { Text(option.title) }
        }
    })
}

internal const val RECOMMENDED = "Recommended"

/** The Gemini Nano rows' settings button, which opens its page (the row itself only selects it). */
internal const val GEMINI_SETTINGS = "Gemini Nano settings"

/** A step's name and what it's for, above its options. */
@Composable
internal fun StepTitle(title: String, hint: String) {
    Column(Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Settings → How Ferb understands you: the three steps in order (audio processing, prompt
 * rewrite, tool selection), each with its options. Needle 3 always makes the tool call. Saved at once.
 */
@Composable
internal fun UnderstandingSection(settings: SettingsViewModel, onOpenGemini: () -> Unit) {
    val pipeline by settings.pipeline.collectAsStateWithLifecycle()
    val gemini by settings.gemini.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { settings.checkGemini() }
    val available = rememberAvailability(settings.link, gemini)
    SectionTitle(UNDERSTANDING)
    Text("Needle 3, on this phone, always picks the action. These steps help it understand you.",
        Modifier.padding(horizontal = 32.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    StepTitle(AudioStep.TITLE, AudioStep.HINT)
    StepOptions(AudioOptions, pipeline.audio, settings::setAudio, available, gemini, onOpenGemini)
    StepTitle(RewriteStep.TITLE, RewriteStep.HINT)
    StepOptions(RewriteOptions, pipeline.rewrite, settings::setRewrite, available, gemini, onOpenGemini)
    StepTitle(ToolStep.TITLE, ToolStep.HINT)
    StepOptions(ToolOptions, pipeline.tools, settings::setTools, available, gemini, onOpenGemini)
}

internal const val UNDERSTANDING = "How Ferb understands you"
