package dev.abhay.assistant.tools

import dev.abhay.assistant.notes.NoteRef
import dev.abhay.assistant.notes.NoteStore
import dev.abhay.assistant.reminders.ReminderBook

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import java.time.ZonedDateTime

/** What running a call did, shown under it. */
sealed interface Outcome {
    /**
     * [actions] are buttons offered next to the result, e.g. "Turn off" after turning the torch on;
     * [level], a slider instead, e.g. for the volume just set; [lines], a list under the message,
     * e.g. a day's events.
     */
    data class Done(val message: String, val actions: List<FollowUp> = emptyList(), val level: Level? = null, val lines: List<Line> = emptyList()) : Outcome
    /** Didn't work: [reason]; when it's for want of Android permissions, [needs] lists them, for the reply to ask. */
    data class Failed(val reason: String, val needs: List<String> = emptyList()) : Outcome
}

/** One line of a result's list: [lead] (e.g. a time) beside [text], [detail] under it; tapping it starts [open]'s intent, if any. */
/** One line of a result's list: [lead] in a column of its own (a time), [open] when tapped; with [tick], a checkbox that does it (a list's item). */
class Line(val lead: String, val text: String, val detail: String? = null, val open: (() -> Intent)? = null, val tick: FollowUp? = null)

/** A slider on a result, at [value] (0..1): [set] applies a value and gives the result's new message. */
data class Level(val value: Float, val set: (Context, Float) -> String)

/** A button on a result: [run] does it and gives the new result (with its own buttons, e.g. "Turn on" again). */
class FollowUp(val label: String, val run: suspend (FollowUpEnv) -> Outcome) {
    override fun toString() = "FollowUp($label)"
}

/**
 * What a button's action can use: [awaitBack] waits until another app the action opened has handed
 * back to the assistant (false if it didn't within a few seconds), e.g. to check what it did.
 */
class FollowUpEnv(val context: Context, val awaitBack: suspend () -> Boolean)

/**
 * A call's arguments, checked: ready to run (with what it will do, in words), or why not.
 * [Ready.opensApp] is true when running it starts another app's screen (an intent), so the next
 * call must wait for the assistant to be back in front.
 */
sealed interface Prepared {
    /**
     * When it asks first: [detail] under the summary (e.g. a message's text), and [confirm] and
     * [cancel] as the buttons. With [choices] (e.g. the contacts that match a name), each is a
     * button of its own instead of [confirm], and [other] a way to do it by hand (open the
     * dialer); without asking, [run] runs, which is the first choice. [needs]: Android permissions
     * it does without (a way round, such as the dialer instead of a contact), for the reply to ask.
     */
    class Ready(
        val summary: String,
        val opensApp: Boolean = true,
        val detail: String? = null,
        val choices: List<Choice> = emptyList(),
        val other: Choice? = null,
        val confirm: String = "Do it",
        val cancel: String = "Not now",
        val needs: List<String> = emptyList(),
        val run: (Context) -> Outcome,
    ) : Prepared
    /** Can't run: [reason]; when it's for want of Android permissions, [needs] lists them, for the reply to ask. */
    data class Invalid(val reason: String, val needs: List<String> = emptyList()) : Prepared
}

/** One of a call's [Prepared.Ready.choices]: [label] and [detail] on its button, [run] when it's picked. */
class Choice(val label: String, val detail: String? = null, val run: (Context) -> Outcome)

/**
 * One capability the assistant can be given. Needle 3 only sees [schema] (name, description and
 * parameters); the app checks every argument in [prepare] before acting, because the model's
 * bounds aren't trusted alone.
 */
