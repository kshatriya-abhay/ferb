package dev.abhay.assistant.voice

import com.google.mlkit.genai.common.FeatureStatus
import java.util.Locale

/**
 * Gemini Nano's speech recognition, in one locale, en-US, whatever the user speaks (the user's
 * decisions, 2026-10-25 and 2026-10-30; there's no language setting). Advanced mode is one model
 * for every language: the locale is only a hint, and checkStatus says the same for every locale. Set to en-US it still
 * writes Hindi down, in Devanagari, and Hinglish too, and it detects other languages itself (the
 * phone's TTS in Japanese and French came back right). The locale probe (2026-10-30,
 * tool-check/locale-probe-2026-10-30.md) found:
 *  - en-IN gives exactly what en-US gives;
 *  - hi-IN hears Hindi about as well, but writes English in Devanagari and gets it wrong more
 *    ("Show me my alarms" as "Xiaomi my alarms");
 *  - a wrong locale is worse than none: ja-JP and fr-FR translate Hindi into Japanese or French.
 * So no language setting: Ferb's rewrite and Needle 3 handle English and Hindi only anyway.
 */
object NanoSpeech {
    val LOCALE: Locale = Locale.forLanguageTag("en-US")
}

/** Where one part of Gemini Nano's setup stands on this phone: speech recognition, or the rewriting model. */
sealed interface Part {
    data object Checking : Part
    data object Ready : Part
    data object NeedsDownload : Part
    /**
     * No amount: AICore's model is shared across apps, so the download's byte counts may not match
     * what this phone fetches (2026-10-30). NanoEars logs them; [ReadyWatch] asks when it's done.
     */
    data object Downloading : Part
    data object Unavailable : Part
    data class Failed(val why: String) : Part
    /** The status check itself threw: not known, so not "can't run" (Try again checks again). */
    data class Unchecked(val why: String) : Part

    /** For Settings. */
    fun label(): String = when (this) {
        Checking -> "Checking…"
        Ready -> "Ready"
        NeedsDownload -> "Needs a download"
        Downloading -> "Downloading…"
        Unavailable -> "Not available on this phone"
        is Failed -> "Couldn't download: $why"
        is Unchecked -> "Couldn't check: $why"
    }

    /** Needs a download the user can start: offered, or one that failed (review P2-107, one rule for setUp, the setup state and the row). */
    val needsDownload: Boolean get() = this == NeedsDownload || this is Failed

    companion object {
        /**
         * A part as just checked ([now]) against how it stood ([was]): what AICore says wins, except
         * that a failed download stays failed while AICore only offers it again, and one Ferb is still
         * [downloading] stays downloading (review P2-106: the rule, pure and tested).
         */
        fun merged(was: Part, now: Part, downloading: Boolean): Part = when {
            now != NeedsDownload -> now
            was is Failed -> was
            downloading -> Downloading
            else -> now
        }

        /** What checkStatus() says. DOWNLOADING without numbers: Android is fetching it; Set up follows it. */
        fun of(status: Int): Part = when (status) {
            FeatureStatus.AVAILABLE -> Ready
            FeatureStatus.DOWNLOADABLE -> NeedsDownload
            FeatureStatus.DOWNLOADING -> Downloading
            else -> Unavailable
        }

    }
}

/** What the pipeline needs from Gemini Nano's setup: [speech] and [rewrite] ready now, and whether it can run [here] at all (review P2-109). */
data class GeminiAbilities(val speech: Boolean, val rewrite: Boolean, val here: Boolean)

/** Gemini Nano's whole setup: what it needs to hear a request and to write it in English. */
data class GeminiSetup(val speech: Part = Part.Checking, val rewrite: Part = Part.Checking) {
    val ready get() = speech == Part.Ready && rewrite == Part.Ready
    val parts get() = listOf(speech, rewrite)
    /** Something to fetch: Set up downloads it. */
    val needsSetUp get() = parts.any { it.needsDownload }
    val downloading get() = parts.any { it == Part.Downloading }

    companion object {
        /** For the steps: speech ready, rewrite ready, and whether Gemini Nano can run here at all. */
        fun abilities(s: GeminiSetup) = GeminiAbilities(speech = s.speech == Part.Ready, rewrite = s.rewrite == Part.Ready, here = s.speech != Part.Unavailable)
    }

    /**
     * Its state in a few words, for Advanced's Gemini Nano options and the Gemini page's subtitle:
     * the status only, as neither is a button; the buttons say what they do (review P2-134).
     */
    fun summary(): String = when {
        ready -> "Ready"
        parts.any { it is Part.Unchecked } -> "Couldn't check"
        parts.any { it == Part.Unavailable } -> "Not available on this phone"
        downloading -> "Downloading…"
        parts.any { it == Part.Checking } -> "Checking…"
        else -> "Set up needed"
    }
}

/** What Gemini Nano's checkStatus() means for a spoken request. */
sealed interface NanoChoice {
    /** Gemini Nano hears it. */
    data object Nano : NanoChoice
    /** Whistle hears it instead, with [notice] saying why. */
    data class Whistle(val notice: String) : NanoChoice

