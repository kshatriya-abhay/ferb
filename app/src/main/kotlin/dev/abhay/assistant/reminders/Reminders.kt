package dev.abhay.assistant.reminders

import dev.abhay.assistant.notes.NoteStore
import dev.abhay.assistant.notes.NoteText
import dev.abhay.assistant.tools.Durations
import dev.abhay.assistant.tools.When
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** How a reminder repeats, as the note's `repeat:` key says it. */
enum class Repeat(val key: String) {
    Daily("daily"), Weekly("weekly"), Monthly("monthly"), Yearly("yearly");

    fun after(at: LocalDateTime): LocalDateTime = when (this) {
        Daily -> at.plusDays(1)
        Weekly -> at.plusWeeks(1)
        Monthly -> at.plusMonths(1)
        Yearly -> at.plusYears(1)
    }

    companion object {
        /** The repeat a request says: "every day", "daily", "every Monday" (weekly), "every month"…; null if it says none. */
        fun said(text: String): Repeat? {
            val t = text.lowercase()
            return when {
                Regex("""\b(every ?day|daily|each day|every morning|every evening|every night)\b""").containsMatchIn(t) -> Daily
                Regex("""\b(every|each) (week|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b|\bweekly\b""").containsMatchIn(t) -> Weekly
                Regex("""\b(every|each) month\b|\bmonthly\b""").containsMatchIn(t) -> Monthly
                Regex("""\b(every|each) year\b|\byearly\b|\bannually\b""").containsMatchIn(t) -> Yearly
                else -> null
            }
        }

        fun of(text: String?): Repeat? = text?.trim()?.lowercase()?.let { t ->
            entries.firstOrNull { it.key == t || t == it.name.lowercase() || t == "every " + mapOf("daily" to "day", "weekly" to "week", "monthly" to "month", "yearly" to "year")[it.key] }
        }
    }
}

/**
 * One reminder: [title], due [at] (local time), maybe repeating. [snoozed] moves only the next
 * notification, not the reminder itself.
 * [fired]: its notification for [at] has shown (a one-off; a repeat moves on instead).
 */
data class Reminder(val id: String, val title: String, val at: LocalDateTime, val repeat: Repeat? = null, val snoozed: LocalDateTime? = null, val fired: Boolean = false) {
    /** When the notification is due. */
    val due: LocalDateTime get() = snoozed ?: at
}

/** Reminders' times as the note's `remind:` key holds them: "2026-10-20 09:00", or a date alone, meaning 09:00. */
object ReminderTime {
    private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
    val DEFAULT_TIME: LocalTime = LocalTime.of(9, 0)
    private val RELATIVE = Regex("""\bin (an?|\d+(?:\.\d+)?|half an?) ?(minutes?|mins?|hours?|hrs?|h)\b(?: and (\d+) ?(?:minutes?|mins?))?""")

    fun format(at: LocalDateTime): String = at.format(FORMAT)

    /** A late notification's line: "Due 9:00 am", or "Due Thu 22 Oct, 9:00 am" when it was another day. */
    fun due(at: LocalDateTime, now: LocalDateTime): String {
        val time = at.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)).lowercase()
        return if (at.toLocalDate() == now.toLocalDate()) "Due $time" else "Due ${at.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.US))}, $time"
    }

    /**
     * The next time, for the Reminders tab: "Today 9:00 pm", "Tomorrow 7:00 am", "Tue 28 Oct, 6:30 pm",
     * with the year when it isn't this one.
     */
    fun next(at: LocalDateTime, now: LocalDateTime): String {
        val time = at.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)).lowercase()
        val day = at.toLocalDate()
        return when {
            day == now.toLocalDate() -> "Today $time"
            day == now.toLocalDate().plusDays(1) -> "Tomorrow $time"
            day.year == now.year -> "${at.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.US))}, $time"
            else -> "${at.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.US))}, $time"
        }
    }

    /** How it repeats, for the Reminders tab: "Daily", "Every Monday", "Monthly", "Yearly"; null for a one-off. */
    fun repeat(reminder: Reminder): String? = when (reminder.repeat) {
        null -> null
        Repeat.Daily -> "Daily"
        Repeat.Weekly -> "Every " + reminder.at.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
        Repeat.Monthly -> "Monthly"
        Repeat.Yearly -> "Yearly"
    }

    const val NONE_UPCOMING = "No upcoming reminders. Ask Ferb to remind you."

    /** A `remind:` value: a date and time, or a date (09:00); null if it's neither. */
    fun parse(value: String): LocalDateTime? {
        val v = value.trim().trim('"', '\'').replace('T', ' ')
        return runCatching { LocalDateTime.parse(v.take(16), FORMAT) }.getOrNull()
            ?: runCatching { java.time.LocalDate.parse(v.take(10)).atTime(DEFAULT_TIME) }.getOrNull()
    }

    /**
     * When a spoken or typed reminder is due: "in 20 minutes" from [now]; else a day and a time
     * ("tomorrow at 9", "Friday", "at 6 pm"), a day alone at 09:00, a time alone the next time it
     * comes. Null if [text] says no time.
     */
    fun read(text: String, now: LocalDateTime): LocalDateTime? {
        val t = text.lowercase()
        RELATIVE.find(t)?.let { m ->
            val seconds = Durations.parse(m.value.removePrefix("in ").trim()) ?: return@let
            return now.plusSeconds(seconds.toLong()).withNano(0)
        }
        val said = When.read(text, now)
        if (said.isEmpty) return null
        val time = said.time ?: DEFAULT_TIME
        val day = said.day ?: if (time.isAfter(now.toLocalTime())) now.toLocalDate() else now.toLocalDate().plusDays(1)
        return day.atTime(time)
    }
}

