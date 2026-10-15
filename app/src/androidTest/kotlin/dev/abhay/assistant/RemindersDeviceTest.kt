package dev.abhay.assistant

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.notes.NoteText
import dev.abhay.assistant.reminders.FileReminderStore
import dev.abhay.assistant.reminders.ReminderAlarms
import dev.abhay.assistant.reminders.ReminderBook
import dev.abhay.assistant.reminders.ReminderReceiver
import java.io.File
import java.time.LocalDateTime
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reminders on the phone: exact alarms are allowed, a reminder's notification fires on time, Done
 * and Snooze work from it, and a reminder on a note writes its frontmatter through the real folder.
 * Test reminders are titled [TITLE] and removed afterwards.
 */
@RunWith(AndroidJUnit4::class)
class RemindersDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as AssistantApp
    private val notifications = context.getSystemService(NotificationManager::class.java)

    @After
    fun clean() {
        // A reminder left for the script to see fire later; it cleans up with "cleanup=1".
        if (InstrumentationRegistry.getArguments().getString("later") == "1") return
        app.reminders.all().filter { it.title == TITLE }.forEach { app.reminders.remove(it.id); notifications.cancel(it.id.hashCode()) }
        app.alarms.sync(app.reminders.all().filter { it.due.isAfter(LocalDateTime.now()) })
        File(context.cacheDir, "testdocs").deleteRecursively()
    }

    @Test
    fun exactAlarmsAreAllowed() {
        // USE_EXACT_ALARM: granted with the app on Android 13+, no settings page.
        assertThat(context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()).isTrue()
    }

    @Test
    fun aReminderFiresDoneAndSnooze() {
        assumeTrue("POST_NOTIFICATIONS granted (the script grants it)",
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        val reminder = app.reminders.add(TITLE, LocalDateTime.now().plusSeconds(8))!!
        app.alarms.sync(app.reminders.all().filter { it.due.isAfter(LocalDateTime.now()) })
        assertThat(waitFor(30_000) { notifications.activeNotifications.any { it.id == reminder.id.hashCode() } }).isTrue()

        // Snooze: the notification goes, and comes back in 10 minutes; the reminder keeps its time.
        ReminderAlarms.pending(context, ReminderReceiver.SNOOZE, reminder.id).send()
        assertThat(waitFor(10_000) { notifications.activeNotifications.none { it.id == reminder.id.hashCode() } }).isTrue()
        val snoozed = app.reminders.all().single { it.id == reminder.id }
        assertThat(snoozed.snoozed).isNotNull()
        assertThat(snoozed.at).isEqualTo(reminder.at)

        // Done: the reminder goes.
        ReminderAlarms.pending(context, ReminderReceiver.DONE, reminder.id).send()
        assertThat(waitFor(10_000) { app.reminders.all().none { it.id == reminder.id } }).isTrue()
    }

    @Test
    fun aReminderOnANoteWritesItsFrontmatter() {
        val notes = NotesDeviceTest.testNotes(context)
        val note = notes.create("Rent", "---\ntags: [money]\n---\nPay by transfer.\n")!!
        val book = ReminderBook(FileReminderStore(File(context.cacheDir, "reminders-devicetest.json")), notes) { LocalDateTime.now() }
        val r = book.add("Rent", LocalDateTime.of(2030, 1, 1, 9, 0), note = note)!!
        val text = notes.read(note)!!
        assertThat(NoteText.get(text, NoteText.REMIND)).isEqualTo("2030-01-01 09:00")
        assertThat(NoteText.get(text, NoteText.ID)).isEqualTo(r.id)
        assertThat(NoteText.body(text)).isEqualTo("Pay by transfer.\n")
        // Rebuilt from the folder alone, as after a reinstall.
        File(context.cacheDir, "reminders-devicetest.json").delete()
        assertThat(book.scan().map { it.id }).containsExactly(r.id)
        File(context.cacheDir, "reminders-devicetest.json").delete()
    }

    /** For the script: a reminder in a minute and a half, to fire after the process is killed or the app reinstalled. */
    @Test
    fun scheduleForLater() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("later") == "1")
        app.reminders.add(TITLE, LocalDateTime.now().plusSeconds(90))
        app.alarms.sync(app.reminders.all().filter { it.due.isAfter(LocalDateTime.now()) })
    }

    private fun waitFor(ms: Long, check: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (check()) return true
            Thread.sleep(250)
        }
        return check()
    }

    companion object {
        const val TITLE = "Ferb device test"
    }
}
