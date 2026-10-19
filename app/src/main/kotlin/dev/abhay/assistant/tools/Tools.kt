package dev.abhay.assistant.tools

import android.Manifest
import dev.abhay.assistant.notes.NoteStore
import dev.abhay.assistant.notes.NoteText
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import dev.abhay.assistant.R
import dev.abhay.assistant.tools.Tools.again
import dev.abhay.assistant.tools.Tools.withActions
import java.util.Locale
import kotlin.math.roundToInt
import androidx.core.content.ContextCompat
import android.content.IntentFilter
import android.content.BroadcastReceiver

/**
 * The assistant's tools. Each is one narrow action, with bounds and enums in its schema and formats
 * in its descriptions, as Cactus advises for Needle. With five or fewer turned on, Needle 3 sees all
 * of them on every request; with more, it sees the five that best match the request.
 *
 * All of them work offline and for any app: they're intents to other apps (clock, settings,
 * launcher apps, notes apps, maps, the dialer and the messages app), plain Android APIs (torch, volume, media keys) or the phone's calendar (CalendarTools.kt). None needs the assistant role.
 */
object Tools {
    val all: List<Tool> = listOf(SetAlarm, SetTimer, OpenApp, OpenSettings, Flashlight, ShowAlarms, SetVolume, MediaControl, TakePhoto, CreateNote, AddToList, ShowList, CheckOff, AddReminder, GetDirections, MakeCall, SendText, ShowAgenda, AddEvent)

    fun byName(name: String): Tool? = all.firstOrNull { it.name == name }

    /** The JSON array Needle 3 gets for [tools]. */
    fun json(tools: List<Tool>): String = tools.joinToString(",\n", prefix = "[\n", postfix = "\n]") { it.schema }

    /** Whether at least half the words of [text] (an argument) are [request]'s; true without one. */
    internal fun mostlySaid(text: String?, request: String): Boolean {
        val said = words(text ?: return true)
        return said.isEmpty() || said.count { it in words(request) } * 2 >= said.size
    }

    private fun words(text: String) = Regex("""[\p{L}\p{N}]+""").findAll(text.lowercase()).map { it.value }.toSet()

    /** [this] with [actions] as buttons, if it succeeded. */
    internal fun Outcome.withActions(vararg actions: FollowUp): Outcome = if (this is Outcome.Done) copy(actions = actions.toList()) else this

    /** [this] with an "Open again" button that starts [intent] once more. */
    internal fun Outcome.again(intent: Intent): Outcome {
        val message = (this as? Outcome.Done)?.message ?: return this
        return withActions(FollowUp("Open again") { start(it.context, Intent(intent), message).again(intent) })
    }

    /**
     * Starts [intent] (the assistant's screen is in front, so Android allows it). By default in a
     * task of its own, for switching to another app. With [quietly], from the assistant's screen in
     * the assistant's own task, for requests another app handles without showing anything (set an
     * alarm, start a timer): its handler finishes back into the assistant. In a new task, HyperOS's
     * clock left the Clock app on screen after setting an alarm; Google's assistant, which doesn't,
     * starts it from its own screen.
     */
    internal fun start(context: Context, intent: Intent, done: String, quietly: Boolean = false): Outcome = try {
        if (quietly && context is Activity) context.startActivity(intent)
        else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Outcome.Done(done)
    } catch (e: ActivityNotFoundException) {
        Outcome.Failed("No app on this phone handles that")
    } catch (e: SecurityException) {
        Outcome.Failed("Not allowed: ${e.message}")
    }
}

object SetAlarm : Tool(
    name = "set_alarm",
    title = "Alarms",
    icon = R.drawable.ic_alarm,
    description = "Set an alarm in the clock app, at a time of day.",
    onByDefault = true,
    // Bars from tool-check's per-tool table (2026-10-07). Right alarms scored 0.47–0.85 with
    // five tools, wrong ones that get past the keyword and number check at most 0.24.
    defaultRunFrom = 0.4,
    examples = listOf("Wake me up at 6:45", "Alarms at 8 and 9 am"),
) {
    override val options = listOf(ClockProviders.option)

    // The time as text, parsed by the app. On the desktop check, separate hour and minute fields
    // turned "alarms at 8 and 9 am" into one alarm at 08:09 and scored single alarms around 0.3;
    // one time string got every request right (two alarms included) and scored higher. A label
    // parameter made the model invent labels ("Tomorrow morning"), so there isn't one.
    // Hindi for wake me ("6 baje utha dena", "jaga dena"): typed Hinglish without "alarm" reached
    // every tool and became a calendar event (2026-10-12).
    override val keywords = listOf("alarm", "wake", "get up", "up at", "utha", "uthana", "utha dena", "jaga", "jagana", "jaga dena")

    override val schema = """
        {"name": "set_alarm",
         "description": "Set an alarm for a time, such as wake me up at 6:45 tomorrow",
         "parameters": {"type": "object", "properties": {
           "time": {"type": "string", "description": "The time, such as 6:45 am or 7 pm"}},
           "required": ["time"]}}
    """.trimIndent()

    override fun prepare(arguments: Map<String, Any?>, env: PrepareEnv): Prepared {
        val said = arguments.text("time") ?: return Prepared.Invalid(arguments.text(TimeArgs.ASK)?.replaceFirstChar { it.uppercase() } ?: "No time given")
        val (hour, minute) = TimeOfDay.parse(said) ?: return Prepared.Invalid("\"$said\" isn't a time of day")
        val time = String.format(Locale.US, "%02d:%02d", hour, minute)
        fun set(context: android.content.Context) = Tools.start(context, Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true), "Alarm set for $time", quietly = true)
            .withActions(SHOW_ALARMS)
        // Asked for on another day than its next ring (the Clock app can't take a date): offer
        // the next ring, or a reminder at the time asked for (the user's decision, 2026-10-16).
        val wanted = arguments.text(TimeArgs.WANTED)?.let { runCatching { java.time.LocalDateTime.parse(it) }.getOrNull() }
        val reminders = env.reminders
        if (wanted != null && reminders != null) {
            val day = wanted.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", Locale.US))
            return Prepared.Ready("Set an alarm for $time?", detail = arguments.text(TimeArgs.ASK)?.replaceFirstChar { it.uppercase() },
                choices = listOf(
                    Choice("Alarm at the next $time") { set(it) },
                    Choice("Reminder on $day at $time") { _ ->
                        val reminder = reminders.add("Alarm", wanted) ?: return@Choice Outcome.Failed("Couldn't save the reminder")
                        env.remindersChanged()
                        Outcome.Done("I'll remind you on $day at $time", actions = listOf(FollowUp("Undo") { _ ->
                            reminders.remove(reminder.id)
                            env.remindersChanged()
                            Outcome.Done("Reminder removed")
                        }))
                    },
                ), cancel = "Cancel") { set(it) }
        }
        // The clock app sets the next time it's that time of day: today if it's still ahead, else tomorrow.
        return Prepared.Ready("Set an alarm for $time") { context -> set(context) }
    }

    override fun grounded(arguments: Map<String, Any?>, request: String) =
        arguments[TimeArgs.RESOLVED] == true || arguments.text("time")?.let { SaidNumbers.grounded(it, request) } ?: true

    private val SHOW_ALARMS = FollowUp("Show alarms") { Tools.start(it.context, Intent(AlarmClock.ACTION_SHOW_ALARMS), "Opened the alarms") }

    // No Undo: Android has no request to delete another app's alarm, and ACTION_DISMISS_ALARM
    // (searching by time), which switches alarms off in clock apps that support it, did nothing in
    // HyperOS's clock (2026-10-07). "Show alarms" opens the clock's list, to delete it there.
}