/** Where reminders are kept: Ferb's own file ([FileReminderStore]), or memory in tests. */
interface ReminderStore {
    fun load(): List<Reminder>
    fun save(reminders: List<Reminder>)
}

/**
 * Reminders: title, time, maybe a repeat, kept in Ferb's own store and nowhere else (no notes
 * since 2026-10-23). [Alarms][ReminderAlarms] schedule what this decides.
 */
class ReminderBook(store: ReminderStore, private val now: () -> LocalDateTime) {
    private val _changes = MutableStateFlow(0)

    /** Counts saves (added, fired, done, snoozed, cleared), so a screen showing reminders refreshes. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private val store = object : ReminderStore by store {
        override fun save(reminders: List<Reminder>) {
            store.save(reminders)
            _changes.update { it + 1 }
        }
    }

    fun all(): List<Reminder> = store.load().sortedBy { it.due }

    /** What's still to come, soonest first: what the alarms are set for, and what the Reminders tab lists. */
    fun upcoming(): List<Reminder> = all().filter { it.due.isAfter(now()) }

    fun add(title: String, at: LocalDateTime, repeat: Repeat? = null): Reminder {
        val reminder = Reminder(NoteStore.newId(), title, at, repeat)
        store.save(store.load() + reminder)
        return reminder
    }

    /** Removes a reminder (Undo, or Done without a repeat). */
    fun remove(id: String) {
        clear(id)
    }

    /** Clears a reminder entirely (a repeat too, not just its next time); it's returned for Undo ([restore]), or null if there's none. */
    fun clear(id: String): Reminder? {
        val reminder = store.load().firstOrNull { it.id == id } ?: return null
        store.save(store.load().filter { it.id != id })
        return reminder
    }

    /** Undoes [clear]: the reminder back as it was (time, repeat, snooze). */
    fun restore(reminder: Reminder) = store.save(store.load().filter { it.id != reminder.id } + reminder)

    /** Adds reminders kept elsewhere before (the notes' frontmatter, read once: [ReminderMigration]), keeping those already here. */
    fun import(reminders: List<Reminder>) {
        val kept = store.load()
        val new = reminders.filter { r -> kept.none { it.id == r.id } }
        if (new.isNotEmpty()) store.save(kept + new)
    }

    /**
     * Done: a repeat moves to its next time after now, unless it already has ([fired]), when only
     * a snooze goes; otherwise the reminder goes.
     */
    fun done(id: String): Reminder? {
        val reminder = store.load().firstOrNull { it.id == id } ?: return null
        if (reminder.repeat == null) return null.also { remove(id) }
        if (reminder.at.isAfter(now())) return reminder.copy(snoozed = null).also { save(it) }
        return advance(reminder)
    }