    companion object {
        /**
         * The mic's choice from speech's [Part], as setup has it: one reading of AICore for the whole
         * app (review P2-105). A check that failed is "couldn't check", never "can't run" (P2-102).
         */
        fun of(part: Part): NanoChoice = when (part) {
            Part.Ready -> Nano
            Part.NeedsDownload, is Part.Failed -> Whistle(NOT_SET_UP)
            Part.Downloading -> Whistle(DOWNLOADING)
            Part.Unavailable -> Whistle(UNAVAILABLE)
            Part.Checking, is Part.Unchecked -> Whistle(UNCHECKED)
        }

        const val NOT_SET_UP = "Gemini Nano isn't set up: Whistle heard this"
        const val DOWNLOADING = "Gemini Nano is downloading: Whistle heard this"
        const val UNAVAILABLE = "Gemini Nano can't run here: Whistle heard this"
        const val UNCHECKED = "Gemini Nano couldn't be checked just now: Whistle heard this"
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

    /** Whether a rewrite's [line] is NONE (nothing for the phone to do), a full stop or not: the one rule for every rewrite. */
    fun isNone(line: String): Boolean = line.trim().trimEnd('.').equals(NONE, ignoreCase = true)

    /**
     * v2: the first prompt's misses on the bench (2026-10-24) as general rules, its examples new (in no bench or
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
     * R2 (2026-10-29, written before any run of it): R plus the event rule blind-1 and blind-2
     * showed (meetings, visits and plans became reminders, alarms or lists): something the user
     * will attend at a time is a calendar event; "remind me" or yaad dilana is always a reminder;
     * an alarm only when asked for one or to be woken. New examples, in no bench file.
     */
    val PROMPT_R2 = PROMPT_R.replace(
        "Keep what the request asks for: a reminder stays a reminder",
        "Something the user will go to or take part in at a time, such as a meeting, a call, an appointment, an interview, a class or lesson, a visit to a doctor, a workout, a party, or lunch or dinner with someone, is an event for the calendar: \"Add <it> to my calendar <day> at <time>\". But a request that says remind me or yaad dilana is a reminder, whatever it's about, and an alarm is only for a request that asks for an alarm or to be woken.\nKeep what the request asks for: a reminder stays a reminder",
    ).replace(
        "torch jala do -> Turn on the flashlight",
        "badminton saturday 6 baje -> Add badminton to my calendar on Saturday at 6\nband practice tomorrow from 5 to 7 pm -> Add band practice to my calendar tomorrow from 5 to 7 pm\nroz raat 9 baje inhaler lene ka yaad dilana -> Remind me every day at 9 pm to take the inhaler\ntorch jala do -> Turn on the flashlight",
    )
    const val VERSION_R2 = "r2"

    /**
     * R2 (2026-10-30), the prompt 0.1 used for Gemini Nano and Ferb Link. On blind-2 it gives 90
     * right and 2 wrong (R: 75 and 13); on datetime-v2, behind the date gate, 122 right and 0
     * wrong (R: 123 and 0). Since 0.2 the app uses [ContentRewrite] (H2, which keeps the words to
     * write down or send as said); R2 stays as the bench's baseline, and its rules are H2's and A's
     * (PROMPT_R and PROMPT_R2 are frozen: FrozenPromptsTest says why).
     */
    val PROMPT = PROMPT_R2
    const val VERSION = VERSION_R2

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
        val none = isNone(line)
        require(!cut || none || answer.trimStart().contains('\n')) { CUT_OFF }
        return Rewritten(said, if (none) null else line)
    }
}

/**
 * What Gemini Nano's setup offers now, in one order for the Gemini page and the line under the
 * model choice (review P2-135: two tables in two orders disagreed, e.g. speech downloading with
 * the rewrite unchecked).
 */
sealed interface GeminiOffer {
    data object Ready : GeminiOffer
    /** A check failed ([why]): check again. */
    data class Unchecked(val why: String) : GeminiOffer
    data object CantRun : GeminiOffer
    /** Ferb waited for a download and gave up ([GeminiWatch.GaveUp]): check again. */
    data object StillDownloading : GeminiOffer
    data object Downloading : GeminiOffer
    /** A download failed ([why]): set up again. */
    data class Failed(val why: String) : GeminiOffer
    data object SetUp : GeminiOffer
    data object Checking : GeminiOffer

    companion object {
        fun of(setup: GeminiSetup, watch: GeminiWatch): GeminiOffer {
            val unchecked = setup.parts.filterIsInstance<Part.Unchecked>().firstOrNull()
            val failed = setup.parts.filterIsInstance<Part.Failed>().firstOrNull()
            return when {
                setup.ready -> Ready
                unchecked != null -> Unchecked(unchecked.why)
                setup.speech == Part.Unavailable -> CantRun
                setup.downloading && watch == GeminiWatch.GaveUp -> StillDownloading
                setup.downloading -> Downloading
                failed != null -> Failed(failed.why)
                setup.needsSetUp -> SetUp
                else -> Checking
            }
        }
    }
}