/** A spoken or written length of time, in seconds. */
object Durations {
    private val UNITS = mapOf(
        "h" to 3600, "hr" to 3600, "hrs" to 3600, "hour" to 3600, "hours" to 3600,
        "m" to 60, "min" to 60, "mins" to 60, "minute" to 60, "minutes" to 60,
        "s" to 1, "sec" to 1, "secs" to 1, "second" to 1, "seconds" to 1,
    )
    private val PART = Regex("""(\d+(?:\.\d+)?)\s*([a-z]+)""")

    /**
     * "2 hours", "1 hour 15 minutes", "90 seconds", "45 min", "1.5 hours", "1:30" (min:sec), "one
     * minute", "an hour and a half", "1 and a half minutes", "half a minute" → seconds; null if it
     * isn't one. The computer's model passes halves as said (audio bench, 2026-10-12).
     */
    fun parse(text: String): Int? {
        val t = text.trim().lowercase()
            // Before "and" goes and "a" becomes 1.
            .replace(Regex("""\band a half\b"""), " andahalf ")
            .replace(Regex("""\bhalf an? (hour|minute|second)\b""")) { "0.5 ${it.groupValues[1]}" }
            .replace(" and ", " ")
            .replace(SaidNumbers.WORD) { SaidNumbers.WORDS.getValue(it.value).toString() }
            // "1 andahalf minutes" and "1 hour andahalf" → 1.5 of the unit.
            .replace(Regex("""(\d+(?:\.\d+)?)\s+andahalf\s+([a-z]+)""")) { "${it.groupValues[1].toDouble() + 0.5} ${it.groupValues[2]}" }
            .replace(Regex("""(\d+(?:\.\d+)?)\s*([a-z]+)\s+andahalf""")) { "${it.groupValues[1].toDouble() + 0.5} ${it.groupValues[2]}" }
            .trim()
        Regex("""(\d+):(\d{2})""").matchEntire(t)?.let { return it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        val parts = PART.findAll(t).toList()
        if (parts.isEmpty() || parts.any { it.groupValues[2] !in UNITS }) return null
        return parts.sumOf { it.groupValues[1].toDouble() * UNITS.getValue(it.groupValues[2]) }.let { Math.round(it).toInt() }
    }
}

/**
 * The numbers a request says, as digits or words ("six", "half" past, "quarter" to), so a call's
 * numbers can be checked against it. Needle 3 sometimes fills a time or length from its examples:
 * "I hate alarms" became an alarm at 6:45 at 0.63, more than most right alarms.
 */
object SaidNumbers {
    val WORDS = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20,
        "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "ninety" to 90,
    )
    private val CLOCK_WORDS = mapOf("half" to 30, "quarter" to 15, "noon" to 12, "midnight" to 12)
    val WORD = Regex("""\b(${WORDS.keys.joinToString("|")})\b""")

    fun of(text: String): Set<Int> = Regex("""\d+|[a-z]+""").findAll(text.lowercase())
        .mapNotNull { it.value.toIntOrNull() ?: WORDS[it.value] ?: CLOCK_WORDS[it.value] }.toSet()

    /** Whether every number in [argument] is one [request] says. Zeros always are, and 13–23 are when said as 1–11 (pm). */
    fun grounded(argument: String, request: String): Boolean {
        val said = of(request)
        return of(argument).all { it == 0 || it in said || (it in 13..23 && it - 12 in said) }
    }
}

/** A spoken or written time of day as hour (0–23) and minute. */
object TimeOfDay {
    private val PATTERN = Regex("""^(\d{1,2})(?:\s*[:.h]\s*(\d{2}))?\s*(am|a\.m\.|pm|p\.m\.)?$""")

    // Words around a time that say am or pm, in English and Hindi ("subah 5 baje", "7 in the evening").
    private val MORNING = Regex("""\b(in the morning|morning|subah|subha)\b""")
    private val LATER = Regex("""\b(in the afternoon|afternoon|in the evening|evening|at night|tonight|night|dopahar|shaam|sham|raat)\b""")
    private val NIGHT = Regex("""\b(at night|tonight|night|raat)\b""")
    // Words that add nothing to the time: "5 o'clock", "5 baje", "tomorrow at 7" (the clock app
    // sets the next time it's that time of day, so tomorrow when it's already passed today).
    private val FILLER = Regex("""\b(o'clock|o’clock|oclock|o clock|baje|bje|sharp|at|tomorrow|today|kal|aaj|ka|ki|ke)\b""")

