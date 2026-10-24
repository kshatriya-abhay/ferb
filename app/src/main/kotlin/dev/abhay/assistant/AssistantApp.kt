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

    /** How Ferb understands a request: audio processing, rewrite, tool selection (Settings). */
    val pipeline by lazy { dev.abhay.assistant.pipeline.PipelineSettings(this) }
    /** Gemini Nano's speech recognition (POC), when picked in Settings. */
    val nanoEars by lazy { dev.abhay.assistant.voice.NanoEars() }
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
