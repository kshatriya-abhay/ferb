package dev.abhay.assistant.tools

import android.Manifest
import dev.abhay.assistant.R
import dev.abhay.assistant.notes.NoteStore
import dev.abhay.assistant.reminders.Repeat
import dev.abhay.assistant.reminders.ReminderTime
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The request itself, added to the arguments: the time is read from it ([ReminderTime.read]), as Needle 3 doesn't know the date. */
private const val SAID = "_request"

object AddReminder : Tool(
    name = "add_reminder",
    title = "Reminders",
    icon = R.drawable.ic_reminder,
    description = "Remind you of something at a time, with a notification from Ferb; it can repeat, and be linked to a note.",
    onByDefault = false,
    // On tool-check (2026-10-14), right reminders scored 0.19–0.51 and nothing else reached it;
    // a wrong one has Undo.
    defaultRunFrom = 0.3,
    examples = listOf("Remind me in 20 minutes to check the oven", "Remind me on Friday to pay the rent"),
) {
    override val keywords = listOf("remind me", "reminder", "remind")
    override val permissions = listOf(Manifest.permission.POST_NOTIFICATIONS)

    override val schema = """
        {"name": "add_reminder",
         "description": "Remind the user of something at a time, such as remind me at 6 to call mum",
         "parameters": {"type": "object", "properties": {
           "text": {"type": "string", "description": "What to be reminded of, in the request's words, such as call mum"},
           "time": {"type": "string", "description": "When, as the request says it, such as tomorrow at 9 or in 20 minutes"},
           "repeat": {"type": "string", "description": "Only if the request says it repeats: daily, weekly, monthly or yearly"},
           "note": {"type": "string", "description": "Only if the request names a note: its name"}},
           "required": ["text", "time"]}}
    """.trimIndent()

    override fun fromRequest(arguments: Map<String, Any?>, request: String) = arguments + (SAID to request)

    /** What to be reminded of must be the request's words, and the time's numbers too. */
    override fun grounded(arguments: Map<String, Any?>, request: String) =
        Tools.mostlySaid(arguments.text("text"), request) && (arguments.text("time")?.let { SaidNumbers.grounded(it, request) } ?: true)

    /** "Pay the rent": the words of the reminder, without "remind me to" or the time. */
    fun title(text: String): String = When.strip(text)
        .replace(Regex("""^(?:please )?(?:remind me (?:to |about |that )?|reminder (?:to |for )?)""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""^(?:to|about) """, RegexOption.IGNORE_CASE), "")
        .trim().trimEnd('.', '!').replaceFirstChar { it.uppercase() }

    fun describe(at: LocalDateTime, now: LocalDateTime): String =
        dayName(at.toLocalDate(), now.toLocalDate()) + ", " + at.format(DateTimeFormatter.ofPattern("HH:mm", Locale.US))

    override fun prepare(arguments: Map<String, Any?>, env: PrepareEnv): Prepared {
        val reminders = env.reminders ?: return Prepared.Invalid("Reminders aren't available")
        val text = title(arguments.text("text") ?: return Prepared.Invalid("Remind you of what?"))
        if (text.isBlank()) return Prepared.Invalid("Remind you of what?")
        val now = env.now.toLocalDateTime()
        // The request's own words first, then Needle 3's "time".
        val at = arguments.text(SAID)?.let { ReminderTime.read(it, now) } ?: arguments.text("time")?.let { ReminderTime.read(it, now) }
            ?: return Prepared.Invalid("When? Say a time, such as in 20 minutes or tomorrow at 9")
        if (!at.isAfter(now)) return Prepared.Invalid("That's in the past")
        // The request's words decide the repeat; Needle 3's only counts when the request says one too.
        val said = arguments.text(SAID)
        val repeat = said?.let(Repeat::said) ?: Repeat.of(arguments.text("repeat")).takeIf { said == null }
        // A reminder on a note needs the notes folder; without a note, none.
        val noteName = arguments.text("note")
        if (noteName != null && !env.notes.ready) return Prepared.Invalid("Reminders on notes need your notes folder", needs = listOf(NoteStore.NEEDS_FOLDER))
        val note = noteName?.let { NoteStore.find(it, env.notes.list()) }
        // Without notifications a reminder can't remind: ask first, then the request runs again.
        if (!env.granted(Manifest.permission.POST_NOTIFICATIONS)) {
            return Prepared.Invalid("Ferb needs to show notifications to remind you", needs = listOf(Manifest.permission.POST_NOTIFICATIONS))
        }
        val summary = "Remind you: “$text” · ${describe(at, now)}" + (repeat?.let { ", ${it.key}" } ?: "")
        return Prepared.Ready(summary, opensApp = false, detail = note?.let { "On the note “${it.name}”" }) { _ ->
            val reminder = reminders.add(note?.name ?: text, at, repeat, note) ?: return@Ready Outcome.Failed("Couldn't save the reminder in “${note?.name}”")
            env.remindersChanged()
            Outcome.Done("I'll remind you: “${reminder.title}” · ${describe(at, now)}" + (repeat?.let { ", ${it.key}" } ?: ""),
                actions = listOf(FollowUp("Undo") { _ ->
                    reminders.remove(reminder.id)
                    env.remindersChanged()
                    Outcome.Done("Reminder removed")
                }))
        }
    }
}
