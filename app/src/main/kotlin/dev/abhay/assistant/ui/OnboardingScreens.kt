package dev.abhay.assistant.ui

import dev.abhay.assistant.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
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
import dev.abhay.assistant.pipeline.Abilities
import dev.abhay.assistant.pipeline.ModelChoice
import dev.abhay.assistant.voice.GeminiSetup
import dev.abhay.assistant.voice.Part

/** Each page in plain words (string resources). */
internal object PageText {
    val MODEL_TITLE = R.string.onboarding_model_title
    val TOOLS_TITLE = R.string.onboarding_tools_title
    val OPEN_TOOLS = R.string.onboarding_open_tools
    val ASSISTANT_TITLE = R.string.onboarding_assistant_title
    val START = R.string.onboarding_start
    val SKIP = R.string.onboarding_skip
    val NEXT = R.string.onboarding_next
    val DOWNLOAD = R.string.onboarding_download
    val BACK = R.string.onboarding_back
    val DONE = R.string.onboarding_done
    val SET_UP_GEMINI = dev.abhay.assistant.ui.SET_UP_GEMINI
    val OPEN_ASSISTANT_SETTINGS = R.string.onboarding_open_assistant_settings
    val IS_ASSISTANT = R.string.onboarding_is_assistant
}

/**
 * The first launch (and Settings → Run setup again), one page at a time: the welcome, the model
 * (the recommended one picked; a greyed one says why in a few words), the tools, then the
 * assistant role. Setup carries no explanations: titles, buttons and the status needed to act
 * (those live in Settings). Skip ends it at once, keeping what's chosen. Next on the model page
 * saves the one shown ([onKeepModel]), so a default doesn't change under the user later; with
 * Gemini Nano shown and still to download, the button says Download, and also starts it
 * ([onSetUpGemini]). [model] is null when steps were changed under Advanced: then nothing is
 * picked, and Next keeps them. The tools
 * page opens Settings → Tools ([onOpenTools]); back from there is this page again, so the page
 * is held outside ([pageState]), where it outlives the trip. The assistant page opens
 * the phone's assistant setting ([openAssistantSettings]) and, back in Ferb, says once it's set.
 */
