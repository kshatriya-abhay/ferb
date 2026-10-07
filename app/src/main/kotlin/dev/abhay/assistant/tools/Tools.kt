package dev.abhay.assistant.tools

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import dev.abhay.assistant.R
import dev.abhay.assistant.tools.Tools.again
import dev.abhay.assistant.tools.Tools.withActions
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The assistant's tools. Each is one narrow action, with bounds and enums in its schema and formats
 * in its descriptions, as Cactus advises for Needle. With five or fewer turned on, Needle 3 sees all
 * of them on every request; with more, it sees the five that best match the request.
 *
 * All of them work offline and for any app: they're intents to other apps (clock, settings,
 * launcher apps) or plain Android APIs (torch, volume, media keys). None needs the assistant role.
 */
object Tools {
    val all: List<Tool> = listOf(SetAlarm, SetTimer, OpenApp, OpenSettings, Flashlight, ShowAlarms, SetVolume, MediaControl, TakePhoto)

    fun byName(name: String): Tool? = all.firstOrNull { it.name == name }

    /** The JSON array Needle 3 gets for [tools]. */
    fun json(tools: List<Tool>): String = tools.joinToString(",\n", prefix = "[\n", postfix = "\n]") { it.schema }

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
    defaultRunFrom = 0.4,
    examples = listOf("Wake me up at 6:45", "Alarms at 8 and 9 am"),
) {
    override val options = listOf(ClockProviders.option)

    // The time as text, parsed by the app. On the desktop check, separate hour and minute fields
    // turned "alarms at 8 and 9 am" into one alarm at 08:09 and scored single alarms around 0.3;
    // one time string got every request right (two alarms included) and scored higher. A label
    // parameter made the model invent labels ("Tomorrow morning"), so there isn't one.
    override val schema = """
        {"name": "set_alarm",
         "description": "Set an alarm for a time, such as wake me up at 6:45 tomorrow",
         "parameters": {"type": "object", "properties": {
           "time": {"type": "string", "description": "The time, such as 6:45 am or 7 pm"}},
           "required": ["time"]}}
    """.trimIndent()

    override fun prepare(arguments: Map<String, Any?>, apps: InstalledApps): Prepared {
        val said = arguments.text("time") ?: return Prepared.Invalid("No time given")
        val (hour, minute) = TimeOfDay.parse(said) ?: return Prepared.Invalid("\"$said\" isn't a time of day")
        val time = String.format(Locale.US, "%02d:%02d", hour, minute)
        // The clock app sets the next time it's that time of day: today if it's still ahead, else tomorrow.
        return Prepared.Ready("Set an alarm for $time") { context ->
            Tools.start(context, Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true), "Alarm set for $time", quietly = true)
                .withActions(SHOW_ALARMS)
        }
    }

    private val SHOW_ALARMS = FollowUp("Show alarms") { Tools.start(it.context, Intent(AlarmClock.ACTION_SHOW_ALARMS), "Opened the alarms") }

    // No Undo: Android has no request to delete another app's alarm, and ACTION_DISMISS_ALARM
    // (searching by time), which switches alarms off in clock apps that support it, did nothing in
    // HyperOS's clock (2026-10-07). "Show alarms" opens the clock's list, to delete it there.
}

/** A spoken or written time of day as hour (0–23) and minute. */
object TimeOfDay {
    private val PATTERN = Regex("""^(\d{1,2})(?:\s*[:.h]\s*(\d{2}))?\s*(am|a\.m\.|pm|p\.m\.)?$""")

