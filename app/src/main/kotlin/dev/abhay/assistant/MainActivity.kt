package dev.abhay.assistant

import android.os.Bundle
import android.widget.Toast
import dev.abhay.assistant.ui.NotesScreen
import dev.abhay.assistant.ui.NotesTab
import dev.abhay.assistant.ui.NewReminderScreen
import dev.abhay.assistant.ui.NavStack
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
import dev.abhay.assistant.ui.WelcomeScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The assistant, and its settings: Settings → Tools → one tool's page → its keywords. The
 * navigation state is a small back stack of screen names ("tool:<name>" for a tool's page,
 * "keywords:<name>" for its keywords).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class MainActivity : ComponentActivity() {
    /** A note to show, from an intent ([openNote]): its id and name. */
    private val noteToOpen = mutableStateOf<Pair<String, String>?>(null)

    /** A reminder's notification was tapped ([openReminders]): counted, so each one opens the Reminders tab. */
    private val remindersOpened = mutableStateOf(0)

    /** Opened as the phone's assistant ([Intent.ACTION_ASSIST]): counted, so each one brings up the chat ready to type. */
    private val assists = mutableStateOf(0)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    private fun read(intent: Intent?) {
        // The assist trigger (holding the power button, the corner swipe): its extras, the screen's
        // context, aren't read.
        if (intent?.action == Intent.ACTION_ASSIST) assists.value++
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
                val settings: SettingsViewModel = viewModel()
                // The first launch (or after Settings → Show tips again): the welcome screen first.
                val onboarding by viewModel.onboarding.state.collectAsStateWithLifecycle()
                // Welcome to the chat: Material's fade through, for screens with no spatial relation
                // (https://m3.material.io/styles/motion/transitions/transition-patterns).
                val motion = MaterialTheme.motionScheme
                AnimatedContent(onboarding.welcomed, transitionSpec = {
                    fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.defaultEffectsSpec())
                }) { welcomed ->
                if (!welcomed) {
                    WelcomeScreen(onStart = { viewModel.onboarding.welcomed() })
                    return@AnimatedContent
                }
                var stack by rememberSaveable { mutableStateOf(listOf("assistant")) }
                val open = { screen: String -> stack = stack + screen }
                // A note opens in Obsidian (Ferb has no viewer of its own); without it, a short message.
                val showNote = { note: NoteRef ->
                    if (!Obsidian.open(this@MainActivity, note)) {
                        Toast.makeText(this@MainActivity, Obsidian.NOT_INSTALLED, Toast.LENGTH_SHORT).show()
                    }
                }
                // "Open" on a list or note in the chat (or a reminder's notification): that note.
                val opening by noteToOpen
                LaunchedEffect(opening) {
                    opening?.let { (id, name) -> showNote(NoteRef(id, name, 0)) }
                    noteToOpen.value = null
                }
                // The assistant trigger: back to the chat, from wherever Ferb was.
                val assisted by assists
                LaunchedEffect(assisted) { if (assisted > 0) stack = listOf("assistant") }
                // A reply's "Reminders": the Notes screen on that tab, forward as any screen opens.
                LaunchedEffect(Unit) { viewModel.opens.collect { screen -> if (stack.last() != screen) stack = stack + screen } }
                // A reminder's notification: the chat, then the Reminders tab over it.
                val fromNotification by remindersOpened
                LaunchedEffect(fromNotification) { if (fromNotification > 0 && stack.last() != REMINDERS) stack = listOf("assistant", REMINDERS) }
                val back = { stack = stack.dropLast(1) }
                NavStack(stack, onBack = back) { screen ->
                    when {
                        screen == "assistant" -> AssistantScreen(viewModel, assists = assisted, onOpenSettings = { open("settings") },
                            onOpenLink = { stack = stack + "settings" + "link" }, onOpenNotes = { open("notes") })
                        screen == "notes" || screen == REMINDERS -> NotesScreen(viewModel, onOpen = { showNote(it) }, onBack = back,
                            tab = if (screen == REMINDERS) NotesTab.Reminders else NotesTab.Notes,
                            // The page beneath becomes the Reminders tab, so Save or Back lands there.
                            onNewReminder = { stack = stack.dropLast(1) + REMINDERS + NEW_REMINDER })
                        screen == NEW_REMINDER -> NewReminderScreen(viewModel, onDone = back)
                        screen == "settings" -> SettingsScreen(settings, onOpenTools = { open("tools") }, onOpenLink = { open("link") }, onBack = back)
                        screen == "link" -> LinkScreen(settings, onBack = back)
                        screen == "tools" -> ToolsScreen(settings, onOpenTool = { open("tool:$it") }, onBack = back)
                        screen.startsWith("keywords:") -> KeywordsScreen(settings, screen.removePrefix("keywords:"), onBack = back)
                        // "Try it" on a tool's page: back to the chat, and send the example.
                        else -> ToolConfigScreen(settings, screen.removePrefix("tool:"), onOpenKeywords = { open("keywords:" + screen.removePrefix("tool:")) }, onTry = { example ->
                            stack = listOf("assistant")
                            viewModel.send(example)
                        }, onBack = back)
                    }
                }
                }
            }
        }
    }

    companion object {
        /** The Notes screen opened on its Reminders tab. */
        const val REMINDERS = "reminders"
        const val NEW_REMINDER = "new_reminder"
        private const val EXTRA_REMINDERS = "reminders"
        private const val EXTRA_NOTE_ID = "note_id"
        private const val EXTRA_NOTE_NAME = "note_name"

        /** Opens Ferb on the Reminders tab (a reminder's notification). */
        fun openReminders(context: Context): Intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_REMINDERS, true)

        /** Opens [note] in Obsidian, through Ferb (a reply's Open). */
        fun openNote(context: Context, note: NoteRef): Intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_NOTE_ID, note.id).putExtra(EXTRA_NOTE_NAME, note.name)
    }
}