@Composable
internal fun OnboardingFlow(
    model: ModelChoice?,
    can: Abilities,
    gemini: GeminiSetup,
    assistantHeld: Boolean,
    onModel: (ModelChoice) -> Unit,
    onSetUpGemini: () -> Unit,
    onOpenAssistantSettings: () -> Unit,
    onCheckGemini: () -> Unit = {},
    onDone: () -> Unit,
    geminiWatch: dev.abhay.assistant.voice.GeminiWatch = dev.abhay.assistant.voice.GeminiWatch.Off,
    onKeepModel: (ModelChoice) -> Unit = onModel,
    onOpenLink: (() -> Unit)? = null,
    onOpenTools: () -> Unit = {},
    pageState: MutableState<Page> = rememberSaveable { mutableStateOf(Page.Welcome) },
) {
    var page by pageState
    // The pages as at the start: the assistant page stays once Ferb is made the assistant from it, to say so.
    val heldAtStart by rememberSaveable { mutableStateOf(assistantHeld) }
    val pages = Page.shown(heldAtStart)
    // Gemini Nano picked and still to download: the button says Download, starts it and moves on.
    val download = page == Page.Model && model == ModelChoice.Gemini && can.geminiHere && gemini.needsSetUp
    fun next() {
        if (page == Page.Model) model?.let(onKeepModel)
        if (download) onSetUpGemini()
        Page.next(page, heldAtStart)?.let { page = it } ?: onDone()
    }
    Page.previous(page, heldAtStart)?.let { previous -> BackHandler { page = previous } }
    val motion = MaterialTheme.motionScheme
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            // Skip, top right, on every page but the last.
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (page != pages.last()) TextButton(onClick = onDone) { Text(stringResource(PageText.SKIP)) }
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
                        Page.Model -> {
                            PageHeader(stringResource(PageText.MODEL_TITLE))
                            ModelOptions(model, can, onModel, gemini, onSetUpGemini, onCheckGemini, onOpenLink,
                                showReady = true, watch = geminiWatch, brief = true)
                        }
                        Page.Tools -> {
                            PageHeader(stringResource(PageText.TOOLS_TITLE))
                            FilledTonalButton(onClick = onOpenTools, Modifier.padding(horizontal = 32.dp)) { Text(stringResource(PageText.OPEN_TOOLS)) }
                        }
                        Page.Assistant -> {
                            PageHeader(stringResource(PageText.ASSISTANT_TITLE))
                            Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (assistantHeld) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text(stringResource(PageText.IS_ASSISTANT), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                } else {
                                    FilledTonalButton(onClick = onOpenAssistantSettings) { Text(stringResource(PageText.OPEN_ASSISTANT_SETTINGS)) }
                                }
                            }
                        }
                    }
                }
            }
            Dots(pages, page)
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Page.previous(page, assistantHeld)?.let { previous ->
                    OutlinedButton(onClick = { page = previous }, Modifier.height(56.dp)) { Text(stringResource(PageText.BACK)) }
                }
                Button(onClick = ::next, Modifier.weight(1f).height(56.dp), contentPadding = ButtonDefaults.MediumContentPadding) {
                    Text(when {
                        page == Page.Welcome -> stringResource(PageText.START)
                        download -> stringResource(PageText.DOWNLOAD)
                        page == pages.last() -> stringResource(PageText.DONE)
                        else -> stringResource(PageText.NEXT)
                    }, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun PageHeader(title: String) {
    Text(title, Modifier.padding(horizontal = 32.dp, vertical = 8.dp), style = MaterialTheme.typography.headlineSmall)
}

/** Where the user is among the pages. */
@Composable
private fun Dots(pages: List<Page>, page: Page) {
    val where = stringResource(R.string.onboarding_page_s_of_s, pages.indexOf(page) + 1, pages.size)
    Row(Modifier.fillMaxWidth().semantics { contentDescription = where },
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

/**
 * Where the phone's assistant is picked, the most direct page this phone allows:
 * 1. voice input settings, which on Android 17 (Pixel) is the "Digital assistant app" page itself
 *    (the permission controller's AssistantSettingsActivity), and on older ones the page holding it;
 * 2. Default apps, asked to highlight the assistant row (Settings' fragment-args extras; ignored
 *    where unsupported); the page that picks one role directly (MANAGE_DEFAULT_APP) is for system apps only;
 * 3. Settings.
 * The assistant role can't be requested from a dialog, so the user picks Ferb there and comes back.
 */
internal fun assistantSettingsIntents(): List<Intent> = listOf(
    Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
    Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        .putExtra(FRAGMENT_ARGS_KEY, RoleManager.ROLE_ASSISTANT)
        .putExtra(SHOW_FRAGMENT_ARGS, android.os.Bundle().apply { putString(FRAGMENT_ARGS_KEY, RoleManager.ROLE_ASSISTANT) }),
    Intent(Settings.ACTION_SETTINGS),
)

private const val FRAGMENT_ARGS_KEY = ":settings:fragment_args_key"
private const val SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"

/** Opens the first of [assistantSettingsIntents] this phone has. */
internal fun openAssistantSettings(context: Context) {
    for (intent in assistantSettingsIntents()) {
        if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

/**
 * [OnboardingFlow] on the settings' state: the model choice as it is now, and what this phone can
 * use. The tools page's button opens Settings → Tools (and each tool's page) over it; back returns
 * to the tools page. A tool's page has no "Try it" here: it would end setup before the assistant page.
 */
@Composable
fun Onboarding(settings: SettingsViewModel, onDone: () -> Unit) {
    val page = rememberSaveable { mutableStateOf(Page.Welcome) }
    var stack by rememberSaveable { mutableStateOf(listOf(SETUP)) }
    val open = { screen: String -> stack = stack + screen }
    val back = { stack = stack.dropLast(1) }
    NavStack(stack, onBack = back) { screen ->
        when {
            screen == SETUP -> SetupPages(settings, onDone, page, onOpenTools = { open("tools") })
            screen == "tools" -> ToolsScreen(settings, onOpenTool = { open("tool:$it") }, onBack = back)
            screen.startsWith("keywords:") -> KeywordsScreen(settings, screen.removePrefix("keywords:"), onBack = back)
            else -> ToolConfigScreen(settings, screen.removePrefix("tool:"), onOpenKeywords = { open("keywords:" + screen.removePrefix("tool:")) },
                onTry = null, onBack = back)
        }
    }
}

private const val SETUP = "setup"

@Composable
private fun SetupPages(settings: SettingsViewModel, onDone: () -> Unit, page: MutableState<Page>, onOpenTools: () -> Unit) {
    val context = LocalContext.current
    val chosen by settings.chosen.collectAsStateWithLifecycle()
    val gemini by settings.gemini.collectAsStateWithLifecycle()
    val watch by settings.geminiWatch.collectAsStateWithLifecycle()
    val can = rememberAbilities(settings)
    var held by rememberSaveable { mutableStateOf(assistantHeld(context)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        held = assistantHeld(context)
        onPauseOrDispose { }
    }
    OnboardingFlow(
        model = chosen.model ?: if (chosen.custom) null else ModelChoice.default(can),
        can = can,
        gemini = gemini,
        assistantHeld = held,
        // A tap only picks: the Download button starts Gemini Nano's download.
        onModel = { settings.setModel(it, download = false) },
        onKeepModel = { settings.setModel(it, download = false) },
        onOpenLink = rememberOpenLink(settings.link),
        onSetUpGemini = settings::setUpGemini,
        onCheckGemini = settings::checkGemini,
        onOpenAssistantSettings = { openAssistantSettings(context) },
        onDone = onDone,
        geminiWatch = watch,
        onOpenTools = onOpenTools,
        pageState = page,
    )
}
