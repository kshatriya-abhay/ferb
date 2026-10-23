package dev.abhay.assistant.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import dev.abhay.assistant.AssistantApp
import dev.abhay.assistant.MainActivity
import dev.abhay.assistant.R
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * Schedules the reminders' notifications with exact alarms (`USE_EXACT_ALARM`, granted with the
 * app on Android 13+ for reminders), one per reminder, cancelling those that went; and a daily
 * rescan of the notes folder. All local: no network.
 */
class ReminderAlarms(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val prefs = context.getSharedPreferences("reminder_alarms", Context.MODE_PRIVATE)

    fun sync(reminders: List<Reminder>) {
        val scheduled = prefs.getStringSet(SCHEDULED, emptySet()).orEmpty()
        (scheduled - reminders.map { it.id }.toSet()).forEach { alarms.cancel(pending(context, ReminderReceiver.FIRE, it)) }
        reminders.forEach { r ->
            val at = r.due.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val intent = pending(context, ReminderReceiver.FIRE, r.id)
            if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
        prefs.edit().putStringSet(SCHEDULED, reminders.map { it.id }.toSet()).apply()
        // The daily rescan of the notes folder, set until reminders left notes (2026-10-23): gone.
        alarms.cancel(pending(context, ReminderReceiver.RESCAN, "scan"))
    }

    companion object {
        private const val SCHEDULED = "scheduled"
        const val CHANNEL = "reminders"

        fun pending(context: Context, action: String, id: String): PendingIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, ReminderReceiver::class.java).setAction(action).setData(Uri.parse("ferb-reminder://$id")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** Fires a reminder's notification, and handles its Done and Snooze, a reboot, and the daily rescan. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as AssistantApp
        val id = intent.data?.host
        val result = goAsync()
        // The notes folder may be read (SAF), so off the main thread.
        thread {
            try {
                when (intent.action) {
                    FIRE -> app.reminders.all().firstOrNull { it.id == id }?.let {
                        notify(context, it, late = false)
                        // A repeat comes back at its next time, Done tapped or not.
                        app.reminders.fired(it.id)
                    }
                    DONE -> id?.let { app.reminders.done(it); cancel(context, it) }
                    SNOOZE -> id?.let { app.reminders.snooze(it); cancel(context, it) }
                    else -> {
                        // Boot, update: what was missed meanwhile, then every alarm again, from the store.
                        app.migrateReminders()
                        app.reminders.catchUp()
                        showMissed(context, app)
                    }
                }
                // One-offs over for a day leave the store.
                app.reminders.prune()
                app.alarms.sync(app.reminders.upcoming())
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        /** Takes [id]'s notification down, if it's showing. */
        fun cancel(context: Context, id: String) = context.getSystemService(NotificationManager::class.java).cancel(id.hashCode())

        const val FIRE = "dev.abhay.assistant.reminder.FIRE"
        const val DONE = "dev.abhay.assistant.reminder.DONE"
        const val SNOOZE = "dev.abhay.assistant.reminder.SNOOZE"
        const val RESCAN = "dev.abhay.assistant.reminder.RESCAN"

        /**
         * One-offs due while the phone was off (or Ferb's alarms were cleared): shown now, late,
         * with the time they were due, and marked fired so they never show twice.
         */
        fun showMissed(context: Context, app: AssistantApp) {
            app.reminders.missed().forEach { if (notify(context, it, late = true)) app.reminders.fired(it.id) }
        }

        /** Shows [reminder]'s notification; [late]: "Due 9:00 am". False without the permission. */
        fun notify(context: Context, reminder: Reminder, late: Boolean): Boolean {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(ReminderAlarms.CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
            // Tapped: Ferb's Reminders tab.
            val target = MainActivity.openReminders(context)
            val open = PendingIntent.getActivity(context, reminder.id.hashCode(), target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(context, ReminderAlarms.CHANNEL)
                .setSmallIcon(R.drawable.ic_reminder)
                .setContentTitle(reminder.title)
                .setContentText(if (late) ReminderTime.due(reminder.at, LocalDateTime.now()) else "Reminder")
                .apply { if (late) setWhen(reminder.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()).setShowWhen(true) }
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(0, "Done", ReminderAlarms.pending(context, DONE, reminder.id))
                .addAction(0, "Snooze ${ReminderBook.SNOOZE_MINUTES} min", ReminderAlarms.pending(context, SNOOZE, reminder.id))
                .build()
            manager.notify(reminder.id.hashCode(), notification)
            return true
        }
    }
}
