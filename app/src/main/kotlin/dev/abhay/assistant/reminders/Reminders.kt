package dev.abhay.assistant.reminders

import dev.abhay.assistant.notes.NoteStore
import dev.abhay.assistant.notes.NoteText
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

    /** The [n]th time after [start]: from the start, not the last time, so the 31st comes back after February's 28th. */
    fun nth(start: LocalDateTime, n: Long): LocalDateTime = when (this) {
        Daily -> start.plusDays(n)
        Weekly -> start.plusWeeks(n)
        Monthly -> start.plusMonths(n)
        Yearly -> start.plusYears(n)
    }

    companion object {
        fun of(text: String?): Repeat? = text?.trim()?.lowercase()?.let { t ->
            entries.firstOrNull { it.key == t || t == it.name.lowercase() || t == "every " + mapOf("daily" to "day", "weekly" to "week", "monthly" to "month", "yearly" to "year")[it.key] }
        }
    }
}

/**
 * One reminder: [title], due [at] (local time), maybe repeating. [snoozed] moves only the next
 * notification, not the reminder itself.
 * [fired]: its notification for [at] has shown (a one-off; a repeat moves on instead).
 * For the Reminders tab's Recent: [firedAt], the time of the last occurrence whose notification
 * showed, and [doneAt], when Done was tapped on it.
 */
data class Reminder(
    val id: String, val title: String, val at: LocalDateTime, val repeat: Repeat? = null, val snoozed: LocalDateTime? = null,
    val fired: Boolean = false, val firedAt: LocalDateTime? = null, val doneAt: LocalDateTime? = null,
    /** A repeat's first time, what each next one counts from ([Repeat.nth]); null (saved before 2026-11-16): [at]. */
    val start: LocalDateTime? = null,
) {
    /** When the notification is due. */
    val due: LocalDateTime get() = snoozed ?: at

    /** A one-off that's over: shown, or done. */
    val over: Boolean get() = repeat == null && (fired || doneAt != null)
}

/** A reminder on the Recent list: its last occurrence, done ([done]) or not, at [time] (when done, or when it was due). */
data class RecentReminder(val reminder: Reminder, val time: LocalDateTime, val done: Boolean)

/** Reminders' times as the note's `remind:` key holds them: "2026-10-20 09:00", or a date alone, meaning 09:00. */
object ReminderTime {
    private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
    val DEFAULT_TIME: LocalTime = LocalTime.of(9, 0)

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

    /** A Recent row's time: "Done 9:00 am", "Was due 9:00 am", or "Done yesterday 9:00 pm". */
    fun recent(entry: RecentReminder, now: LocalDateTime): String {
        val time = entry.time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)).lowercase()
        val day = when (entry.time.toLocalDate()) {
            now.toLocalDate() -> ""
            now.toLocalDate().minusDays(1) -> "yesterday "
            else -> entry.time.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)) + ", "
        }
        return (if (entry.done) "Done " else "Was due ") + day + time
    }

    /** A `remind:` value: a date and time, or a date (09:00); null if it's neither. */
    fun parse(value: String): LocalDateTime? {
        val v = value.trim().trim('"', '\'').replace('T', ' ')
        return runCatching { LocalDateTime.parse(v.take(16), FORMAT) }.getOrNull()
            ?: runCatching { java.time.LocalDate.parse(v.take(10)).atTime(DEFAULT_TIME) }.getOrNull()
    }
}

/** Where reminders are kept: Ferb's own file ([FileReminderStore]), or memory in tests. */
interface ReminderStore {
    fun load(): List<Reminder>
    fun save(reminders: List<Reminder>)
}