    /**
     * Its notification has just shown: a repeat moves on to its next time now, so it comes back
     * whether or not Done is tapped, and a snooze that's over goes. A one-off stays until Done,
     * marked [Reminder.fired], so it's never shown late as well ([missed]).
     */
    fun fired(id: String): Reminder? {
        val reminder = store.load().firstOrNull { it.id == id } ?: return null
        val unsnoozed = reminder.copy(snoozed = reminder.snoozed?.takeIf { it.isAfter(now()) })
        if (unsnoozed.repeat == null) return unsnoozed.copy(fired = true).also { if (it != reminder) save(it) }
        if (unsnoozed.at.isAfter(now())) return unsnoozed.also { if (it != reminder) save(it) }
        return advance(unsnoozed)
    }

    /** One-offs whose time went by without their notification (the phone was off): to show late, then [fired]. */
    fun missed(): List<Reminder> = all().filter { it.repeat == null && !it.fired && !it.due.isAfter(now()) }

    /**
     * Repeats whose time went by unseen (the phone off, or Ferb's alarms cleared) move to their
     * next time, so they keep coming: after a reboot, an update, a launch.
     */
    fun catchUp(): List<Reminder> {
        store.load().filter { it.repeat != null && !it.due.isAfter(now()) }.forEach { advance(it) }
        return all()
    }

    /** A repeat at its next time after now, without a snooze. */
    private fun advance(reminder: Reminder): Reminder {
        val repeat = reminder.repeat!!
        var next = repeat.after(reminder.at)
        while (!next.isAfter(now())) next = repeat.after(next)
        val moved = reminder.copy(at = next, snoozed = null)
        save(moved)
        return moved
    }

    private fun save(reminder: Reminder) = store.save(store.load().map { if (it.id == reminder.id) reminder else it })

    /** Snooze: the notification again in [minutes]; the reminder keeps its time. */
    fun snooze(id: String, minutes: Long = SNOOZE_MINUTES): Reminder? {
        val snoozed = store.load().firstOrNull { it.id == id }?.copy(snoozed = now().plusMinutes(minutes).withNano(0)) ?: return null
        store.save(store.load().map { if (it.id == id) snoozed else it })
        return snoozed
    }

    companion object {
        const val SNOOZE_MINUTES = 10L
    }
}

/**
 * Until 2026-10-23 a reminder could live on a note, in its frontmatter (`ferb-id`, `remind`,
 * `repeat`), and one added there in Obsidian became a reminder. Read once, on the first start
 * after that ended: each such reminder not already in Ferb's store, as a plain one. The notes are
 * only read, never written; their keys stay as harmless leftovers.
 */
object ReminderMigration {
    fun fromNotes(notes: NoteStore, now: LocalDateTime): List<Reminder> {
        if (!notes.ready) return emptyList()
        return notes.list().mapNotNull { note ->
            val text = notes.read(note) ?: return@mapNotNull null
            val at = NoteText.get(text, NoteText.REMIND)?.let(ReminderTime::parse) ?: return@mapNotNull null
            // Its id was the note's ferb-id; a note without one never reached the store.
            val id = NoteText.get(text, NoteText.ID) ?: NoteStore.newId()
            // One gone by counts as shown, so nothing old turns up late.
            Reminder(id, note.name, at, Repeat.of(NoteText.get(text, NoteText.REPEAT)), fired = !at.isAfter(now))
        }
    }
}

/** Reminders in Ferb's own storage, as JSON: id, title, at, repeat, note id, snoozed. */
class FileReminderStore(private val file: java.io.File) : ReminderStore {
    override fun load(): List<Reminder> = try {
        if (!file.exists()) emptyList() else JSONArray(file.readText()).let { a ->
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val at = LocalDateTime.parse(o.getString("at"))
                // Saved before "fired" was kept (2026-10-23): one already past counts as shown, so nothing old shows late.
                val fired = if (o.has("fired")) o.getBoolean("fired") else !at.isAfter(LocalDateTime.now())
                // A "note" key (a reminder on a note, until 2026-10-23) is dropped: it's a plain reminder now.
                Reminder(o.getString("id"), o.getString("title"), at, Repeat.of(o.optString("repeat").ifEmpty { null }),
                    o.optString("snoozed").ifEmpty { null }?.let(LocalDateTime::parse), fired)
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    override fun save(reminders: List<Reminder>) {
        val a = JSONArray(reminders.map { r ->
            JSONObject().put("id", r.id).put("title", r.title).put("at", r.at.toString()).put("repeat", r.repeat?.key ?: "")
                .put("snoozed", r.snoozed?.toString() ?: "").put("fired", r.fired)
        })
        val tmp = java.io.File(file.parentFile, file.name + ".tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file)
    }
}
