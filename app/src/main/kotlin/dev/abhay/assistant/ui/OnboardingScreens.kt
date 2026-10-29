package dev.abhay.assistant.ui

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.abhay.assistant.onboarding.Page
import dev.abhay.assistant.pipeline.AudioStep
import dev.abhay.assistant.pipeline.Availability
import dev.abhay.assistant.pipeline.Pipeline
import dev.abhay.assistant.pipeline.RewriteStep
import dev.abhay.assistant.pipeline.Saved
import dev.abhay.assistant.pipeline.ToolStep
import dev.abhay.assistant.voice.GeminiSetup
import dev.abhay.assistant.voice.Part

/** Each step's page in plain words: its question, and what the choice means. */
internal object PageText {
    const val AUDIO_TITLE = "How should Ferb hear you?"
    const val AUDIO_BODY = "Tap the mic and say what you'd like done. Pick what turns your voice into text: Gemini Nano and Whistle " +
        "stay on this phone; Ferb Link uses your own computer. Off hides the mic, and you type instead. Ferb asks for the microphone the first time you tap it."
    const val REWRITE_TITLE = "Should Ferb tidy up what you ask?"
    const val REWRITE_BODY = "Before acting, Ferb can rewrite your request as one short, plain English command. That helps with Hindi and " +
        "Hinglish, and lets Ferb tell when nothing needs doing, such as a question. Gemini Nano does it on this phone; Ferb Link on your computer."
    const val TOOLS_TITLE = "Should Jev pick the tool?"
    const val TOOLS_BODY = "Jev, a model in the cloud reached through Ferb Link, can narrow down which tool fits before Ferb acts. Your " +
        "request's text and your tools' names then go to TypeSafe (US). Off keeps it all on this phone."
    const val ASSISTANT_TITLE = "Make Ferb your phone's assistant"
    const val ASSISTANT_BODY = "Then holding the power button, or swiping up from a bottom corner, opens Ferb from anywhere. In Android's " +
        "default apps, pick Ferb as the Digital assistant app. Ferb doesn't read your screen."
    const val SKIP = "Skip"
    const val NEXT = "Next"
    const val BACK = "Back"
    const val DONE = "Done"
    const val SET_UP_GEMINI = "Set up Gemini Nano"
    const val OPEN_DEFAULT_APPS = "Open default apps"
}

/**
 * The first launch (and Settings → Run setup again), one page at a time: the welcome, then each
 * step of how Ferb understands you with its options (the recommended one picked, and why an option
 * can't be used now), then the assistant role. Skip ends it at once, keeping what's chosen; Next on
 * a step saves the option shown, so a default doesn't change under the user later.
 */