    /**
     * "8:00 AM", "08:00", "7 pm", "19:30", "6.45", "8" → (hour, minute); null if it isn't one.
     * Without am or pm, an hour up to 12 is taken as written (so "8" is 8 am, "12" noon).
     */
    fun parse(text: String): Pair<Int, Int>? {
        val t = text.trim().lowercase().let { if (it == "noon") "12" else if (it == "midnight") "0" else it }
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
    defaultRunFrom = 0.4,
    examples = listOf("Timer for 10 minutes", "Set a 90 second timer for the eggs"),
) {
    override val options = listOf(ClockProviders.option)

    const val MAX_SECONDS = 24 * 60 * 60

    override val schema = """
        {"name": "set_timer",
         "description": "Start a countdown timer for a length of time, such as a 10 minute timer",
         "parameters": {"type": "object", "properties": {
           "seconds": {"type": "integer", "minimum": 1, "maximum": $MAX_SECONDS, "description": "Total length in seconds: 90 seconds is 90, 10 minutes is 600, 1 hour 15 minutes is 4500"},
           "label": {"type": "string", "description": "What the timer is for, only if the request says"}},
           "required": ["seconds"]}}
    """.trimIndent()

    override fun prepare(arguments: Map<String, Any?>, apps: InstalledApps): Prepared {
        val total = arguments.int("seconds") ?: return Prepared.Invalid("No length given")
        if (total !in 1..MAX_SECONDS) return Prepared.Invalid("A timer needs 1 second to 24 hours, not $total s")
        val label = arguments.text("label")
        val length = duration(total)
        return Prepared.Ready("Start a $length timer" + (label?.let { " ($it)" } ?: "")) { context ->
            Tools.start(context, Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, total)
                .apply { label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true), "Timer started: $length", quietly = true)
                .withActions(FollowUp("Show timers") { Tools.start(it.context, Intent(AlarmClock.ACTION_SHOW_TIMERS), "Opened the timers") })
        }
    }

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

    override val schema = """
        {"name": "show_alarms",
         "description": "Open the list of alarms in the clock app",
         "parameters": {"type": "object", "properties": {}, "required": []}}
    """.trimIndent()

    override fun prepare(arguments: Map<String, Any?>, apps: InstalledApps) =
        Prepared.Ready("Show the alarms") { context -> Tools.start(context, Intent(AlarmClock.ACTION_SHOW_ALARMS), "Opened the alarms") }
}

