package dev.abhay.assistant

import android.app.Application
import dev.abhay.assistant.engine.AssistantEngine
import dev.abhay.assistant.tools.CalendarBook
import dev.abhay.assistant.tools.ContactBook
import dev.abhay.assistant.tools.InstalledApps
import dev.abhay.assistant.tools.ToolSettings
import dev.abhay.assistant.link.FerbLink
import dev.abhay.assistant.notes.SafNotes
import dev.abhay.assistant.reminders.FileReminderStore
import dev.abhay.assistant.reminders.ReminderAlarms
import dev.abhay.assistant.reminders.ReminderBook
import dev.abhay.assistant.onboarding.Onboarding
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** One model, one tool configuration, one app list, one contact book and one calendar per process, shared by the screens. */
class AssistantApp : Application() {
    val engine by lazy { AssistantEngine(this) }
    val toolSettings by lazy { ToolSettings(this) }
    val apps by lazy { InstalledApps.from(this) }
    val contacts by lazy { ContactBook.from(this) }
    val calendar by lazy { CalendarBook.from(this) }
    val link by lazy { FerbLink(this) }
    val onboarding by lazy { Onboarding.from(this) }
    val notes by lazy { SafNotes(this) }
    /**
     * The chat, for the process: the full chat and the assistant's sheet over another app show the
     * same conversation, so opening the chat from the sheet carries it on.
     */
    val chat by lazy { kotlinx.coroutines.flow.MutableStateFlow(dev.abhay.assistant.ui.AssistantViewModel.UiState()) }
    val exchangeIds = java.util.concurrent.atomic.AtomicLong()
    /**
     * Where a request's calls run, for the process, one at a time ([callsRunning]): not a screen's,
     * so closing the sheet while the clock app is handing back doesn't cancel the calls after it.
     */
    val calls = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    val callsRunning = kotlinx.coroutines.sync.Mutex()

    /** How Ferb understands a request: audio processing, rewrite, tool selection (Settings). */
    val pipeline by lazy {
        dev.abhay.assistant.pipeline.PipelineSettings(this) {
            (link.status() as? dev.abhay.assistant.link.LinkStatus.Ready).let { (it != null) to (it?.audio == true) }
        }
    }
    /** Gemini Nano's speech recognition (POC), when picked in Settings. */
    val nanoEars by lazy { dev.abhay.assistant.voice.NanoEars(foreground.inFront) }

    private val geminiFollow = java.util.concurrent.atomic.AtomicBoolean()
    /**
     * From the first check on, the steps follow Gemini Nano's state: each part counts once AICore
     * has answered, so Ferb works on what's there while a download runs and switches to Gemini
     * Nano the moment it's ready. Each time Ferb comes back to the front it checks again, and when
     * a download it waited for is ready, the chat says so ([GEMINI_READY]). Started by the first
     * screen that checks it; once per process.
     */
    fun followGemini() {
        if (!geminiFollow.compareAndSet(false, true)) return
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
        scope.launch {
            nanoEars.setup
                .filter { s -> s.parts.none { it == dev.abhay.assistant.voice.Part.Checking } }
                .map { s -> dev.abhay.assistant.voice.GeminiSetup.abilities(s) }
                .distinctUntilChanged()
                .collect { (speech, rewrite, here) -> pipeline.geminiChecked(speech, rewrite, here) }
        }
        scope.launch {
            // Coming back, not the resume that's on now (its screen has just checked).
            foreground.inFront.drop(1).filter { it }.collect { nanoEars.resumed() }
        }
        scope.launch {
            nanoEars.watch.drop(1).filter { it == dev.abhay.assistant.voice.GeminiWatch.Ready }
                .collect { chat.update { it.copy(notice = GEMINI_READY) } }
        }
    }
    val reminders by lazy { ReminderBook(FileReminderStore(java.io.File(filesDir, "reminders.json"))) { java.time.LocalDateTime.now() } }
    val alarms by lazy { ReminderAlarms(this) }

    /** The reminders scheduled from Ferb's store: on launch, and after any change. */
    fun syncReminders() {
        kotlin.concurrent.thread {
            migrateReminders()
            dev.abhay.assistant.reminders.ReminderReceiver.showMissed(this, this)
            reminders.catchUp()
            reminders.prune()
            alarms.sync(reminders.upcoming())
        }
    }
    /**
     * Once, silently: reminders that lived on notes (until 2026-10-23) read into Ferb's store
     * ([dev.abhay.assistant.reminders.ReminderMigration]). The notes are only read. Blocking.
     */
    @Synchronized
    fun migrateReminders() {
        val prefs = getSharedPreferences("reminders", MODE_PRIVATE)
        if (prefs.getBoolean(FROM_NOTES, false)) return
        reminders.import(dev.abhay.assistant.reminders.ReminderMigration.fromNotes(notes, java.time.LocalDateTime.now()))
        prefs.edit().putBoolean(FROM_NOTES, true).commit()
    }

    /** Clears a reminder (the Reminders tab): out of the store, its alarm cancelled and its notification taken down. Blocking. */
    fun clearReminder(id: String): dev.abhay.assistant.reminders.Reminder? = reminders.clear(id)?.also {
        dev.abhay.assistant.reminders.ReminderReceiver.cancel(this, id)
        alarms.sync(reminders.upcoming())
    }

    /** Takes an entry off the Reminders tab's Recent ([dev.abhay.assistant.reminders.ReminderBook.dismiss]). Blocking. */
    fun dismissReminder(id: String): dev.abhay.assistant.reminders.Reminder? = reminders.dismiss(id)?.also {
        alarms.sync(reminders.upcoming())
    }

    /** Undo for [clearReminder] and [dismissReminder]: the reminder and its alarm back. Blocking. */
    fun restoreReminder(reminder: dev.abhay.assistant.reminders.Reminder) {
        reminders.restore(reminder)
        alarms.sync(reminders.upcoming())
    }

    lateinit var foreground: ForegroundTracker
        private set

    override fun onCreate() {
        super.onCreate()
        // Before any activity starts, so the first resume is seen.
        foreground = ForegroundTracker(this)
        syncReminders()
    }
}

private const val FROM_NOTES = "imported_from_notes"

/** The chat's note when a Gemini Nano download Ferb waited for is ready. */
const val GEMINI_READY = "Gemini Nano is ready. Ferb now understands English + Hindi on this phone."