    /**
     * "8:00 AM", "08:00", "7 pm", "19:30", "6.45", "8", "5 o'clock", "five o'clock", "5 baje", "7 in
     * the evening", "subah 5", "tomorrow at 6" → (hour, minute); null if it isn't one. Without am,
     * pm or a word for the time of day, an hour up to 12 is taken as written (so "8" is 8 am, "12"
     * noon). "5 o'clock" came back from the computer for "paanch baje" (2026-10-12) and was refused.
     */
    /**
     * [parse]'s rule for an alarm's hour from the computer's time structure ([Resolve]): am or pm as
     * said; else the part of the day (morning am; afternoon and evening pm; at night 12 and 1–5 the
     * small hours, else pm); else the hour as written.
     */
    fun of(hour: Int, minute: Int, meridiem: String?, part: String?): Pair<Int, Int>? {
        if (minute !in 0..59 || hour !in 0..23) return null
        val period = meridiem ?: when (part) {
            "morning" -> "am"
            "afternoon", "evening" -> "pm"
            "night" -> if (hour == 12 || hour in 1..5) "am" else "pm"
            else -> null
        }
        var h = hour
        when {
            h > 12 || period == null -> {}
            period == "am" -> if (h == 12) h = 0
            period == "pm" -> if (h != 12) h += 12
        }
        return h to minute
    }

    fun parse(text: String): Pair<Int, Int>? {
        val lower = text.trim().lowercase()
        val hint = when {
            MORNING.containsMatchIn(lower) -> "am"
            LATER.containsMatchIn(lower) -> "pm"
            else -> null
        }
        var t = lower.replace(MORNING, " ").replace(LATER, " ").replace(FILLER, " ")
            .replace(SaidNumbers.WORD) { w -> SaidNumbers.WORDS.getValue(w.value).takeIf { it in 1..12 }?.toString() ?: w.value }
            .replace(Regex("""\s+"""), " ").trim()
        t = if (t == "noon") "12" else if (t == "midnight") "0" else t
        // The word gives am or pm when the time doesn't; at night, 12 to 5 are the small hours
        // ("12 at night" is midnight, "raat 2 baje" 2 am), as Ferb Link reads raat too.
        val small = Regex("""^(\d{1,2})""").find(t)?.groupValues?.get(1)?.toInt()?.let { it == 12 || it in 1..5 } == true
        val said = if (hint == "pm" && NIGHT.containsMatchIn(lower) && small) "am" else hint
        if (said != null && Regex("""^\d{1,2}(?:\s*[:.h]\s*\d{2})?$""").matches(t)) t = "$t $said"
        val m = PATTERN.matchEntire(t) ?: return null
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        val period = m.groupValues[3].replace(".", "")
        if (minute > 59) return null
        when (period) {
            "am" -> { if (hour !in 1..12) return null; if (hour == 12) hour = 0 }
            "pm" -> { if (hour !in 1..12) return null; if (hour != 12) hour += 12 }
            else -> if (hour > 23) return null
        }
        return hour to minute
    }
}

object SetTimer : Tool(
    name = "set_timer",
    title = "Timers",
    icon = R.drawable.ic_timer,
    description = "Start a countdown timer in the clock app.",
    onByDefault = true,
    // Right timers 0.43–0.88; none wrong once the length's numbers must be said.
    defaultRunFrom = 0.4,
    examples = listOf("Timer for 10 minutes", "Set a 90 second timer for the eggs"),
) {
    override val options = listOf(ClockProviders.option)

    const val MAX_SECONDS = 24 * 60 * 60

    // The length as text, parsed by the app (like alarm times). As a number of seconds, Needle 3
    // got the arithmetic wrong, confidently: "2 hours" became 120 s at 0.82, "45 min" 45 s. As
    // text it copied every length right on the desktop check, and scored higher. No label: it
    // added nothing the clock shows, and the model invents them.
    override val schema = """
        {"name": "set_timer",
         "description": "Start a countdown timer, such as a 10 minute timer",
         "parameters": {"type": "object", "properties": {
           "duration": {"type": "string", "description": "The length, such as 10 minutes or 90 seconds"}},
           "required": ["duration"]}}
    """.trimIndent()

    override val keywords = listOf("timer", "countdown", "count down", "remind me in", "minute", "min", "second", "sec", "hour", "hr")

    override fun prepare(arguments: Map<String, Any?>, env: PrepareEnv): Prepared {
        val said = arguments.text("duration") ?: return Prepared.Invalid("No length given")
        val total = Durations.parse(said) ?: return Prepared.Invalid("\"$said\" isn't a length of time")
        if (total !in 1..MAX_SECONDS) return Prepared.Invalid("A timer needs 1 second to 24 hours")
        val length = duration(total)
        return Prepared.Ready("Start a $length timer") { context ->
            Tools.start(context, Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, total)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true), "Timer started: $length", quietly = true)
                .withActions(FollowUp("Show timers") { Tools.start(it.context, Intent(AlarmClock.ACTION_SHOW_TIMERS), "Opened the timers") })
        }
    }

    override fun grounded(arguments: Map<String, Any?>, request: String) =
        arguments[TimeArgs.RESOLVED] == true || arguments.text("duration")?.let { SaidNumbers.grounded(it, request) } ?: true

    /** "1 h 5 min", "90 s" → "1 min 30 s". */
    fun duration(seconds: Int): String = listOfNotNull(
        (seconds / 3600).takeIf { it > 0 }?.let { "$it h" },
        (seconds / 60 % 60).takeIf { it > 0 }?.let { "$it min" },
        (seconds % 60).takeIf { it > 0 }?.let { "$it s" },
    ).joinToString(" ")
}

object ShowAlarms : Tool(
    name = "show_alarms",
    title = "Show alarms",
    icon = R.drawable.ic_alarm_list,
    description = "Open the clock app's list of alarms.",
    onByDefault = false,
    defaultRunFrom = 0.3,
    examples = listOf("Show my alarms"),
) {
    override val options = listOf(ClockProviders.option)

    override val keywords = listOf("alarm")

    override val schema = """
        {"name": "show_alarms",
         "description": "Open the list of alarms in the clock app",
         "parameters": {"type": "object", "properties": {}, "required": []}}
    """.trimIndent()

    override fun prepare(arguments: Map<String, Any?>, env: PrepareEnv) =
        Prepared.Ready("Show the alarms") { context -> Tools.start(context, Intent(AlarmClock.ACTION_SHOW_ALARMS), "Opened the alarms") }
}

