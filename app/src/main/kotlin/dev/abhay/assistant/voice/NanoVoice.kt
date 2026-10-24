package dev.abhay.assistant.voice

import com.google.mlkit.genai.common.FeatureStatus
import org.json.JSONObject
import java.util.Locale

/**
 * The language Gemini Nano expects. One model hears both: set to English it still wrote Hindi
 * down, in Devanagari (2026-10-24), and both locales report the same AICore feature
 * (nano-v4-full, "Speech Recognition Continuous Streaming"). Japanese wasn't heard.
 */
enum class GeminiLanguage(val id: String, val title: String, val locale: Locale) {
    English("en-US", "English", Locale.forLanguageTag("en-US")),
    Hindi("hi-IN", "Hindi or Hinglish", Locale.forLanguageTag("hi-IN"));

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: English
    }
}

/** Where one part of Gemini Nano's setup stands on this phone: speech recognition, or the rewriting model. */
sealed interface Part {
    data object Checking : Part
    data object Ready : Part
    data object NeedsDownload : Part
    /** [done] bytes of [total] (null until AICore says how much). */
    data class Downloading(val done: Long = 0, val total: Long? = null) : Part {
        val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (done.toFloat() / it).coerceIn(0f, 1f) }
    }
    data object Unavailable : Part
    data class Failed(val why: String) : Part

    /** For Settings. */
    fun label(): String = when (this) {
        Checking -> "Checking…"
        Ready -> "Ready"
        NeedsDownload -> "Needs a download"
        is Downloading -> when {
            total != null && total > 0 -> "Downloading: ${mb(done)} of ${mb(total)} MB"
            done > 0 -> "Downloading: ${mb(done)} MB so far"
            else -> "Downloading…"
        }
        Unavailable -> "Not available on this phone"
        is Failed -> "Couldn't download: $why"
    }

    companion object {
        /** What checkStatus() says. DOWNLOADING without numbers: Android is fetching it; Set up follows it. */
        fun of(status: Int): Part = when (status) {
            FeatureStatus.AVAILABLE -> Ready
            FeatureStatus.DOWNLOADABLE -> NeedsDownload
            FeatureStatus.DOWNLOADING -> Downloading()
            else -> Unavailable
        }

        private fun mb(bytes: Long) = String.format(Locale.US, if (bytes < 10_000_000) "%.1f" else "%.0f", bytes / 1_000_000.0)
    }
}

/** Gemini Nano's whole setup: what it needs to hear [language] and to write it in English. */
data class GeminiSetup(val speech: Part = Part.Checking, val rewrite: Part = Part.Checking) {
    val ready get() = speech == Part.Ready && rewrite == Part.Ready
    val parts get() = listOf(speech, rewrite)
    /** Something to fetch: Set up downloads it. */
    val needsSetUp get() = parts.any { it == Part.NeedsDownload || it is Part.Failed || (it is Part.Downloading && it.total == null && it.done == 0L) }
    val downloading get() = parts.any { it is Part.Downloading }

    /** The Settings row's line under "Gemini Nano". */
    fun summary(): String = when {
        ready -> "Ready"
        parts.any { it == Part.Unavailable } -> "Not available on this phone"
        downloading -> parts.filterIsInstance<Part.Downloading>().let { d ->
            val total = d.mapNotNull { it.total }.sum()
            if (total > 0 && d.all { it.total != null }) "Downloading: ${(d.sumOf { it.done } * 100 / total)}%" else "Downloading…"
        }
        parts.any { it == Part.Checking } -> "Checking…"
        else -> "Set up needed: tap to download"
    }
}

/** What Gemini Nano's checkStatus() means for a spoken request. */
sealed interface NanoChoice {
    /** Gemini Nano hears it. */
    data object Nano : NanoChoice
    /** Whistle hears it instead, with [notice] saying why. */
    data class Whistle(val notice: String) : NanoChoice

    companion object {
        fun of(status: Int): NanoChoice = when (status) {
            FeatureStatus.AVAILABLE -> Nano
            FeatureStatus.DOWNLOADABLE -> Whistle(NOT_SET_UP)
            FeatureStatus.DOWNLOADING -> Whistle(DOWNLOADING)
            else -> Whistle(UNAVAILABLE)
        }

        const val NOT_SET_UP = "Gemini Nano isn't set up yet (Settings → Gemini Nano): Whistle hears this one"
        const val DOWNLOADING = "Gemini Nano is still downloading: Whistle hears this one"
        const val UNAVAILABLE = "Gemini Nano can't hear on this phone: Whistle hears it"

        /** For the log. */
        fun name(status: Int): String = when (status) {
            FeatureStatus.AVAILABLE -> "available"
            FeatureStatus.DOWNLOADABLE -> "downloadable"
            FeatureStatus.DOWNLOADING -> "downloading"
            FeatureStatus.UNAVAILABLE -> "unavailable"
            else -> "unknown ($status)"
        }
    }
}

/**
 * What Gemini Nano hears while listening: each final piece as it comes, then the piece still being
 * heard (a partial result replaces the one before it).
 */