/**
 * Reminders: title, time, maybe a repeat, kept in Ferb's own store and nowhere else (no notes
 * since 2026-10-23). [Alarms][ReminderAlarms] schedule what this decides. Its functions run one at a
 * time: alarms, a launch and the screens all change it, each on its own thread.
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

    @Synchronized
    fun all(): List<Reminder> = store.load().sortedBy { it.due }

    /** What's still to come, soonest first: what the alarms are set for, and what the Reminders tab lists. */
    @Synchronized
    fun upcoming(): List<Reminder> = all().filter { it.due.isAfter(now()) && it.doneAt.let { done -> done == null || it.repeat != null } }

    /**
     * The Reminders tab's Recent: reminders whose last occurrence showed or was done in the past
     * [RECENT_HOURS], newest first. A repeat can be here and in [upcoming] at its next time.
     */
    @Synchronized
    fun recent(): List<RecentReminder> {
        val since = now().minusHours(RECENT_HOURS)
        return store.load().mapNotNull { r ->
            val entry = when {
                r.doneAt != null -> RecentReminder(r, r.doneAt, done = true)
                r.firedAt != null -> RecentReminder(r, r.firedAt, done = false)
                else -> null
            }
            entry?.takeIf { it.time.isAfter(since) && !it.time.isAfter(now()) }
        }.sortedByDescending { it.time }
    }

    /**
     * One-offs over for more than [RECENT_HOURS] (shown or done, by when that was) leave the store.
     * On launch, after a reboot or update, and as reminders fire.
     */
    @Synchronized
    fun prune() {
        val since = now().minusHours(RECENT_HOURS)
        val kept = store.load().filterNot { it.over && !(it.doneAt ?: it.firedAt ?: it.at).isAfter(since) }
        if (kept.size != store.load().size) store.save(kept)
    }

    /**
     * Takes an entry off Recent (its clear button): a one-off goes; a repeat only forgets its last
     * occurrence and stays scheduled. Returned for Undo ([restore]).
     */
    @Synchronized
    fun dismiss(id: String): Reminder? {
        val reminder = store.load().firstOrNull { it.id == id } ?: return null
        if (reminder.repeat == null) return clear(id)
        save(reminder.copy(firedAt = null, doneAt = null))
        return reminder
    }

    @Synchronized
    fun add(title: String, at: LocalDateTime, repeat: Repeat? = null): Reminder {
        val reminder = Reminder(NoteStore.newId(), title, at, repeat, start = at.takeIf { repeat != null })
        store.save(store.load() + reminder)
        return reminder
    }

    /** Removes a reminder (Undo, or Done without a repeat). */
    @Synchronized
    fun remove(id: String) {
        clear(id)
    }

    /** Clears a reminder entirely (a repeat too, not just its next time); it's returned for Undo ([restore]), or null if there's none. */
    @Synchronized
    fun clear(id: String): Reminder? {
        val reminder = store.load().firstOrNull { it.id == id } ?: return null
        store.save(store.load().filter { it.id != id })
        return reminder
    }

    /** Undoes [clear]: the reminder back as it was (time, repeat, snooze). */
    @Synchronized
    fun restore(reminder: Reminder) = store.save(store.load().filter { it.id != reminder.id } + reminder)

    /** Adds reminders kept elsewhere before (the notes' frontmatter, read once: [ReminderMigration]), keeping those already here. */
    @Synchronized
    fun import(reminders: List<Reminder>) {
        val kept = store.load()
        val new = reminders.filter { r -> kept.none { it.id == r.id } }
        if (new.isNotEmpty()) store.save(kept + new)
    }

    /**
     * Done: a repeat moves to its next time after now, unless it already has ([fired]), when only
     * a snooze goes; a one-off is marked done, for Recent, until [prune]d. Both keep when.
     */
    @Synchronized
    fun done(id: String): Reminder? {
        val reminder = store.load().firstOrNull { it.id == id } ?: return null
        val doneNow = reminder.copy(doneAt = now().withNano(0), snoozed = null)
        if (reminder.repeat == null) return doneNow.copy(fired = true, firedAt = reminder.firedAt ?: reminder.at).also { save(it) }
        if (reminder.at.isAfter(now())) return doneNow.also { save(it) }
        return advance(doneNow.copy(firedAt = reminder.at))
    }

    /**
     * Its notification has just shown: a repeat moves on to its next time now, so it comes back
     * whether or not Done is tapped, and a snooze that's over goes. A one-off stays until Done,
     * marked [Reminder.fired], so it's never shown late as well ([missed]).
     */
    @Synchronized
    fun fired(id: String): Reminder? {
        val reminder = store.load().firstOrNull { it.id == id } ?: return null
        val unsnoozed = reminder.copy(snoozed = reminder.snoozed?.takeIf { it.isAfter(now()) })
        if (unsnoozed.repeat == null) return unsnoozed.copy(fired = true, firedAt = reminder.firedAt ?: reminder.at).also { if (it != reminder) save(it) }
        if (unsnoozed.at.isAfter(now())) return unsnoozed.also { if (it != reminder) save(it) }
        // This occurrence showed: it's the one Recent lists, not done yet.
        return advance(unsnoozed.copy(firedAt = unsnoozed.at, doneAt = null))
    }

    /** One-offs whose time went by without their notification (the phone was off): to show late, then [fired]. */
    @Synchronized
    fun missed(): List<Reminder> = all().filter {
        (it.repeat == null && !it.fired && it.doneAt == null && !it.due.isAfter(now())) ||
            // A snooze that came due unseen (the phone off): the user asked to be reminded again (review P2-55).
            (it.snoozed != null && !it.snoozed.isAfter(now()) && (it.repeat != null || it.doneAt == null))
    }

    /**
     * Repeats whose time went by unseen (the phone off, or Ferb's alarms cleared) move to their
     * next time, so they keep coming: after a reboot, an update, a launch.
     */
    @Synchronized
    fun catchUp(): List<Reminder> {
        // By the occurrence's own time, not a snooze's: a snoozed repeat already moved on (review P2-54).
        store.load().filter { it.repeat != null && !it.at.isAfter(now()) }.forEach { advance(it) }
        return all()
    }

    /** A repeat at its next time after now, without a snooze. */
    private fun advance(reminder: Reminder): Reminder {
        val repeat = reminder.repeat!!
        val start = reminder.start ?: reminder.at
        var n = 1L
        var next = repeat.nth(start, n)
        while (!next.isAfter(reminder.at) || !next.isAfter(now())) next = repeat.nth(start, ++n)
        val moved = reminder.copy(at = next, snoozed = null, start = start)
        save(moved)
        return moved
    }

    private fun save(reminder: Reminder) = store.save(store.load().map { if (it.id == reminder.id) reminder else it })

    /** Snooze: the notification again in [minutes]; the reminder keeps its time. */
    @Synchronized
    fun snooze(id: String, minutes: Long = SNOOZE_MINUTES): Reminder? {
        val snoozed = store.load().firstOrNull { it.id == id }?.copy(snoozed = now().plusMinutes(minutes).withNano(0)) ?: return null
        store.save(store.load().map { if (it.id == id) snoozed else it })
        return snoozed
    }

    /**
     * Its alarm went off: [show]s it and marks it [fired], unless it already showed (a one-off shown
     * as [missed] by a launch meanwhile, or done); a snooze that's due shows again.
     */
    @Synchronized
    fun fire(id: String, show: (Reminder) -> Boolean): Reminder? {
        val reminder = store.load().firstOrNull { it.id == id } ?: return null
        if (reminder.repeat == null && (reminder.doneAt != null || reminder.fired && reminder.snoozed == null)) return null
        // Not due yet: a stale alarm for an occurrence already shown (a repeat moved on), review P2-56.
        if (reminder.due.isAfter(now())) return null
        // A one-off counts as shown only if it was (notifications off: it stays missed, as showMissed does);
        // a repeat moves on to its next time either way (review P2-58).
        if (!show(reminder) && reminder.repeat == null) return null
        return fired(id)
    }

    /** The upcoming reminders handed to [sync] (the alarms) while the book is locked, so no change slips between them. */
    @Synchronized
    fun schedule(sync: (List<Reminder>) -> Unit) = sync(upcoming())

    /** One-offs whose time went by unseen ([missed]): each [show]n late, then [fired], so it never shows twice. */
    @Synchronized
    fun showMissed(show: (Reminder) -> Boolean) {
        missed().forEach { if (show(it)) fired(it.id) }
    }

    companion object {
        const val SNOOZE_MINUTES = 10L
        const val RECENT_HOURS = 24L
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
/**
 * Reminders as JSON in Ferb's own files. What it can't read it keeps (review P2-57): an entry it can't
 * parse is skipped on load and written back as it was on the next save; a file that isn't JSON at all
 * is moved aside (reminders.json.bad) before anything is written over it. A save goes to a temp file,
 * synced to disk, then renamed over the old one, and fails loudly if the rename does.
 */
class FileReminderStore(private val file: java.io.File) : ReminderStore {
    /** Entries the last load couldn't read, as they were, to write back. */
    private var unreadable = emptyList<JSONObject>()
    /** The file wasn't JSON at all on the last load: move it aside before saving over it. */
    private var broken = false

    @Synchronized
    override fun load(): List<Reminder> {
        unreadable = emptyList(); broken = false
        if (!file.exists()) return emptyList()
        val a = try {
            JSONArray(file.readText())
        } catch (e: Exception) {
            broken = true
            log("reminders.json unreadable: ${e.javaClass.simpleName}")
            return emptyList()
        }
        val skipped = mutableListOf<JSONObject>()
        val read = (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            try {
                reminder(o)
            } catch (e: Exception) {
                log("a reminder unreadable: ${e.javaClass.simpleName}")
                skipped += o
                null
            }
        }
        unreadable = skipped
        return read
    }

    private fun reminder(o: JSONObject): Reminder {
        val at = LocalDateTime.parse(o.getString("at"))
        // Saved before "fired" was kept (2026-10-23): one already past counts as shown, so nothing old shows late.
        val fired = if (o.has("fired")) o.getBoolean("fired") else !at.isAfter(LocalDateTime.now())
        // A "note" key (a reminder on a note, until 2026-10-23) is dropped: it's a plain reminder now.
        fun time(key: String) = o.optString(key).ifEmpty { null }?.let(LocalDateTime::parse)
        return Reminder(o.getString("id"), o.getString("title"), at, Repeat.of(o.optString("repeat").ifEmpty { null }),
            time("snoozed"), fired, time("firedAt"), time("doneAt"), time("start"))
    }

    @Synchronized
    override fun save(reminders: List<Reminder>) {
        val a = JSONArray(reminders.map { r ->
            JSONObject().put("id", r.id).put("title", r.title).put("at", r.at.toString()).put("repeat", r.repeat?.key ?: "")
                .put("snoozed", r.snoozed?.toString() ?: "").put("fired", r.fired)
                .put("firedAt", r.firedAt?.toString() ?: "").put("doneAt", r.doneAt?.toString() ?: "")
                .put("start", r.start?.toString() ?: "")
        } + unreadable.filter { o -> reminders.none { it.id == o.optString("id") } })
        if (broken && file.exists()) {
            val aside = java.io.File(file.parentFile, file.name + ".bad")
            if (!file.renameTo(aside)) throw java.io.IOException("Couldn't keep the unreadable reminders file aside")
            broken = false
        }
        val tmp = java.io.File(file.parentFile, file.name + ".tmp")
        java.io.FileOutputStream(tmp).use { out ->
            out.write(a.toString().toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) throw java.io.IOException("Couldn't save the reminders")
    }

    /** Debug log, in no words of the user's; a no-op where Android's log isn't there (JVM tests). */
    private fun log(message: String) {
        runCatching { android.util.Log.d("FerbReminders", message) }
    }
}
