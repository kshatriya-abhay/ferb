package dev.abhay.assistant.tools

import android.Manifest
import dev.abhay.assistant.R
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
    description = "Remind you of something at a time, with a notification from Ferb; it can repeat.",
    onByDefault = false,
    // On tool-check (2026-10-14), right reminders scored 0.19–0.51 and nothing else reached it;
    // a wrong one has Undo.
    defaultRunFrom = 0.3,
    examples = listOf("Remind me in 20 minutes to check the oven", "Remind me on Friday to pay the rent"),
) {
    // Hindi's "yaad dilana" too (2026-10-29): the words as said then name reminders, so a rewrite
    // that makes an alarm of one asks (DateGate's gate H), and Needle 3 sees the tool.
    override val keywords = listOf("remind me", "reminder", "remind", "yaad dilana", "yaad dila", "yaad dilao", "yaad dilaana", "yaad karana", "याद दिलाना", "याद दिला", "याद दिलाओ")
    override val permissions = listOf(Manifest.permission.POST_NOTIFICATIONS)

    override val schema = """
        {"name": "add_reminder",
         "description": "Remind the user of something at a time, such as remind me at 6 to call mum",
         "parameters": {"type": "object", "properties": {
           "text": {"type": "string", "description": "What to be reminded of, in the request's words, such as call mum"},
           "time": {"type": "string", "description": "When, as the request says it, such as tomorrow at 9 or in 20 minutes"},
           "repeat": {"type": "string", "description": "Only if the request says it repeats: daily, weekly, monthly or yearly"}},
           "required": ["text", "time"]}}
    """.trimIndent()

    override fun fromRequest(arguments: Map<String, Any?>, request: String) = arguments + (SAID to request)

    /** What to be reminded of must be the request's words, and the time's numbers too. */
    override fun grounded(arguments: Map<String, Any?>, request: String) =
        Tools.mostlySaid(arguments.text("text"), request) &&
            (arguments[TimeArgs.RESOLVED] == true || arguments.text("time")?.let { SaidNumbers.grounded(it, request) } ?: true)

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
        val resolved = arguments[TimeArgs.RESOLVED] == true
        // Resolved from the computer's time structure ([TimeArgs]): that, before the words.
        val at = arguments.text(TimeArgs.AT)?.let { java.time.LocalDateTime.parse(it) }
            ?: arguments.text(SAID)?.takeIf { !resolved }?.let { ReminderTime.read(it, now) }
            ?: arguments.text("time")?.takeIf { !resolved }?.let { ReminderTime.read(it, now) }
            ?: return Prepared.Invalid(arguments.text(TimeArgs.ASK)?.replaceFirstChar { it.uppercase() } ?: "When? Say a time, such as in 20 minutes or tomorrow at 9")
        if (!at.isAfter(now)) return Prepared.Invalid("That's in the past")
        // The request's words decide the repeat; Needle 3's only counts when the request says one too.
        val said = arguments.text(SAID)
        val repeat = if (resolved) Repeat.of(arguments.text(TimeArgs.REPEAT))
            else said?.let(Repeat::said) ?: Repeat.of(arguments.text("repeat")).takeIf { said == null }
        // Without notifications a reminder can't remind: ask first, then the request runs again.
        if (!env.granted(Manifest.permission.POST_NOTIFICATIONS)) {
            return Prepared.Invalid("Ferb needs to show notifications to remind you", needs = listOf(Manifest.permission.POST_NOTIFICATIONS))
        }
        val summary = "Remind you: “$text” · ${describe(at, now)}" + (repeat?.let { ", ${it.key}" } ?: "")
        return Prepared.Ready(summary, opensApp = false) { _ ->
            val reminder = reminders.add(text, at, repeat)
            env.remindersChanged()
            added(env, reminder.id, "I'll remind you: “${reminder.title}” · ${describe(at, now)}" + (repeat?.let { ", ${it.key}" } ?: ""))
        }
    }

    /** A reminder's reply: "Reminders" opens the Reminders tab (keeping the reply), "Undo" removes it. */
    fun added(env: PrepareEnv, id: String, message: String): Outcome.Done = Outcome.Done(message, actions = listOf(
        FollowUp(REMINDERS) { _ ->
            env.openReminders()
            added(env, id, message)
        },
        FollowUp("Undo") { _ ->
            env.reminders?.remove(id)
            env.remindersChanged()
            Outcome.Done("Reminder removed")
        },
    ))

    const val REMINDERS = "Reminders"
}