class LiveTranscript {
    private val finals = mutableListOf<String>()
    private var partial = ""

    fun partial(text: String): String {
        partial = text.trim()
        return heard
    }

    fun final(text: String): String {
        text.trim().takeIf { it.isNotEmpty() }?.let(finals::add)
        partial = ""
        return heard
    }

    val heard get() = (finals + partial).filter { it.isNotEmpty() }.joinToString(" ")
}

/**
 * A request as Gemini Nano wrote it for Needle 3: [command], one short plain English command, or
 * null when it asks the phone to do nothing ("NONE": a question, a how-to, chat, a statement, or
 * something the phone can't do).
 */
data class Rewritten(val said: String, val command: String?)

/**
 * Gemini Nano before Needle 3 (the prompt rewrite step, on the phone; Gemma 4 on the computer uses it too): every request, typed or
 * heard, English, Hinglish or Hindi, becomes one plain English command, or NONE. Needle 3's
 * weakest spot was knowing when to call nothing (75 of 137 right on the phone bench, 2026-10-24),
 * so NONE is Gemini Nano's to say. The phone bench measures this same prompt (tool-check/phone.py);
 * its examples are in no bench file. Lessons from Ferb Link's transcribe and rewrite prompts:
 * keep every name and number as said, add nothing, Hindi amounts as numbers.
 */
object GeminiRewrite {
    const val NONE = "NONE"

    const val PROMPT_V1 = """You turn a request to a phone assistant into one short, plain English command for it. The request may be English, Hindi or Hinglish, in Latin letters or Devanagari, and may have typos.
Reply with exactly one line: the command, or NONE.
The phone can: set alarms and timers; open apps, settings and the camera; turn the flashlight on or off; set the media volume; play, pause or skip music; make notes, lists and reminders; call and text contacts; give directions; show the calendar and add events.
Reply NONE when the user isn't asking the phone to do one of these now: questions about the world, how-to questions, chat, greetings, opinions, statements about things or the past, and things the phone can't do.
Keep every name, contact, app, place, number, time, date and length exactly as said; translate Hindi words into English. Never add anything the request doesn't say. Keep the user's own words for notes and messages.
Hindi amounts as numbers: sava 7 is 7:15, saade 5 is 5:30, paune 8 is 7:45, dedh ghanta is 1 hour 30 minutes, dhai minute is 2 minutes 30 seconds, aadhi aawaz is 50 percent volume.
Examples:
kal subah 6 baje ka alarm laga do -> Set an alarm for 6 am tomorrow
torch jala do -> Turn on the flashlight
bhaiya ko message karo ki main late hoon -> Send a text to bhaiya: main late hoon
remind me to drink water at 4 -> Remind me to drink water at 4
what is the capital of japan -> NONE
my phone feels slow today -> NONE"""

    /**
     * v2: v1's hurts on the bench (2026-10-24) as general rules, its examples new (in no bench or
     * held-out file): the settings page kept, questions about the user's own calendar, alarms and
     * lists shown, a word or two of music control is a command, list verbs stay list actions, "tell"
     * is a text, and a statement or complaint never becomes an action with a made-up time.
     */
    const val PROMPT_V2 = """You turn a request to a phone assistant into one short, plain English command for it. The request may be English, Hindi or Hinglish, in Latin letters or Devanagari, and may have typos.
Reply with exactly one line: the command, or NONE.
The phone can: set alarms and timers; open apps, settings and the camera; turn the flashlight on or off; set the media volume; play, pause or skip music; make notes, lists and reminders; call and text contacts; give directions; show the calendar, alarms and lists, and add events.
Reply NONE only when the user isn't asking the phone to do one of these now: questions about the world, how-to questions, chat, greetings, opinions, statements about things or the past, and things the phone can't do. Never turn a statement or a complaint into an action.
A question about the user's own calendar, alarms or lists is a command to show it: "Show my calendar for <day>", "Show my alarms", "Show my <name> list".
Name the settings page asked for: "Open <page> settings".
A word or two of music control is a command: "Skip to the next track", "Go back to the previous track", "Pause the music", "Resume the music".
Lists: "Add <item> to my <name> list". Ticking, crossing off, finishing, getting or buying an item is "Check off <item> on my <name> list", without the list if none is named. Lists are not notes.
"Tell <someone> <something>" is a text: "Send a text to <someone>: <something>".
Keep every name, contact, app, place, number, time, date and length exactly as said; translate Hindi words into English. Never add anything the request doesn't say, least of all a time. Keep the user's own words for notes and messages.
Amounts as numbers: half is 50 percent and half an hour 30 minutes; sava 7 is 7:15, saade 5 is 5:30, paune 8 is 7:45, dedh ghanta is 1 hour 30 minutes, dhai minute is 2 minutes 30 seconds.
Examples:
kal subah 6 baje ka alarm laga do -> Set an alarm for 6 am tomorrow
torch jala do -> Turn on the flashlight
open the nfc settings -> Open NFC settings
any plans on wednesday? -> Show my calendar for Wednesday
got the batteries -> Check off batteries
tell meera the door is open -> Send a text to meera: the door is open
what is the capital of japan -> NONE
the alarm woke the baby -> NONE"""

