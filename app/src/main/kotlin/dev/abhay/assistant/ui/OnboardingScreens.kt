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
import dev.abhay.assistant.pipeline.Talk
import dev.abhay.assistant.voice.GeminiSetup
import dev.abhay.assistant.voice.Part

/** Each page in plain words. */
internal object PageText {
    const val TALK_TITLE = "How will you talk to Ferb?"
    const val TALK_BODY = "Tap the mic and say what you'd like done. Ferb asks for the microphone the first time."
    const val TALK_MORE = "More options in Settings → Advanced."
    const val ASSISTANT_TITLE = "Make Ferb your assistant"
    const val ASSISTANT_BODY = "Then hold the power button to open Ferb from anywhere. Pick Ferb as the digital assistant app. Ferb doesn't read your screen."
    const val START = "Start"
    const val SKIP = "Skip"
    const val NEXT = "Next"
    const val BACK = "Back"
    const val DONE = "Done"
    const val SET_UP_GEMINI = dev.abhay.assistant.ui.SET_UP_GEMINI
    const val OPEN_DEFAULT_APPS = "Open default apps"
}

/**
 * The first launch (and Settings → Run setup again), one page at a time: the welcome, Talk to
 * Ferb in (the recommended choice picked, and why one can't be used here), then the assistant
 * role. Skip ends it at once, keeping what's chosen; Next on Talk to Ferb in saves the choice
 * shown, so a default doesn't change under the user later. [talk] is null when steps were
 * changed under Advanced: then nothing is picked, and Next keeps them.
 */
@Composable
internal fun OnboardingFlow(
    talk: Talk?,
    can: Abilities,
    gemini: GeminiSetup,
    assistantHeld: Boolean,
    onTalk: (Talk) -> Unit,
    onSetUpGemini: () -> Unit,
    onOpenDefaultApps: () -> Unit,
    onDone: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(Page.Welcome) }
    val pages = Page.shown(assistantHeld)
    fun next() {
        if (page == Page.Talk) talk?.let(onTalk)
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
                        Page.Talk -> {
                            PageHeader(PageText.TALK_TITLE, PageText.TALK_BODY)
                            TalkOptions(talk, can, onTalk, recommended = Talk.default(can))
                            GeminiSetUp(gemini, onSetUpGemini)
                            Text(PageText.TALK_MORE, Modifier.padding(horizontal = 32.dp),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        page == Page.Welcome -> PageText.START
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

/** [OnboardingFlow] on the settings' state: Talk to Ferb in as it is now, and what this phone can use. */
@Composable
fun Onboarding(settings: SettingsViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val chosen by settings.chosen.collectAsStateWithLifecycle()
    val gemini by settings.gemini.collectAsStateWithLifecycle()
    val can = rememberAbilities(settings)
    var held by rememberSaveable { mutableStateOf(assistantHeld(context)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        held = assistantHeld(context)
        onPauseOrDispose { }
    }
    OnboardingFlow(
        talk = chosen.talk ?: if (chosen.custom) null else Talk.default(can),
        can = can,
        gemini = gemini,
        assistantHeld = held,
        onTalk = settings::setTalk,
        onSetUpGemini = settings::setUpGemini,
        onOpenDefaultApps = { openDefaultApps(context) },
        onDone = onDone,
    )
}
