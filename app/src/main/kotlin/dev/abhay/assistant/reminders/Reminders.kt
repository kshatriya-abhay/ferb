package dev.abhay.assistant.reminders

import dev.abhay.assistant.notes.NoteRef
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
 * One reminder: [title], due [at] (local time), maybe repeating, maybe linked to a note by its
 * `ferb-id` ([noteId]). [snoozed] moves only the next notification, not the reminder itself.
 * [fired]: its notification for [at] has shown (a one-off; a repeat moves on instead).
 */
data class Reminder(val id: String, val title: String, val at: LocalDateTime, val repeat: Repeat? = null, val noteId: String? = null, val snoozed: LocalDateTime? = null, val fired: Boolean = false) {
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
 * Reminders: kept by Ferb, linked to notes in the notes folder through their frontmatter
 * (`ferb-id`, `remind`, `repeat`), so a renamed note still matches and the reminders can be
 * rebuilt from the folder ([scan]); a `remind:` added in Obsidian becomes one too. Reminders
 * without a note need no folder. [Alarms][ReminderAlarms] schedule what this decides.
 */
class ReminderBook(store: ReminderStore, private val notes: NoteStore, private val now: () -> LocalDateTime) {
    private val _changes = MutableStateFlow(0)

    /** Counts saves (added, fired, done, snoozed, rescanned), so a screen showing reminders refreshes. */
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

    /** [upcoming], each with its note when it has one still in the folder (read once for all). */
    fun upcomingWithNotes(): List<Pair<Reminder, NoteRef?>> {
        val list = upcoming()
        if (list.none { it.noteId != null } || !notes.ready) return list.map { it to null }
        val byId = notes.list().mapNotNull { n -> notes.read(n)?.let { NoteText.get(it, NoteText.ID) }?.let { it to n } }.toMap()
        return list.map { it to it.noteId?.let(byId::get) }
    }

    /** Adds a reminder; with [note], also writes its `remind` (and `repeat`) keys there, giving it a `ferb-id` if it has none. */
    fun add(title: String, at: LocalDateTime, repeat: Repeat? = null, note: NoteRef? = null): Reminder? {
        var noteId: String? = null
        if (note != null) {
            val ok = notes.update(note) { text ->
                val id = NoteText.get(text, NoteText.ID) ?: NoteStore.newId()
                noteId = id
                var next = NoteText.set(text, NoteText.ID, id)
                next = NoteText.set(next, NoteText.REMIND, ReminderTime.format(at))
                NoteText.set(next, NoteText.REPEAT, repeat?.key)
            }
            if (!ok) return null
        }
        val reminder = Reminder(noteId ?: NoteStore.newId(), title, at, repeat, noteId)
        store.save(store.load().filter { it.id != reminder.id } + reminder)
        return reminder
    }

    /** Removes a reminder (Undo, or Done without a repeat), and the `remind`/`repeat` keys from its note. */
    fun remove(id: String) {
        val reminder = store.load().firstOrNull { it.id == id } ?: return
        store.save(store.load().filter { it.id != id })
        reminder.noteId?.let { noteId -> noteFor(noteId)?.let { note -> notes.update(note) { NoteText.set(NoteText.set(it, NoteText.REMIND, null), NoteText.REPEAT, null) } } }
    }

    /**
     * Done: a repeat moves to its next time after now (in the note too), unless it already has
     * ([fired]), when only a snooze goes; otherwise the reminder goes.
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
     * next time, so they keep coming: after a reboot, an update, the daily rescan, a launch.
     */
    fun catchUp(): List<Reminder> {
        store.load().filter { it.repeat != null && !it.due.isAfter(now()) }.forEach { advance(it) }
        return all()
    }

    /** A repeat at its next time after now, in the note too, without a snooze. */
    private fun advance(reminder: Reminder): Reminder {
        val repeat = reminder.repeat!!
        var next = repeat.after(reminder.at)
        while (!next.isAfter(now())) next = repeat.after(next)
        val moved = reminder.copy(at = next, snoozed = null)
        save(moved)
        reminder.noteId?.let { noteId -> noteFor(noteId)?.let { note -> notes.update(note) { NoteText.set(it, NoteText.REMIND, ReminderTime.format(next)) } } }
        return moved
    }

    private fun save(reminder: Reminder) = store.save(store.load().map { if (it.id == reminder.id) reminder else it })

    /** Snooze: the notification again in [minutes]; the reminder (and its note) keep their time. */
    fun snooze(id: String, minutes: Long = SNOOZE_MINUTES): Reminder? {
        val snoozed = store.load().firstOrNull { it.id == id }?.copy(snoozed = now().plusMinutes(minutes).withNano(0)) ?: return null
        store.save(store.load().map { if (it.id == id) snoozed else it })
        return snoozed
    }

    /**
     * Rebuilds the reminders linked to notes from the folder: each note with a `remind:` key is one
     * (a note without a `ferb-id` gets one), keeping a snooze; a linked reminder whose note no
     * longer has the key is gone. Reminders without a note stay. Without a folder, nothing changes.
     */
    fun scan(): List<Reminder> {
        if (!notes.ready) return all()
        val kept = store.load()
        val fromNotes = notes.list().mapNotNull { note ->
            val text = notes.read(note) ?: return@mapNotNull null
            val at = NoteText.get(text, NoteText.REMIND)?.let(ReminderTime::parse) ?: return@mapNotNull null
            val id = NoteText.get(text, NoteText.ID) ?: NoteStore.newId().also { id -> notes.update(note) { NoteText.set(it, NoteText.ID, id) } }
            val before = kept.firstOrNull { it.id == id }
            val same = before?.takeIf { it.at == at }
            Reminder(id, note.name, at, Repeat.of(NoteText.get(text, NoteText.REPEAT)), id, same?.snoozed, same?.fired ?: false)
        }
        val result = kept.filter { it.noteId == null } + fromNotes
        store.save(result)
        return result.sortedBy { it.due }
    }

    /** The note with [noteId] in its frontmatter, wherever it is now (renamed or not). */
    fun noteFor(noteId: String): NoteRef? = notes.list().firstOrNull { n -> notes.read(n)?.let { NoteText.get(it, NoteText.ID) } == noteId }

    companion object {
        const val SNOOZE_MINUTES = 10L
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
                Reminder(o.getString("id"), o.getString("title"), at, Repeat.of(o.optString("repeat").ifEmpty { null }),
                    o.optString("note").ifEmpty { null }, o.optString("snoozed").ifEmpty { null }?.let(LocalDateTime::parse), fired)
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    override fun save(reminders: List<Reminder>) {
        val a = JSONArray(reminders.map { r ->
            JSONObject().put("id", r.id).put("title", r.title).put("at", r.at.toString()).put("repeat", r.repeat?.key ?: "")
                .put("note", r.noteId ?: "").put("snoozed", r.snoozed?.toString() ?: "").put("fired", r.fired)
        })
        val tmp = java.io.File(file.parentFile, file.name + ".tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file)
    }
}