    /**
     * R (2026-10-24, frozen before the blind date set was read): v2 with the date and time rules
     * the user approved. Times and dates become canonical English phrases relative to now, never
     * a computed date (Ferb's own resolver works them out from the words as said and as
     * rewritten); the reminder, alarm or event verb and waking words are kept. Its examples are
     * new, in no bench file (tested). Not shipped: benched against v2 first.
     */
    const val PROMPT_R = """You turn a request to a phone assistant into one short, plain English command for it. The request may be English, Hindi or Hinglish, in Latin letters or Devanagari, and may have typos.
Reply with exactly one line: the command, or NONE.
The phone can: set alarms and timers; open apps, settings and the camera; turn the flashlight on or off; set the media volume; play, pause or skip music; make notes, lists and reminders; call and text contacts; give directions; show the calendar, alarms and lists, and add events.
Reply NONE only when the user isn't asking the phone to do one of these now: questions about the world, how-to questions, chat, greetings, opinions, statements about things or the past, and things the phone can't do. Never turn a statement or a complaint into an action.
A question about the user's own calendar, alarms or lists is a command to show it: "Show my calendar for <day>", "Show my alarms", "Show my <name> list".
Name the settings page asked for: "Open <page> settings".
A word or two of music control is a command: "Skip to the next track", "Go back to the previous track", "Pause the music", "Resume the music".
Lists: "Add <item> to my <name> list". Ticking, crossing off, finishing, getting or buying an item is "Check off <item> on my <name> list", without the list if none is named. Lists are not notes.
"Tell <someone> <something>" is a text: "Send a text to <someone>: <something>".
Keep what the request asks for: a reminder stays a reminder ("remind me", "yaad dilana": "Remind me ..."), an alarm an alarm, a timer a timer, an event an event. Keep waking words: "wake me up", "get up", utha dena and jaga dena are "Wake me up".
Keep every name, relation, contact, app, place and number as said; translate Hindi words into English. Never add anything the request doesn't say, least of all a time.
Times and dates are English words relative to now, never a date you work out: kal is tomorrow, parso is in 2 days, narso is in 3 days, aaj is today, agle with a weekday is next and the weekday (somvaar Monday, mangalvaar Tuesday, budhvaar Wednesday, guruvaar Thursday, shukravaar Friday, shanivaar Saturday, ravivaar Sunday). Keep a weekday or a date as said.
A clock time takes am or pm only from what the request says: subah or savere is am; dopahar and shaam are pm; raat is pm from 8 to 11 and am from 12 to 4. Otherwise keep the hour as said, with no am or pm. sava 7 is 7:15, saade 5 is 5:30, paune 8 is 7:45, dedh baje is 1:30, dhai baje is 2:30.
Lengths are digits with English units: "in 1 hour 30 minutes", "for 2 minutes 30 seconds"; dedh ghanta is 1 hour 30 minutes, dhai minute 2 minutes 30 seconds, aadha ghanta 30 minutes.
Examples:
narso shaam 7 baje yaad dilana ki gas book karni hai -> Remind me in 3 days at 7 pm to book the gas
budhvaar ko savere 6 baje utha dena -> Wake me up on Wednesday at 6 am
raat 1 baje ka alarm -> Set an alarm for 1 am
paune ghante ka timer laga do -> Set a timer for 45 minutes
torch jala do -> Turn on the flashlight
open the nfc settings -> Open NFC settings
any plans on wednesday? -> Show my calendar for Wednesday
tell meera the door is open -> Send a text to meera: the door is open
what is the capital of japan -> NONE
the alarm woke the baby -> NONE"""
    const val VERSION_R = "r"

    /**
     * The prompt the app uses, for Gemini Nano and for Ferb Link (sent as its instructions): R
     * (2026-10-25, the user's decision). On the blind date set it picked the right tool for 138 of
     * 154 requests, against v2's 107 and 87 without a rewrite. v2 stays for the bench.
     */
    const val PROMPT = PROMPT_R
    const val VERSION = VERSION_R

    const val CUT_OFF = "answer cut off"

    /** Room for one short command; R says times in words ("in 3 days at 7 pm"). NONE takes a token or two. */
    const val MAX_TOKENS = 48

    /**
     * The model's answer: its first line, without quotes, an arrow or "Command:"; null for NONE.
     * [cut]: the answer stopped at the token limit. A first line it cut off fails, so a half-written
     * command ("Send a text to bhaiya: I") never runs (as Ferb Link's, 2026-10-24); NONE, or a cut
     * in a later line, is whole.
     */
    fun parse(said: String, answer: String, cut: Boolean = false): Rewritten {
        val line = answer.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
            .substringAfter("->").removePrefix("Command:").trim().trim('"', '\u201c', '\u201d', '`').trim()
        require(line.isNotEmpty()) { "Gemini Nano wrote nothing" }
        val none = line.trimEnd('.').equals(NONE, ignoreCase = true)
        require(!cut || none || answer.trimStart().contains('\n')) { CUT_OFF }
        return Rewritten(said, if (none) null else line)
    }
}