object OpenApp : Tool(
    name = "open_app",
    title = "Open apps",
    icon = R.drawable.ic_apps,
    description = "Open an app on this phone by its name.",
    onByDefault = true,
    // Low: opening an app is harmless, and the keywords hold back the wrong calls ("uninstall instagram" 0.83).
    defaultRunFrom = 0.3,
    examples = listOf("Open the calculator", "Launch WhatsApp"),
) {
    override val keywords = listOf("open", "launch", "start", "go to", "run", "show")

    override val schema = """
        {"name": "open_app",
         "description": "Open an app installed on this phone by its name, such as Calculator or WhatsApp. Not for settings pages",
         "parameters": {"type": "object", "properties": {
           "app_name": {"type": "string", "description": "The app's name as the request says it, such as Camera or Calculator"}},
           "required": ["app_name"]}}
    """.trimIndent()

    override fun prepare(arguments: Map<String, Any?>, env: PrepareEnv): Prepared {
        val name = arguments.text("app_name") ?: return Prepared.Invalid("No app name")
        // "Open Bluetooth" sometimes comes here rather than to open_settings: no such app, but a
        // settings page. Then a misheard name ("ptm" for Paytm), last, so a page still wins.
        val app = env.apps.find(name) ?: OpenSettings.pageFor(name)?.let { return OpenSettings.prepare(mapOf("page" to it), env) }
            ?: env.apps.findLoosely(name) ?: return Prepared.Invalid("No app called \"$name\" on this phone")
        return Prepared.Ready("Open ${app.label}") { context ->
            val launch = context.packageManager.getLaunchIntentForPackage(app.packageName)
                ?: return@Ready Outcome.Failed("${app.label} can't be opened")
            Tools.start(context, launch, "Opened ${app.label}").again(launch)
        }
    }
}

object OpenSettings : Tool(
    name = "open_settings",
    title = "Settings pages",
    icon = R.drawable.ic_settings,
    description = "Open a page of the phone's settings, such as Wi-Fi, Bluetooth or battery.",
    onByDefault = true,
    // Questions about a setting came back as calls: "what's my wifi password" 0.56, a wrong page
    // 0.41. Right pages mostly score 0.73+.
    defaultRunFrom = 0.6,
    examples = listOf("Open Wi-Fi settings", "Show me the battery settings"),
) {
    /** The page names Needle 3 can pick, and the Settings action for each. */
    val PAGES: Map<String, String> = linkedMapOf(
        "main" to Settings.ACTION_SETTINGS,
        "wi-fi" to Settings.ACTION_WIFI_SETTINGS,
        "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "mobile_network" to Settings.ACTION_WIRELESS_SETTINGS,
        "airplane_mode" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
        "display" to Settings.ACTION_DISPLAY_SETTINGS,
        "sound" to Settings.ACTION_SOUND_SETTINGS,
        "battery" to Intent.ACTION_POWER_USAGE_SUMMARY,
        "notifications" to Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS,
        "apps" to Settings.ACTION_APPLICATION_SETTINGS,
        "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        "security" to Settings.ACTION_SECURITY_SETTINGS,
        "privacy" to Settings.ACTION_PRIVACY_SETTINGS,
        "date_time" to Settings.ACTION_DATE_SETTINGS,
        "language" to Settings.ACTION_LOCALE_SETTINGS,
        "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
        "nfc" to Settings.ACTION_NFC_SETTINGS,
        "developer_options" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
    )

    /** Other ways the model might name a page. */
    private val ALIASES = mapOf("wifi" to "wi-fi", "wlan" to "wi-fi", "mobile_data" to "mobile_network", "data" to "mobile_network",
        "volume" to "sound", "settings" to "main")

    /** The page [name] means ("Bluetooth", "wifi", "Settings"), if it's one. */
    fun pageFor(name: String): String? = name.lowercase().trim().replace(' ', '_').let { ALIASES[it] ?: it }.takeIf { it in PAGES }

    override val keywords = listOf("setting", "wifi", "wi-fi", "bluetooth", "battery", "display", "brightness", "sound", "location", "storage", "security", "privacy", "notification", "apps", "date", "time", "language", "accessibility", "nfc", "developer", "airplane", "aeroplane", "flight mode", "mobile network", "mobile data")

    override val schema = """
        {"name": "open_settings",
         "triggers": ["\\bsettings?\\b"],
         "description": "Open the phone's settings, or one of its pages such as Wi-Fi, Bluetooth, display, sound or battery",
         "parameters": {"type": "object", "properties": {
           "page": {"type": "string", "enum": [${PAGES.keys.joinToString { "\"$it\"" }}], "description": "Which page; main for the settings app itself"}},
           "required": ["page"]}}
    """.trimIndent()

    override fun prepare(arguments: Map<String, Any?>, env: PrepareEnv): Prepared {
        val page = arguments.text("page")?.let(::pageFor) ?: arguments.text("page")
        val action = PAGES[page] ?: return Prepared.Invalid("\"$page\" isn't a settings page this assistant knows")
        val name = page!!.replace('_', ' ')
        if (page == "main") return Prepared.Ready("Open Settings") { context -> Tools.start(context, Intent(action), "Opened Settings").again(Intent(action)) }
        return Prepared.Ready("Open the $name settings") { context ->
            val intent = Intent(action)
            // Some pages don't exist on every phone; the main settings screen is the fallback.
            if (intent.resolveActivity(context.packageManager) != null) Tools.start(context, intent, "Opened the $name settings").again(intent)
            else Tools.start(context, Intent(Settings.ACTION_SETTINGS), "This phone has no $name page; opened Settings")
        }
    }
}