object OpenApp : Tool(
    name = "open_app",
    title = "Open apps",
    icon = R.drawable.ic_apps,
    description = "Open an app on this phone by its name.",
    onByDefault = true,
    defaultRunFrom = 0.3,
    examples = listOf("Open the calculator", "Launch WhatsApp"),
) {
    override val schema = """
        {"name": "open_app",
         "description": "Open an app installed on this phone by its name, such as Calculator or WhatsApp. Not for settings pages",
         "parameters": {"type": "object", "properties": {
           "app_name": {"type": "string", "description": "The app's name as the request says it, such as Camera or Calculator"}},
           "required": ["app_name"]}}
    """.trimIndent()

    override fun prepare(arguments: Map<String, Any?>, apps: InstalledApps): Prepared {
        val name = arguments.text("app_name") ?: return Prepared.Invalid("No app name")
        // "Open Bluetooth" sometimes comes here rather than to open_settings: no such app, but a settings page.
        val app = apps.find(name) ?: return OpenSettings.pageFor(name)?.let { OpenSettings.prepare(mapOf("page" to it), apps) }
            ?: Prepared.Invalid("No app called \"$name\" on this phone")
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
    defaultRunFrom = 0.3,
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

    override val schema = """
        {"name": "open_settings",
         "description": "Open the phone's settings, or one of its pages such as Wi-Fi, Bluetooth, display, sound or battery",
         "parameters": {"type": "object", "properties": {
           "page": {"type": "string", "enum": [${PAGES.keys.joinToString { "\"$it\"" }}], "description": "Which page; main for the settings app itself"}},
           "required": ["page"]}}
    """.trimIndent()

    override fun prepare(arguments: Map<String, Any?>, apps: InstalledApps): Prepared {
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
    defaultRunFrom = 0.3,
    examples = listOf("Turn on the flashlight", "Torch off"),
) {
    override val schema = """
        {"name": "set_flashlight",
         "description": "Turn the phone's flashlight (torch) on or off, for light",
         "parameters": {"type": "object", "properties": {
           "on": {"type": "boolean", "description": "true to turn it on, false to turn it off"}},
           "required": ["on"]}}
    """.trimIndent()

    override fun prepare(arguments: Map<String, Any?>, apps: InstalledApps): Prepared {
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
    title = "Volume",
    icon = R.drawable.ic_volume,
    description = "Set the volume of media, ringtone, alarms or notifications.",
    onByDefault = false,
    defaultRunFrom = 0.3,
    examples = listOf("Set the media volume to 30 percent", "Mute the ringer"),
) {
    val STREAMS = linkedMapOf(
        "media" to AudioManager.STREAM_MUSIC,
        "ring" to AudioManager.STREAM_RING,
        "alarm" to AudioManager.STREAM_ALARM,
        "notification" to AudioManager.STREAM_NOTIFICATION,
    )

    override val schema = """
        {"name": "set_volume",
         "description": "Set a volume of this phone as a percentage; 0 mutes it",
         "parameters": {"type": "object", "properties": {
           "stream": {"type": "string", "enum": [${STREAMS.keys.joinToString { "\"$it\"" }}], "description": "Which volume: media for music and videos, ring for calls"},
           "percent": {"type": "integer", "minimum": 0, "maximum": 100, "description": "The volume, 0 to 100"}},
           "required": ["stream", "percent"]}}
    """.trimIndent()

    override fun prepare(arguments: Map<String, Any?>, apps: InstalledApps): Prepared {
        val name = arguments.text("stream")?.lowercase() ?: "media"
        val stream = STREAMS[name] ?: return Prepared.Invalid("\"$name\" isn't one of ${STREAMS.keys.joinToString()}")
        val percent = arguments.int("percent") ?: return Prepared.Invalid("No volume given")
        if (percent !in 0..100) return Prepared.Invalid("$percent % isn't 0–100")
        return Prepared.Ready("Set the $name volume to $percent %", opensApp = false) { context ->
            val audio = context.getSystemService(AudioManager::class.java)
            val max = audio.getStreamMaxVolume(stream)
            volume(context, name, stream, (max * percent / 100.0).roundToInt(), undoLabel = "Undo")
        }
    }

    /** Sets [stream] to [level] (of its own steps), offering to put back the level it had. */
    private fun volume(context: Context, name: String, stream: Int, level: Int, undoLabel: String): Outcome {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(stream)
        val max = audio.getStreamMaxVolume(stream)
        return try {
            audio.setStreamVolume(stream, level, AudioManager.FLAG_SHOW_UI)
            val percent = (level * 100.0 / max).roundToInt()
            Outcome.Done("$name volume $percent %", listOf(FollowUp(undoLabel) {
                volume(it.context, name, stream, before, undoLabel = if (undoLabel == "Undo") "Redo" else "Undo")
            }))
        } catch (e: SecurityException) {
            // Changing the ringer while Do Not Disturb is on needs Do Not Disturb access.
            Outcome.Failed("Not allowed while Do Not Disturb is on")
        }
    }
}

object MediaControl : Tool(
    name = "control_media",
    title = "Media controls",
    icon = R.drawable.ic_music,
    description = "Play, pause or skip whatever music or video is playing.",
    onByDefault = false,
    defaultRunFrom = 0.3,
    examples = listOf("Pause the music", "Next song"),
) {
    val ACTIONS = linkedMapOf(
        "play" to KeyEvent.KEYCODE_MEDIA_PLAY,
        "pause" to KeyEvent.KEYCODE_MEDIA_PAUSE,
        "next" to KeyEvent.KEYCODE_MEDIA_NEXT,
        "previous" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
    )

    override val schema = """
        {"name": "control_media",
         "description": "Play, pause, or skip to the next or previous song or track of the music playing",
         "parameters": {"type": "object", "properties": {
           "action": {"type": "string", "enum": [${ACTIONS.keys.joinToString { "\"$it\"" }}], "description": "play, pause, next track or previous track"}},
           "required": ["action"]}}
    """.trimIndent()

    override fun prepare(arguments: Map<String, Any?>, apps: InstalledApps): Prepared {
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
    defaultRunFrom = 0.3,
    examples = listOf("Take a picture", "Open the camera"),
) {
    // No photo/video choice: on the desktop check, Needle 3 picked the mode backwards about half the
    // time (whether as an enum or a boolean); switching to video in the camera app is one tap.
    override val schema = """
        {"name": "open_camera",
         "description": "Open the camera app to take a photo or a video",
         "parameters": {"type": "object", "properties": {}, "required": []}}
    """.trimIndent()

    override fun prepare(arguments: Map<String, Any?>, apps: InstalledApps) = Prepared.Ready("Open the camera") { context ->
        Tools.start(context, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Opened the camera")
            .again(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
    }
}