@Composable
internal fun OnboardingFlow(
    pipeline: Pipeline,
    recommended: Pipeline,
    available: Availability,
    gemini: GeminiSetup,
    assistantHeld: Boolean,
    onAudio: (AudioStep) -> Unit,
    onRewrite: (RewriteStep) -> Unit,
    onTools: (ToolStep) -> Unit,
    onSetUpGemini: () -> Unit,
    onOpenDefaultApps: () -> Unit,
    onDone: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(Page.Welcome) }
    val pages = Page.shown(assistantHeld)
    fun next() {
        when (page) {
            Page.Audio -> onAudio(pipeline.audio)
            Page.Rewrite -> onRewrite(pipeline.rewrite)
            Page.Tools -> onTools(pipeline.tools)
            else -> {}
        }
        Page.next(page, assistantHeld)?.let { page = it } ?: onDone()
    }
    Page.previous(page, assistantHeld)?.let { previous -> BackHandler { page = previous } }
    val motion = MaterialTheme.motionScheme
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            // Skip, top right, on every page but the last.
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (page != pages.last()) TextButton(onClick = onDone) { Text(PageText.SKIP) }
            }
            // Material's shared axis on X between pages, forward to the right.
            AnimatedContent(page, Modifier.weight(1f), transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                (slideInHorizontally(motion.defaultSpatialSpec<IntOffset>()) { if (forward) it / 4 else -it / 4 } + fadeIn(motion.defaultEffectsSpec())) togetherWith
                    (slideOutHorizontally(motion.defaultSpatialSpec<IntOffset>()) { if (forward) -it / 4 else it / 4 } + fadeOut(motion.defaultEffectsSpec()))
            }, label = "onboarding") { shown ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (shown) {
                        Page.Welcome -> WelcomeContent()
                        Page.Audio -> {
                            PageHeader(PageText.AUDIO_TITLE, PageText.AUDIO_BODY)
                            StepOptions(AudioOptions, pipeline.audio, onAudio, available, gemini, onOpenGemini = {}, recommended = recommended.audio, geminiButton = false)
                            GeminiSetUp(gemini, gemini.speech, onSetUpGemini)
                        }
                        Page.Rewrite -> {
                            PageHeader(PageText.REWRITE_TITLE, PageText.REWRITE_BODY)
                            StepOptions(RewriteOptions, pipeline.rewrite, onRewrite, available, gemini, onOpenGemini = {}, recommended = recommended.rewrite, geminiButton = false)
                            GeminiSetUp(gemini, gemini.rewrite, onSetUpGemini)
                        }
                        Page.Tools -> {
                            PageHeader(PageText.TOOLS_TITLE, PageText.TOOLS_BODY)
                            StepOptions(ToolOptions, pipeline.tools, onTools, available, gemini, onOpenGemini = {}, recommended = recommended.tools)
                        }
                        Page.Assistant -> {
                            PageHeader(PageText.ASSISTANT_TITLE, PageText.ASSISTANT_BODY)
                            Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
                                OutlinedButton(onClick = onOpenDefaultApps) { Text(PageText.OPEN_DEFAULT_APPS) }
                            }
                        }
                    }
                }
            }
            Dots(pages, page)
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Page.previous(page, assistantHeld)?.let { previous ->
                    OutlinedButton(onClick = { page = previous }, Modifier.height(56.dp)) { Text(PageText.BACK) }
                }
                Button(onClick = ::next, Modifier.weight(1f).height(56.dp), contentPadding = ButtonDefaults.MediumContentPadding) {
                    Text(when {
                        page == Page.Welcome -> "Start"
                        page == pages.last() -> PageText.DONE
                        else -> PageText.NEXT
                    }, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun PageHeader(title: String, body: String) {
    Column(Modifier.padding(horizontal = 32.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Gemini Nano still to download: the button that starts it (progress then shows on the option). */
@Composable
private fun GeminiSetUp(setup: GeminiSetup, part: Part, onSetUp: () -> Unit) {
    if (part != Part.NeedsDownload && part !is Part.Failed) return
    Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
        TextButton(onClick = onSetUp, enabled = !setup.downloading) { Text(PageText.SET_UP_GEMINI) }
    }
}

/** Where the user is among the pages. */
@Composable
private fun Dots(pages: List<Page>, page: Page) {
    Row(Modifier.fillMaxWidth().semantics { contentDescription = "Page ${pages.indexOf(page) + 1} of ${pages.size}" },
        horizontalArrangement = Arrangement.Center) {
        pages.forEach {
            Box(Modifier.padding(4.dp).size(if (it == page) 10.dp else 8.dp).clip(CircleShape)
                .background(if (it == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
        }
    }
}

/** Whether Ferb is the phone's assistant (Android's assistant role). */
internal fun assistantHeld(context: Context): Boolean =
    context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true

/** Android's default apps (where the assistant is picked), or Settings if this phone has no such page. */
internal fun openDefaultApps(context: Context) {
    val intents = listOf(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), Intent(Settings.ACTION_SETTINGS))
    for (intent in intents) {
        if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

/** [OnboardingFlow] on the settings' state: the steps as they are now, and Gemini Nano's and Ferb Link's. */
@Composable
fun Onboarding(settings: SettingsViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val pipeline by settings.pipeline.collectAsStateWithLifecycle()
    val gemini by settings.gemini.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { settings.checkGemini() }
    val available = rememberAvailability(settings.link, gemini)
    var held by rememberSaveable { mutableStateOf(assistantHeld(context)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        held = assistantHeld(context)
        onPauseOrDispose { }
    }
    OnboardingFlow(
        pipeline = pipeline,
        recommended = Pipeline.resolve(Saved(), dev.abhay.assistant.pipeline.Abilities(gemini.speech == Part.Ready, gemini.rewrite == Part.Ready)),
        available = available,
        gemini = gemini,
        assistantHeld = held,
        onAudio = settings::setAudio,
        onRewrite = settings::setRewrite,
        onTools = settings::setTools,
        onSetUpGemini = settings::setUpGemini,
        onOpenDefaultApps = { openDefaultApps(context) },
        onDone = onDone,
    )
}
