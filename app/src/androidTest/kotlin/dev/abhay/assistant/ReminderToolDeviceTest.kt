package dev.abhay.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.tools.AddReminder
import dev.abhay.assistant.tools.Outcome
import dev.abhay.assistant.tools.PrepareEnv
import dev.abhay.assistant.tools.Prepared
import dev.abhay.assistant.reminders.Repeat
import java.time.Duration
import java.time.LocalDateTime
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A reminder through Ferb's real path, in the background: the add_reminder tool, as a reply runs
 * it, about a minute ahead and daily. Its exact alarm is in `dumpsys alarm`, its notification
 * posts on time with its title, tapping it opens Ferb, and once it has fired the next day's alarm
 * is scheduled. No note: nothing is written in the notes folder. Removed afterwards.
 */
@RunWith(AndroidJUnit4::class)
class ReminderToolDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as AssistantApp
    private val notifications = context.getSystemService(NotificationManager::class.java)

    @After
    fun clean() {
        app.reminders.all().filter { it.title == TITLE }.forEach { app.reminders.remove(it.id); notifications.cancel(it.id.hashCode()) }
        app.alarms.sync(app.reminders.all().filter { it.due.isAfter(LocalDateTime.now()) })
    }

    @Test
    fun aDailyReminderThroughTheToolFiresOnTimeAndComesBack() {
        assumeTrue("POST_NOTIFICATIONS granted", context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        val request = "Remind me in 1 minute about $TITLE every day"
        val env = PrepareEnv(notes = app.notes, reminders = app.reminders, remindersChanged = app::syncReminders,
            granted = { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })
        val ready = AddReminder.prepare(AddReminder.fromRequest(mapOf("text" to TITLE, "time" to "in 1 minute", "repeat" to "daily"), request), env)
        assertThat(ready).isInstanceOf(Prepared.Ready::class.java)
        val outcome = (ready as Prepared.Ready).run(context)
        log("tool: ${ready.summary} → $outcome")
        assertThat(outcome).isInstanceOf(Outcome.Done::class.java)
        val reminder = app.reminders.all().single { it.title == TITLE }
        assertThat(reminder.repeat).isEqualTo(Repeat.Daily)

        // The exact alarm (syncReminders runs on a thread of its own).
        assertThat(waitFor(10_000) { alarmFor(reminder.id) != null }).isTrue()
        log("alarm: ${alarmFor(reminder.id)}")
        assertThat(alarmFor(reminder.id)).contains("window=0") // exact: no window

        // The notification, on time, with its title.
        assertThat(waitFor(120_000) { posted(reminder.id) != null }).isTrue()
        val firedAt = LocalDateTime.now()
        val n = posted(reminder.id)!!
        val late = Duration.between(reminder.at, firedAt).toMillis()
        log("notification: '${n.extras.getString(Notification.EXTRA_TITLE)}' / '${n.extras.getString(Notification.EXTRA_TEXT)}', $late ms after ${reminder.at}; tap opens an activity: ${n.contentIntent.isActivity}, of ${n.contentIntent.creatorPackage}")
        assertThat(n.extras.getString(Notification.EXTRA_TITLE)).isEqualTo(TITLE)
        assertThat(late).isLessThan(15_000)
        assertThat(n.contentIntent.isActivity).isTrue()
        assertThat(n.contentIntent.creatorPackage).isEqualTo(context.packageName)
        val notif = shell("dumpsys notification --noredact").lines().filter { TITLE in it || "dev.abhay.assistant" in it && "pkg=" in it }.take(4)
        log("dumpsys notification: $notif")

        // A daily reminder that fired is due again tomorrow, without Done, its alarm set: within a
        // second of the notification, before anyone could tap Done (the log shows any tap).
        assertThat(waitFor(1_000) { app.reminders.all().single { it.id == reminder.id }.at.isAfter(LocalDateTime.now()) }).isTrue()
        val next = app.reminders.all().single { it.id == reminder.id }
        log("after firing: at ${next.at}, alarm ${alarmFor(reminder.id)}")
        assertThat(next.at).isEqualTo(reminder.at.plusDays(1))
        assertThat(waitFor(10_000) { alarmFor(reminder.id) != null }).isTrue()
    }

    /** The reminder's alarm in `dumpsys alarm`: the line after its ferb-reminder:// intent, or null. */
    private fun alarmFor(id: String): String? {
        val lines = shell("dumpsys alarm").lines()
        val i = lines.indexOfFirst { "ferb-reminder://$id" in it || ("dev.abhay.assistant" in it && "RTC_WAKEUP" in it && id in it) }
        if (i >= 0) return lines.subList(maxOf(0, i - 2), minOf(lines.size, i + 3)).joinToString(" | ") { it.trim() }
        // dumpsys doesn't print intent data: the package's RTC_WAKEUP alarms with their tag.
        return lines.withIndex().filter { (_, l) -> "RTC_WAKEUP" in l && "dev.abhay.assistant" in l }
            .joinToString(" || ") { (j, _) -> lines.subList(j, minOf(lines.size, j + 4)).joinToString(" | ") { it.trim() } }.ifEmpty { null }
            ?.takeIf { "reminder.FIRE" in it }
    }

    private fun posted(id: String) = notifications.activeNotifications.firstOrNull { it.id == id.hashCode() }?.notification

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().readText()

    private fun log(text: String) = Log.i("ReminderToolDeviceTest", text)

    private fun waitFor(ms: Long, check: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (check()) return true
            Thread.sleep(250)
        }
        return check()
    }

    companion object {
        const val TITLE = "Ferb reminder test"
    }
}