abstract class Tool(
    /** The function name Needle 3 calls. */
    val name: String,
    /** For the settings screen. */
    val title: String,
    /** A drawable for the tool, in its row, its page and its results. */
    @param:DrawableRes val icon: Int,
    val description: String,
    /** On until the user changes it. */
    val onByDefault: Boolean,
    /** Requests to try, for the empty assistant screen. */
    val examples: List<String>,
    /**
     * The confidence from which its calls run without asking, until the user changes it. Lower for
     * harmless actions that a tap undoes (the app checks the arguments either way); Cactus's general
     * advice is 0.7.
     */
    val defaultRunFrom: Double = 0.7,
    /** Its calls always ask first until the user changes it: for those that reach other people (calls, messages). */
    val confirmByDefault: Boolean = false,
) {
    /** For tools that ask first by default ([confirmByDefault]): why that's best, when the user turns it off. */
    open val whyAsk: String = "Ferb may get it wrong, and it would run without you seeing it first."

    /** Android permissions it needs (e.g. to read contacts), asked for when it's turned on. */
    open val permissions: List<String> = emptyList()

    /** Settings of this tool's own, on its settings page, beyond the ones every tool has. */
    open val options: List<ToolOption> = emptyList()

    /**
     * Words and phrases a request for this tool would contain ("torch", "flashlight"…), until the
     * user changes them ([ToolConfig.keywords]). A request goes to the tools it mentions
     * ([ToolRouter]), and a call to a tool the request doesn't mention asks first, whatever its
     * confidence: with the volume tool off, Needle 3 answered "mute the ringer" with "flashlight
     * off" at 0.7. Matched as whole words, also with an "s" on the end ([Keywords]).
     */
    open val keywords: List<String> = emptyList()

    /**
     * Whether [arguments] come from [request], e.g. an alarm's time is one the request says. A call
     * that isn't asks first, whatever its confidence. True when the tool doesn't check.
     */
    open fun grounded(arguments: Map<String, Any?>, request: String): Boolean = true

    /**
     * [arguments] with what the app reads from [request] itself, before [prepare]: arguments Needle 3
     * fills in wrongly when the request doesn't give them (e.g. a travel mode). Unchanged by default.
     */
    open fun fromRequest(arguments: Map<String, Any?>, request: String): Map<String, Any?> = arguments

    /** The JSON schema object Needle 3 gets: {"name", "description", "parameters"}. */
    abstract val schema: String

    abstract fun prepare(arguments: Map<String, Any?>, env: PrepareEnv): Prepared
}

/**
 * What [Tool.prepare] can look things up in: the phone's apps, contacts and calendar (each read
 * on lookup), the time [now], and the tool's own [options] ([ToolConfig.options]).
 */
class PrepareEnv(
    val apps: InstalledApps = InstalledApps { emptyList() },
    val contacts: ContactBook = ContactBook.NONE,
    val calendar: CalendarBook = CalendarBook.NONE,
    val now: ZonedDateTime = ZonedDateTime.now(),
    val options: Map<String, String> = emptyMap(),
    /** The notes folder (Notes, lists, reminders with a note); not ready until the user picks one. */
    val notes: NoteStore = NoNotes,
    /** Ferb's reminders, and what to do after one is added or removed (schedule them again). */
    val reminders: ReminderBook? = null,
    val remindersChanged: () -> Unit = {},
    /** Whether Ferb holds an Android permission (notifications for reminders). */
    val granted: (String) -> Boolean = { true },
)

/** Reads an integer argument, whether the engine gave it as a number or a numeric string. */
internal fun Map<String, Any?>.int(key: String): Int? = when (val v = this[key]) {
    is Number -> v.toInt()
    is String -> v.trim().toIntOrNull()
    else -> null
}

internal fun Map<String, Any?>.text(key: String): String? = (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

internal fun Map<String, Any?>.bool(key: String): Boolean? = when (val v = this[key]) {
    is Boolean -> v
    is String -> v.trim().lowercase().toBooleanStrictOrNull()
    else -> null
}

/** No notes folder picked. */
object NoNotes : NoteStore {
    override val ready = false
    override fun list() = emptyList<NoteRef>()
    override fun read(note: NoteRef): String? = null
    override fun update(note: NoteRef, change: (String) -> String) = false
    override fun create(name: String, text: String): NoteRef? = null
    override fun delete(note: NoteRef) = false
}