object Flashlight : Tool(
    name = "set_flashlight",
    title = "Flashlight",
    icon = R.drawable.ic_flashlight,
    description = "Turn the flashlight (torch) on or off.",
    onByDefault = true,
    // Talk about the torch reads as "turn it on": "my flashlight is broken" 0.70, "buy a torch" 0.62.
    // Right calls score 0.82+.
    defaultRunFrom = 0.75,
    examples = listOf("Turn on the flashlight", "Torch off"),
) {
    override val keywords = listOf("torch", "flashlight", "flash light", "light", "dark")

    override val schema = """
        {"name": "set_flashlight",
         "description": "Turn the phone's flashlight (torch) on or off",
         "parameters": {"type": "object", "properties": {
           "on": {"type": "boolean", "description": "true to turn it on, false to turn it off"}},
           "required": ["on"]}}
    """.trimIndent()

    override fun prepare(arguments: Map<String, Any?>, env: PrepareEnv): Prepared {
        val on = arguments.bool("on") ?: return Prepared.Invalid("On or off wasn't given")
        return Prepared.Ready("Turn the flashlight ${if (on) "on" else "off"}", opensApp = false) { context -> torch(context, on) }
    }

    /** Switches the torch, offering to switch it back. */
    fun torch(context: Context, on: Boolean): Outcome {
        val cameras = context.getSystemService(CameraManager::class.java)
        val withFlash = cameras.cameraIdList.firstOrNull {
            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return Outcome.Failed("This phone has no flashlight")
        return try {
            cameras.setTorchMode(withFlash, on)
            Outcome.Done("Flashlight ${if (on) "on" else "off"}", listOf(FollowUp(if (on) "Turn off" else "Turn on") { torch(it.context, !on) }))
        } catch (e: Exception) {
            // e.g. the camera is in use by another app.
            Outcome.Failed("Couldn't switch the flashlight: ${e.message}")
        }
    }
}

object SetVolume : Tool(
    name = "set_volume",
    title = "Media volume",
    icon = R.drawable.ic_volume,
    description = "Set or mute the volume of music, videos and games.",
    onByDefault = false,
    // Right calls 0.59–0.95, none wrong; a little higher than the harmless tools, since a wrong
    // volume can be loud.
    defaultRunFrom = 0.5,
    examples = listOf("Set volume to 40", "Mute the media"),
) {
    private const val STREAM = AudioManager.STREAM_MUSIC

    override val keywords = listOf("volume", "mute", "unmute", "louder", "quieter", "loud", "quiet", "sound")

    // Media only, with the percentage as the one parameter. With a stream to choose too (media,
    // ring, alarm, notification), "volume to 50" and "max volume" got no call: the model had no
    // stream to fill in.
    // "Set or mute" at the start of the description took "mute", "mute the volume", "mute
    // please" from 0.44–0.64 to 0.56–0.78. Saying "mute means 0" or "mute (volume 0)" instead, or
    // in the percent's description, lowered them (one to no call).
    override val schema = """
        {"name": "set_volume",
         "description": "Set or mute the media volume as a percentage, such as volume to 40; 0 mutes it",
         "parameters": {"type": "object", "properties": {
           "percent": {"type": "integer", "minimum": 0, "maximum": 100, "description": "The volume, 0 to 100"}},
           "required": ["percent"]}}
    """.trimIndent()

    /** Other volumes this tool doesn't change: a request naming one asks first rather than change the media volume. */
    private val OTHER_VOLUMES = Regex("""\b(ring(er|tone)?|alarms?|notifications?|calls?)\b""", RegexOption.IGNORE_CASE)

    override fun grounded(arguments: Map<String, Any?>, request: String) = !OTHER_VOLUMES.containsMatchIn(request)

    override fun prepare(arguments: Map<String, Any?>, env: PrepareEnv): Prepared {
        val percent = arguments.int("percent") ?: return Prepared.Invalid("No volume given")
        if (percent !in 0..100) return Prepared.Invalid("$percent % isn't 0–100")
        return Prepared.Ready("Set the media volume to $percent %", opensApp = false) { context ->
            val message = set(context, percent / 100f)
            Outcome.Done(message, level = Level(level(context), ::set, watch))
        }
    }

    /**
     * The media volume as it changes outside Ferb (volume keys, another app, the system panel):
     * Android's VOLUME_CHANGED broadcast, a protected system broadcast that needs no permission,
     * for the media stream only. The volume is read again on each, so the slider shows the step.
     */
    internal val watch = LevelWatch(
        start = { context, onChange ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.getIntExtra(EXTRA_STREAM, STREAM) == STREAM) onChange(level(context))
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(VOLUME_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            val stop: () -> Unit = { context.unregisterReceiver(receiver) }
            stop
        },
        describe = { "Media volume ${(it * 100).roundToInt()} %" },
    )
    /** AudioManager's hidden VOLUME_CHANGED_ACTION and its stream extra. */
    private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
    private const val EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE"

    /** The media volume now, 0..1. */
    private fun level(context: Context): Float {
        val audio = context.getSystemService(AudioManager::class.java)
        return audio.getStreamVolume(STREAM).toFloat() / audio.getStreamMaxVolume(STREAM)
    }

    /** Sets the media volume to [fraction] of its range, to the nearest of its steps. */
    private fun set(context: Context, fraction: Float): String {
        val audio = context.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(STREAM)
        val step = (fraction.coerceIn(0f, 1f) * max).roundToInt()
        audio.setStreamVolume(STREAM, step, 0)
        return "Media volume ${(step * 100.0 / max).roundToInt()} %"
    }
}

object MediaControl : Tool(
    name = "control_media",
    title = "Media controls",
    icon = R.drawable.ic_music,
    description = "Play, pause or skip whatever music or video is playing.",
    onByDefault = false,
    // "resume" became pause at 0.56, and at 0.66 when routed to it alone; right calls 0.69+ when routed.
    defaultRunFrom = 0.7,
    examples = listOf("Pause the music", "Next song"),
) {
    val ACTIONS = linkedMapOf(
        "play" to KeyEvent.KEYCODE_MEDIA_PLAY,
        "pause" to KeyEvent.KEYCODE_MEDIA_PAUSE,
        "next" to KeyEvent.KEYCODE_MEDIA_NEXT,
        "previous" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
    )

    override val keywords = listOf("play", "pause", "resume", "music", "song", "track", "next", "previous", "skip", "stop")

    override val schema = """
        {"name": "control_media",
         "description": "Play, pause, or skip to the next or previous song or track of the music playing",
         "parameters": {"type": "object", "properties": {
           "action": {"type": "string", "enum": [${ACTIONS.keys.joinToString { "\"$it\"" }}], "description": "play, pause, next track or previous track"}},
           "required": ["action"]}}
    """.trimIndent()

    override fun prepare(arguments: Map<String, Any?>, env: PrepareEnv): Prepared {
        val action = arguments.text("action")?.lowercase()
        val key = ACTIONS[action] ?: return Prepared.Invalid("\"$action\" isn't one of ${ACTIONS.keys.joinToString()}")
        return Prepared.Ready("Media: $action", opensApp = false) { context -> press(context, action!!) }
    }

    /** What undoes each action, as a button. */
    private val OPPOSITE = mapOf("play" to "pause", "pause" to "play", "next" to "previous", "previous" to "next")

    private fun press(context: Context, action: String): Outcome {
        val key = ACTIONS.getValue(action)
        val audio = context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        val back = OPPOSITE.getValue(action)
        return Outcome.Done("Sent $action to the media player", listOf(FollowUp(back.replaceFirstChar { it.uppercase() }) { press(it.context, back) }))
    }
}

object TakePhoto : Tool(
    name = "open_camera",
    title = "Camera",
    icon = R.drawable.ic_photo_camera,
    description = "Open the camera app, ready to take a photo.",
    onByDefault = false,
    // "which camera is better" opened it at 0.47, and at 0.64 when routed; right calls 0.80+ when routed.
    defaultRunFrom = 0.7,
    examples = listOf("Take a picture", "Open the camera"),
) {
    // No photo/video choice: on the desktop check, Needle 3 picked the mode backwards about half the
    // time (whether as an enum or a boolean); switching to video in the camera app is one tap.
    override val keywords = listOf("camera", "photo", "picture", "pic", "selfie", "video", "snap")

    override val schema = """
        {"name": "open_camera",
         "description": "Open the camera app to take a photo or a video",
         "parameters": {"type": "object", "properties": {}, "required": []}}
    """.trimIndent()

    override fun prepare(arguments: Map<String, Any?>, env: PrepareEnv) = Prepared.Ready("Open the camera") { context ->
        Tools.start(context, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Opened the camera")
            .again(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
    }
}

object CreateNote : Tool(
    name = "create_note",
    title = "Notes",
    icon = R.drawable.ic_note,
    description = "Take a note in your notes app, such as Keep.",
    onByDefault = false,
    // On tool-check (2026-10-08), right notes scored 0.41–0.79 and took the text cleanly ("jot down
    // that the wifi password is on the fridge" → "the wifi password is on the fridge"); the worst
    // wrong one, "what's a quarter note", 0.19.
    defaultRunFrom = 0.4,
    examples = listOf("Note that the car is parked on level 3", "Take a note: buy milk"),
) {
    /**
     * Google's "create note" action, the one its "Take a note" voice command sends; Keep and other
     * notes apps answer it. With several, Android asks which one. Keep (2026-10-08) doesn't save it:
     * it opens a small dialog with the text filled in, for the user to save.
     */
    const val ACTION = "com.google.android.gms.actions.CREATE_NOTE"

    override val keywords = listOf("note", "jot", "write down", "take down")

    override val schema = """
        {"name": "create_note",
         "description": "Take a note, such as note that the car is parked on level 3",
         "parameters": {"type": "object", "properties": {
           "text": {"type": "string", "description": "What to note, in the request's words"}},
           "required": ["text"]}}
    """.trimIndent()

    /** At least half the note's words must be the request's: a note Needle 3 made up asks first. */
    override fun grounded(arguments: Map<String, Any?>, request: String) = Tools.mostlySaid(arguments.text("text"), request)

    /** Where notes go: the notes app (Google Keep), as before, or `.md` files in a folder of the user's Obsidian vault, opened in Obsidian. */
    const val KEEP = "keep"
    const val FOLDER = "folder"
    /** The folder option's name, wherever the user sees it. */
    const val MARKDOWN_NOTES = "Markdown notes (powered by Obsidian)"
    val saveTo = ToolOption.Choice(
        key = "save_to",
        title = "Save notes to",
        items = listOf(
            ToolOption.Choice.Item(KEEP, "Your notes app", "Such as Google Keep, to save there"),
            ToolOption.Choice.Item(FOLDER, MARKDOWN_NOTES, "Markdown files in a folder of your Obsidian vault, opened in Obsidian", needsObsidian = true),
        ),
        default = KEEP,
        // The user's finding (2026-10-19): links reach a vault Obsidian has loaded; forum reports
        // say links to a never-opened vault fail silently on mobile.
        note = "Notes open in Obsidian when their vault has been opened in Obsidian at least once.",
    )
    val folderOption = ToolOption.NotesFolder(key = "folder", title = "Notes folder")
    override val options = listOf(saveTo, folderOption)

    /** A note's file name: its first line, up to six words. */
    fun titleFor(text: String): String = text.lineSequence().first().split(Regex("""\s+""")).filter { it.isNotEmpty() }.take(6).joinToString(" ")

    /** A new note's file: Ferb's id in the frontmatter (it finds the note again after a rename), then the text. */
    fun noteFile(text: String, id: String): String = "---\n${NoteText.ID}: $id\n---\n$text\n"

    override fun prepare(arguments: Map<String, Any?>, env: PrepareEnv): Prepared {
        val text = arguments.text("text") ?: return Prepared.Invalid("No text for the note")
        val toFolder = env.options[saveTo.key] == FOLDER
        if (toFolder && env.notes.ready) {
            return Prepared.Ready("Save a note: \u201c$text\u201d", opensApp = false) { _ ->
                val written = noteFile(text, NoteStore.newId())
                val note = env.notes.create(titleFor(text), written) ?: return@Ready Outcome.Failed("Couldn't save it in the notes folder")
                Outcome.Done("Saved \u201c${note.name}\u201d in your notes", actions = listOf(FollowUp("Open") { e ->
                    env.openNote(e.context, note)
                    Outcome.Done("Saved \u201c${note.name}\u201d in your notes")
                }, FollowUp("Undo") {
                    // Only the note as Ferb wrote it: once changed elsewhere (Obsidian), it stays.
                    if (env.notes.read(note) == written && env.notes.delete(note)) Outcome.Done("Note removed") else Outcome.Failed("It has changed since, so it stays")
                }))
            }
        }
        // The notes app; set to a folder but none picked yet, the reply also asks for one.
        return Prepared.Ready("Take a note: \u201c$text\u201d", needs = if (toFolder) listOf(NoteStore.NEEDS_FOLDER) else emptyList()) { context ->
            // Notes apps declare the action with a MIME type ("*/*" in Google's docs), so without one nothing matches.
            val intent = Intent(ACTION).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            val handlers = context.packageManager.queryIntentActivities(intent, 0)
            when (handlers.size) {
                0 -> Outcome.Failed("No notes app on this phone takes notes from other apps (Google Keep does)")
                1 -> Tools.start(context, intent, "Note opened in ${handlers[0].loadLabel(context.packageManager)}, to save there", quietly = true)
                else -> Tools.start(context, intent, "Note opened in your notes app, to save there", quietly = true)
            }
        }
    }
}

object GetDirections : Tool(
    name = "get_directions",
    title = "Directions",
    icon = R.drawable.ic_directions,
    description = "Show directions to a place in Google Maps, to check and start.",
    onByDefault = false,
    // On tool-check (2026-10-08), right calls scored 0.38–0.91 ("directions to the airport" 0.51);
    // the worst wrong ones, "how do I get to level 5 in this game" 0.56 and "directions for this
    // recipe" 0.35. A wrong one only opens Maps, to close.
    defaultRunFrom = 0.6,
    examples = listOf("Show me the way to the Taj Mahal", "How do I get to Lodhi Garden"),
) {
    /**
     * Travel modes as Google Maps' links name them, and the request's words that pick each. The
     * app reads the mode, not Needle 3: given a "mode" parameter (an enum, optional, "only if the
     * request says"), it filled one in for every request, "driving" or "walking", and right calls
     * without a mode in the request fell from 0.48–0.91 to 0.14–0.37 (tool-check, 2026-10-08).
     * "Bike" is a motorbike, as in India.
     */
    val modes = linkedMapOf(
        "walking" to Regex("""\b(walk|walking|on foot|by foot)\b"""),
        "bicycling" to Regex("""\b(cycle|cycling|bicycle|by cycle)\b"""),
        "two-wheeler" to Regex("""\b(scooter|scooty|bike|motorbike|motorcycle|two[- ]wheeler)\b"""),
        "transit" to Regex("""\b(bus|train|metro|transit|public transport)\b"""),
        "driving" to Regex("""\b(drive|driving|car)\b"""),
    )

    /** The travel mode [request] names, if any. */
    fun modeIn(request: String): String? = modes.entries.firstOrNull { it.value.containsMatchIn(request.lowercase()) }?.key

    override fun fromRequest(arguments: Map<String, Any?>, request: String) =
        (arguments - "mode") + listOfNotNull(modeIn(request)?.let { "mode" to it })

    // "Route to", not "route": "what's the route of the tour de france" got directions at 0.75.
    // "Get directions in Google Maps to a place…" as the description lost "take me to the nearest
    // petrol pump" and raised the wrong calls.
    override val keywords = listOf("direction", "navigate", "route to", "take me to", "get to", "way to", "how far", "go to", "reach")

    override val schema = """
        {"name": "get_directions",
         "description": "Get directions to a place, such as directions to the airport",
         "parameters": {"type": "object", "properties": {
           "destination": {"type": "string", "description": "The place, in the request's words"}},
           "required": ["destination"]}}
    """.trimIndent()

    /** At least half the destination's words must be the request's: a place Needle 3 made up asks first. */
    override fun grounded(arguments: Map<String, Any?>, request: String) = Tools.mostlySaid(arguments.text("destination"), request)

    /**
     * Google Maps' directions link, which shows the route to check before starting (the
     * `google.navigation:` intent starts at once). Maps owns the link, so it opens there without
     * asking; without Maps, in the browser. Maps looks the place up, so this app stays offline.
     */
    fun link(destination: String, mode: String?): Uri = Uri.Builder().scheme("https").authority("www.google.com")
        .path("/maps/dir/").appendQueryParameter("api", "1").appendQueryParameter("destination", destination)
        .apply { if (mode != null) appendQueryParameter("travelmode", mode) }.build()

    override fun prepare(arguments: Map<String, Any?>, env: PrepareEnv): Prepared {
        val destination = arguments.text("destination") ?: return Prepared.Invalid("No place given")
        // Without one, Maps uses the user's usual mode.
        val mode = arguments.text("mode")?.takeIf { it in modes }
        val by = mode?.let { " ($it)" } ?: ""
        return Prepared.Ready("Directions to \u201c$destination\u201d$by") { context ->
            val intent = Intent(Intent.ACTION_VIEW, link(destination, mode))
            Tools.start(context, intent, "Opened directions to \u201c$destination\u201d").again(intent)
        }
    }
}

/** What [MakeCall] and [SendText] say without the contacts permission. */
private const val NO_CONTACTS = "Ferb needs your contacts to find who you mean. They stay on this phone."

/** Why [MakeCall] and [SendText] ask first. */
private const val WRONG_PERSON = "Ferb may pick the wrong person. Without asking, the best match opens straight away, " +
    "and you only check it before you press call or send."

/** A typed number ("call 98765 43210"), not a name: five digits or more. */
private fun numberIn(text: String): String? = text.filter { it.isDigit() || it == '+' }.takeIf { it.count(Char::isDigit) >= 5 }

object MakeCall : Tool(
    name = "make_call",
    title = "Calls",
    icon = R.drawable.ic_call,
    description = "Find a contact and call the one you pick. Without asking, it only opens the dialer.",
    onByDefault = false,
    // Only used if the user stops it asking. On tool-check (2026-10-08), right calls 0.57–0.93 (two
    // calls at once, 0.19); wrong ones 0.18–0.64, and "the dial on my watch is broken" called the
    // example's "Priya" (0.41), which the name check holds back.
    defaultRunFrom = 0.7,
    // A wrong contact is embarrassing, so it shows the best match to tap (or the dialer) first.
    confirmByDefault = true,
    examples = listOf("Call Kritika", "Dial Mum"),
) {
    override val permissions = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)

    override val whyAsk = WRONG_PERSON

    override val keywords = listOf("call", "dial", "phone", "ring")

    override val schema = """
        {"name": "make_call",
         "description": "Call a person, such as call Priya",
         "parameters": {"type": "object", "properties": {
           "name": {"type": "string", "description": "Who to call, in the request's words"}},
           "required": ["name"]}}
    """.trimIndent()

    override fun grounded(arguments: Map<String, Any?>, request: String) = Tools.mostlySaid(arguments.text("name"), request)

    /** The dialer with [number] typed in (ACTION_DIAL needs no permission; the user presses call). */
    private fun dial(number: String?) = Intent(Intent.ACTION_DIAL, number?.let { Uri.fromParts("tel", it, null) })

    private val openDialer = Choice("Open dialer") { context -> Tools.start(context, dial(null), "Opened the dialer") }

    /**
     * Calls [number] at once (ACTION_CALL): for a contact the user picked, which is the
     * confirmation. Without the call permission, the dialer with the number instead.
     */
    private fun call(context: Context, number: String, who: String): Outcome =
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            Tools.start(context, Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)), "Calling $who")
        } else {
            Tools.start(context, dial(number), "Opened the dialer for $who: press call (allow calls on this tool's page to skip this)")
        }

    /** Without asking (the user turned that off), the best match only goes to the dialer: nobody is rung unpicked. */
    private fun dialOnly(number: String, who: String): (Context) -> Outcome =
        { context -> Tools.start(context, dial(number), "Opened the dialer for $who: press call") }

    override fun prepare(arguments: Map<String, Any?>, env: PrepareEnv): Prepared {
        val name = arguments.text("name") ?: return Prepared.Invalid("No one to call")
        numberIn(name)?.let { number ->
            return Prepared.Ready("Call $number?", choices = listOf(Choice(number) { call(it, number, number) }), other = openDialer,
                cancel = "Cancel", run = dialOnly(number, number))
        }
        // The best match only, one tap to call; anyone else is found in the dialer.
        // Without the contacts: the dialer, to find them there.
        val contact = (env.contacts.find(name, limit = 1) ?: return Prepared.Ready("Call \u201c$name\u201d from the dialer", detail = NO_CONTACTS,
            confirm = "Open dialer", cancel = "Cancel", needs = listOf(Manifest.permission.READ_CONTACTS), run = openDialer.run)).firstOrNull()
            ?: return Prepared.Ready("No contact matches \u201c$name\u201d", confirm = "Open dialer", cancel = "Cancel", run = openDialer.run)
        val choice = Choice(contact.name, listOfNotNull(contact.kind, contact.number).joinToString(" \u00b7 ")) { call(it, contact.number, contact.name) }
        return Prepared.Ready("Call \u201c$name\u201d?", choices = listOf(choice), other = openDialer, cancel = "Cancel",
            run = dialOnly(contact.number, contact.name))
    }
}

