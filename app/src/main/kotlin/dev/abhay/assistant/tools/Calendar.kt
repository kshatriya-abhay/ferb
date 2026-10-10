package dev.abhay.assistant.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * One of the phone's calendars. [writable]: events can be added to it; [primary]: the account's
 * own calendar, where new events go unless the user picks another.
 */
data class CalendarInfo(val id: Long, val name: String, val account: String, val writable: Boolean, val primary: Boolean = false, val local: Boolean = false)

/** One occurrence of an event (a repeating event has one per day it's on), times in epoch milliseconds. */
data class CalendarEvent(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean = false, val calendar: String = "", val location: String? = null)

/**
 * The phone's calendars and their events, as Android's calendar provider has them (synced by
 * Google Calendar and others, so this app stays offline), read on every lookup; null without the
 * calendar permission.
 */
class CalendarBook(
    private val loadCalendars: () -> List<CalendarInfo>?,
    private val loadEvents: (from: Long, to: Long) -> List<CalendarEvent>?,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** The calendars shown in the calendar app. */
    fun calendars(): List<CalendarInfo>? = loadCalendars()

    /**
     * Where a new event goes: the calendar [chosen] (its id, from the tool's settings) if it can
     * still take events, else the account's own (primary) one, else the first that can.
     */
    fun target(chosen: String?): CalendarInfo? {
        val writable = calendars()?.filter { it.writable } ?: return null
        return writable.firstOrNull { it.id.toString() == chosen }
            ?: writable.firstOrNull { it.primary }
            ?: writable.firstOrNull { !it.local }
            ?: writable.firstOrNull()
    }

    /** The events on [day], all-day ones first, then by start; null without the permission. */
    fun day(day: LocalDate): List<CalendarEvent>? {
        // A day either side: all-day events are stored in UTC, which isn't the day here.
        val from = day.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        return loadEvents(from, to)?.let { onDay(it, day, zone) }
    }

    /** The timed events that overlap [begin]..[end] (epoch milliseconds), for a clash warning. */
    fun overlapping(begin: Long, end: Long): List<CalendarEvent>? =
        loadEvents(begin, end)?.filter { !it.allDay && it.begin < end && it.end > begin }?.sortedBy { it.begin }

    companion object {
        val NONE = CalendarBook({ null }, { _, _ -> null })

        fun canRead(context: Context) = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        fun canWrite(context: Context) = context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

        /**
         * Of [events], the ones on [day] in [zone], sorted. An all-day event's times are midnights
         * in UTC (Android's rule), so it's on the UTC dates it spans, not on the hours it overlaps
         * here: in India, yesterday's holiday would otherwise run into this morning.
         */
        fun onDay(events: List<CalendarEvent>, day: LocalDate, zone: ZoneId): List<CalendarEvent> {
            val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            return events.filter { e ->
                if (e.allDay) {
                    val first = Instant.ofEpochMilli(e.begin).atOffset(ZoneOffset.UTC).toLocalDate()
                    val last = Instant.ofEpochMilli(maxOf(e.end - 1, e.begin)).atOffset(ZoneOffset.UTC).toLocalDate()
                    !day.isBefore(first) && !day.isAfter(last)
                } else {
                    (e.begin < end && e.end > start) || (e.begin == e.end && e.begin in start until end)
                }
            }.distinctBy { Triple(it.id, it.begin, it.title) }.sortedWith(compareByDescending<CalendarEvent> { it.allDay }.thenBy { it.begin })
        }

        fun from(context: Context): CalendarBook = CalendarBook(
            loadCalendars = { if (canRead(context)) calendars(context) else null },
            loadEvents = { from, to -> if (canRead(context)) events(context, from, to) else null },
        )

        private fun calendars(context: Context): List<CalendarInfo> {
            val columns = arrayOf(
                Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE,
                Calendars.CALENDAR_ACCESS_LEVEL, Calendars.IS_PRIMARY, Calendars.OWNER_ACCOUNT,
            )
            val found = mutableListOf<CalendarInfo>()
            context.contentResolver.query(Calendars.CONTENT_URI, columns, "${Calendars.VISIBLE} = 1", null, null)?.use { c ->
                while (c.moveToNext()) {
                    val account = c.getString(2).orEmpty()
                    val local = c.getString(3) == CalendarContract.ACCOUNT_TYPE_LOCAL
                    // HyperOS's own calendar is named by a resource key ("calendar_displayname_local").
                    val name = c.getString(1)?.takeUnless { it.startsWith("calendar_displayname") } ?: if (local) "On this phone" else account
                    val primary = c.getInt(5) == 1 || (!local && c.getString(6) == account && name == account)
                    found += CalendarInfo(c.getLong(0), name, account, c.getInt(4) >= Calendars.CAL_ACCESS_CONTRIBUTOR, primary, local)
                }
            }
            return found
        }

        private fun events(context: Context, from: Long, to: Long): List<CalendarEvent> {
            val uri = Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
            val columns = arrayOf(
                Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY,
                Instances.CALENDAR_DISPLAY_NAME, Instances.EVENT_LOCATION,
            )
            // Shown calendars only, and not the events the user declined or that were cancelled
            // (either may be unset, which SQL's != would also leave out).
            val where = "${Instances.VISIBLE} = 1" +
                " AND (${Instances.SELF_ATTENDEE_STATUS} IS NULL OR ${Instances.SELF_ATTENDEE_STATUS} != ${Attendees.ATTENDEE_STATUS_DECLINED})" +
                " AND (${Instances.STATUS} IS NULL OR ${Instances.STATUS} != ${Events.STATUS_CANCELED})"
            val found = mutableListOf<CalendarEvent>()
            context.contentResolver.query(uri, columns, where, null, "${Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    found += CalendarEvent(
                        id = c.getLong(0), title = c.getString(1)?.ifBlank { null } ?: "(No title)",
                        begin = c.getLong(2), end = c.getLong(3), allDay = c.getInt(4) == 1,
                        calendar = c.getString(5).orEmpty(), location = c.getString(6)?.ifBlank { null },
                    )
                }
            }
            return found
        }

        /** Adds an event to [calendar]; its id, or null if the provider refused it. All-day events are UTC midnights. */
        fun add(context: Context, calendar: Long, title: String, begin: Long, end: Long, allDay: Boolean, zone: ZoneId = ZoneId.systemDefault()): Long? {
            val values = ContentValues().apply {
                put(Events.CALENDAR_ID, calendar)
                put(Events.TITLE, title)
                put(Events.DTSTART, begin)
                put(Events.DTEND, end)
                put(Events.ALL_DAY, if (allDay) 1 else 0)
                put(Events.EVENT_TIMEZONE, if (allDay) "UTC" else zone.id)
            }
            return context.contentResolver.insert(Events.CONTENT_URI, values)?.let(ContentUris::parseId)?.takeIf { it >= 0 }
        }

        /** Deletes the event [id] (an Undo right after adding it). */
        fun delete(context: Context, id: Long): Boolean =
            context.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null) > 0

        /** The calendar app showing event [id] (at the occurrence from [begin]). */
        fun viewEvent(id: Long, begin: Long, end: Long) = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(Events.CONTENT_URI, id))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)

        /** The calendar app at [day]. */
        fun viewDay(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()) = Intent(Intent.ACTION_VIEW,
            CalendarContract.CONTENT_URI.buildUpon().appendPath("time").also { ContentUris.appendId(it, day.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()) }.build())

        /**
         * The calendar app's new-event screen, filled in (ACTION_INSERT needs no permission; the user
         * saves it there): for when Ferb may not add events itself.
         */
        fun insertIntent(title: String, begin: Long, end: Long, allDay: Boolean) = Intent(Intent.ACTION_INSERT, Events.CONTENT_URI)
            .putExtra(Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
    }
}
