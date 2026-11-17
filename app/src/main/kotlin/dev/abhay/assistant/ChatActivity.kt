package dev.abhay.assistant

import android.os.Bundle
import android.widget.Toast
import dev.abhay.assistant.ui.NotesScreen
import dev.abhay.assistant.ui.Screen
import dev.abhay.assistant.ui.Screen.Page
import dev.abhay.assistant.ui.NotesTab
import dev.abhay.assistant.ui.NewReminderScreen
import dev.abhay.assistant.ui.AdvancedScreen
import dev.abhay.assistant.ui.LicencesScreen
import dev.abhay.assistant.ui.PrivacyScreen
import dev.abhay.assistant.ui.GeminiScreen
import dev.abhay.assistant.ui.NavStack
import dev.abhay.assistant.ui.popped
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import dev.abhay.assistant.notes.NoteRef
import dev.abhay.assistant.notes.Obsidian
import androidx.compose.runtime.LaunchedEffect
import android.content.Intent
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.abhay.assistant.ui.AssistantScreen
import dev.abhay.assistant.ui.AssistantViewModel
import dev.abhay.assistant.ui.KeywordsScreen
import dev.abhay.assistant.ui.LinkScreen
import dev.abhay.assistant.ui.SettingsScreen
import dev.abhay.assistant.ui.SettingsViewModel
import dev.abhay.assistant.ui.ToolConfigScreen
import dev.abhay.assistant.ui.ToolsScreen
import dev.abhay.assistant.ui.Onboarding
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The assistant, and its settings: Settings → Tools → one tool's page → its keywords. The
 * navigation state is a small back stack of screen names ("tool:<name>" for a tool's page,
 * "keywords:<name>" for its keywords).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class ChatActivity : ComponentActivity() {
    /** A note to show, from an intent ([openNote]): its id and name. */
    private val noteToOpen = mutableStateOf<Pair<String, String>?>(null)

    /** A reminder's notification was tapped ([openReminders]): counted, so each one opens the Reminders tab. */
    private val remindersOpened = mutableStateOf(0)

    private var chat: AssistantViewModel? = null

    /** Out of sight, a recording of the chat's own goes (see [AssistantViewModel.leftScreen]). */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) chat?.leftScreen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    private fun read(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REMINDERS, false) == true) {
            remindersOpened.value++
            intent.removeExtra(EXTRA_REMINDERS)
        }
        val id = intent?.getStringExtra(EXTRA_NOTE_ID) ?: return
        noteToOpen.value = id to intent.getStringExtra(EXTRA_NOTE_NAME).orEmpty()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) read(intent)
        setContent {
            // minSdk 33, so the wallpaper-based dynamic colours are always available.
            val context = LocalContext.current
            val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            MaterialExpressiveTheme(colorScheme = colors, motionScheme = MotionScheme.expressive()) {
                val viewModel: AssistantViewModel = viewModel()
                chat = viewModel
                val settings: SettingsViewModel = viewModel()
                // The first launch (or after Settings → Run setup again or Show tips again): the welcome and the steps' pages first.
                val onboarding by viewModel.onboarding.state.collectAsStateWithLifecycle()
                // Welcome to the chat: Material's fade through, for screens with no spatial relation
                // (https://m3.material.io/styles/motion/transitions/transition-patterns).
                val motion = MaterialTheme.motionScheme
                AnimatedContent(onboarding.welcomed, transitionSpec = {
                    fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.defaultEffectsSpec())
                }) { welcomed ->
                if (!welcomed) {
                    Onboarding(settings, onDone = { viewModel.onboarding.welcomed() })
                    return@AnimatedContent
                }
                var stack by rememberSaveable(stateSaver = Screen.stackSaver(Page.Assistant)) { mutableStateOf(listOf<Screen>(Page.Assistant)) }
                val open = { screen: Screen -> stack = stack + screen }
                // A note opens in Obsidian (Ferb has no viewer of its own); without it, a short message.
                val showNote = { note: NoteRef ->
                    if (!Obsidian.open(this@ChatActivity, note)) {
                        // Installed but it still couldn't (no path in a vault): say that, not "Install Obsidian" (review P2-52).
                        val why = if (Obsidian.installed(this@ChatActivity)) Obsidian.CANT_OPEN else Obsidian.NOT_INSTALLED
                        Toast.makeText(this@ChatActivity, why, Toast.LENGTH_SHORT).show()
                    }
                }
                // "Open" on a list or note in the chat (or a reminder's notification): that note.
                val opening by noteToOpen
                LaunchedEffect(opening) {
                    opening?.let { (id, name) -> showNote(NoteRef(id, name, 0)) }
                    noteToOpen.value = null
                }
                // A reminder's notification, or a reply's "Reminders": the chat, then the Reminders tab over it.
                val fromNotification by remindersOpened
                LaunchedEffect(fromNotification) { if (fromNotification > 0 && stack.last() != Page.Reminders) stack = listOf(Page.Assistant, Page.Reminders) }
                val back = { stack = stack.popped() }
                NavStack(stack, onBack = back) { screen ->
                    // A screen's own back pops only itself (review P2-130).
                    @Suppress("NAME_SHADOWING") val back = { stack = stack.popped(screen) }
                    when (screen) {
                        // Setup is onboarding's, not the chat's: its root here is the chat.
                        Page.Assistant, Page.Setup -> AssistantScreen(viewModel, onOpenSettings = { open(Page.Settings) },
                            onOpenLink = { stack = stack + Page.Settings + Page.Link }, onOpenNotes = { open(Page.Notes) })
                        Page.Notes, Page.Reminders -> NotesScreen(viewModel, onOpen = { showNote(it) }, onBack = back,
                            tab = if (screen == Page.Reminders) NotesTab.Reminders else NotesTab.Notes,
                            // The page beneath becomes the Reminders tab, so Save or Back lands there.
                            onNewReminder = { stack = stack.dropLast(1) + Page.Reminders + Page.NewReminder })
                        Page.NewReminder -> NewReminderScreen(viewModel, onDone = back)
                        Page.Settings -> SettingsScreen(settings, onOpenTools = { open(Page.Tools) }, onOpenLink = { open(Page.Link) }, onOpenAdvanced = { open(Page.Advanced) }, onOpenBench = { open(Page.Bench) }, onOpenLicences = { open(Page.Licences) }, onOpenPrivacy = { open(Page.Privacy) }, onBack = back)
                        Page.Licences -> LicencesScreen(onBack = back)
                        Page.Privacy -> PrivacyScreen(onBack = back)
                        Page.Advanced -> AdvancedScreen(settings, onOpenGemini = { open(Page.Gemini) }, onBack = back)
                        Page.Gemini -> GeminiScreen(settings, onBack = back)
                        Page.Bench -> DebugBench.Screen(onBack = back)
                        Page.Link -> LinkScreen(settings, onBack = back)
                        Page.Tools -> ToolsScreen(settings, onOpenTool = { open(Screen.Tool(it)) }, onBack = back)
                        is Screen.Keywords -> KeywordsScreen(settings, screen.name, onBack = back)
                        // "Try it" on a tool's page: back to the chat, and send the example.
                        is Screen.Tool -> ToolConfigScreen(settings, screen.name, onOpenKeywords = { open(Screen.Keywords(screen.name)) }, onTry = { example ->
                            stack = listOf(Page.Assistant)
                            viewModel.send(example)
                        }, onBack = back)
                    }
                }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_REMINDERS = "reminders"
        private const val EXTRA_NOTE_ID = "note_id"
        private const val EXTRA_NOTE_NAME = "note_name"

        /** Opens Ferb on the Reminders tab (a reminder's notification). */
        fun openReminders(context: Context): Intent = Intent(context, ChatActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_REMINDERS, true)

        /** Opens [note] in Obsidian, through Ferb (a reply's Open). */
        fun openNote(context: Context, note: NoteRef): Intent = Intent(context, ChatActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_NOTE_ID, note.id).putExtra(EXTRA_NOTE_NAME, note.name)
    }
}