object SendText : Tool(
    name = "send_text",
    title = "Messages",
    icon = R.drawable.ic_sms,
    description = "Write a text message to a contact, to check and send in your messages app.",
    onByDefault = false,
    // Only used if the user stops it asking. On tool-check (2026-10-08), every message came back
    // clean ("send a text to Rahul saying the meeting is at 5" → "The meeting is at 5"), at
    // 0.34–0.89; the one wrong call, "I got a weird message from the bank", 0.19.
    defaultRunFrom = 0.45,
    confirmByDefault = true,
    examples = listOf("Text Priya I'm running late", "Message Mum that I reached safely"),
) {
    override val permissions = listOf(Manifest.permission.READ_CONTACTS)

    override val whyAsk = WRONG_PERSON

    override val keywords = listOf("text", "message", "sms", "msg", "tell")

    override val schema = """
        {"name": "send_text",
         "description": "Send a text message to a person, such as text Priya I'm running late",
         "parameters": {"type": "object", "properties": {
           "name": {"type": "string", "description": "Who to send it to, in the request's words"},
           "message": {"type": "string", "description": "The message, in the request's words"}},
           "required": ["name", "message"]}}
    """.trimIndent()

    override fun grounded(arguments: Map<String, Any?>, request: String) =
        Tools.mostlySaid(arguments.text("name"), request) && Tools.mostlySaid(arguments.text("message"), request)

    /**
     * The messages app with the text written (ACTION_SENDTO needs no permission; the user presses
     * send); without a [number], it asks who to send it to.
     */
    private fun compose(number: String?, message: String) =
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number.orEmpty()))).putExtra("sms_body", message)

    override fun prepare(arguments: Map<String, Any?>, env: PrepareEnv): Prepared {
        val name = arguments.text("name") ?: return Prepared.Invalid("No one to send it to")
        val message = arguments.text("message") ?: return Prepared.Invalid("No message to send")
        val draft = "\u201c$message\u201d"
        val number = numberIn(name)
        // Without the contacts: the message written in the messages app, to pick them there.
        val contact = if (number == null) (env.contacts.find(name, limit = 1) ?: return Prepared.Ready("Write to \u201c$name\u201d in your messages app",
            detail = "$draft\n$NO_CONTACTS", confirm = "Open messages", cancel = "Cancel", needs = listOf(Manifest.permission.READ_CONTACTS)) { context ->
            Tools.start(context, compose(null, message), "Message written: choose who to send it to, then send")
        }).firstOrNull() else null
        val to = when {
            number != null -> number
            contact != null -> "${contact.name} \u00b7 ${contact.number}"
            else -> return Prepared.Ready("No contact matches \u201c$name\u201d: choose in your messages app", detail = draft,
                confirm = "Open messages", cancel = "Cancel") { context ->
                Tools.start(context, compose(null, message), "Message written: choose who to send it to, then send")
            }
        }
        return Prepared.Ready("Text $to", detail = draft, confirm = "Proceed", cancel = "Cancel") { context ->
            Tools.start(context, compose(number ?: contact!!.number, message), "Message written to ${contact?.name ?: number}: press send")
        }
    }
}
