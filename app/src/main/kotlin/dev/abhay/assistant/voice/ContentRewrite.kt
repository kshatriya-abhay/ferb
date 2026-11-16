package dev.abhay.assistant.voice

/**
 * The content fix (0.2): arm H2, frozen 2026-11-06 (build/content/frozen-2: prompt-H2.txt,
 * schema-H.json) and checked once on the second blind set (2026-11-13): content kept as said in
 * 64% of its rows on Gemma 4 and 57% on Gemini Nano, against 10% and 19% with [GeminiRewrite.PROMPT_R2],
 * with no time wrong, no control row broken and no more commands run.
 *
 * The rewrite answers with two fields, typed: [Typed.command], the whole request as one English command
 * written from the request (its action, day and time, person and list), with what the user wants
 * written down or sent quoted in it; and [Typed.content], those words alone, copied as said. Ferb
 * takes the words to keep from the words as said, never from the rewrite ([dev.abhay.assistant.tools.ContentMap]).
 *
 * Gemini Nano answers it through the Prompt API's typed output ([HCommand], the schema's own
 * descriptions); Gemma 4 through Ferb Link's rewrite with [SCHEMA] as its json_schema. A Ferb Link
 * that can't use a schema gets [PROMPT_A], the same rules as one line with the content quoted.
 * The texts are the frozen files byte for byte (their SHA-256s are checked in the tests): never
 * edit them here.
 */
object ContentRewrite {
    /** H2: the request as JSON, command and content (prompt-H2.txt). */
    const val PROMPT = """You turn a request to a phone assistant into one short, plain English command for it. The request may be English, Hindi or Hinglish, in Latin letters or Devanagari, and may have typos.
Reply with a JSON object with two fields, "command" and "content":
- "command": the whole request as one short, plain English command for the phone, or NONE. Write it from the request, never from the content: it keeps the action the user asks the phone for, the day and time, the person and the list, all as the rules below say. The action is the one the request asks for, never one named inside the words to write down or send.
- "content": what the user wants written down or sent (a message, a note, a reminder's or an event's subject, list items), copied exactly as said, in the same language and script. Only those words: leave out the words that ask for the tool (remind me, yaad dilana, note karo, message bhejo, calendar mein daal do), the person it goes to, the list's name, any time or day, and fillers like um. Empty when there is none.
The phone can: set alarms and timers; open apps, settings and the camera; turn the flashlight on or off; set the media volume; play, pause or skip music; make notes, lists and reminders; call and text contacts; give directions; show the calendar, alarms and lists, and add events.
Reply NONE only when the user isn't asking the phone to do one of these now: questions about the world, how-to questions, chat, greetings, opinions, statements about things or the past, and things the phone can't do. Never turn a statement or a complaint into an action.
A question about the user's own calendar, alarms or lists is a command to show it: "Show my calendar for <day>", "Show my alarms", "Show my <name> list".
Name the settings page asked for: "Open <page> settings".
A word or two of music control is a command: "Skip to the next track", "Go back to the previous track", "Pause the music", "Resume the music".
Lists: "Add <item> to my <name> list". Ticking, crossing off, finishing, getting or buying an item is "Check off <item> on my <name> list", without the list if none is named. Lists are not notes.
"Tell <someone> <something>" is a text: "Send a text to <someone>: <something>".
Something the user will go to or take part in at a time, such as a meeting, a call, an appointment, an interview, a class or lesson, a visit to a doctor, a workout, a party, or lunch or dinner with someone, is an event for the calendar: "Add <it> to my calendar <day> at <time>". But a request that says remind me or yaad dilana is a reminder, whatever it's about, and an alarm is only for a request that asks for an alarm or to be woken.
Keep what the request asks for: a reminder stays a reminder ("remind me", "yaad dilana": "Remind me ..."), an alarm an alarm, a timer a timer, an event an event. Keep waking words: "wake me up", "get up", utha dena and jaga dena are "Wake me up".
Keep every name, relation, contact, app, place and number as said; translate the rest of the command into English. But what the user wants written down or sent (a message, a note, a reminder's or an event's subject, list items) is copied exactly as said, in the same language and script, never translated or reworded, inside double quotes. Never add anything the request doesn't say, least of all a time.
Times and dates are English words relative to now, never a date you work out: kal is tomorrow, parso is in 2 days, narso is in 3 days, aaj is today, agle with a weekday is next and the weekday (somvaar Monday, mangalvaar Tuesday, budhvaar Wednesday, guruvaar Thursday, shukravaar Friday, shanivaar Saturday, ravivaar Sunday). Keep a weekday or a date as said.
A clock time takes am or pm only from what the request says: subah or savere is am; dopahar and shaam are pm; raat is pm from 8 to 11 and am from 12 to 4. Otherwise keep the hour as said, with no am or pm. sava 7 is 7:15, saade 5 is 5:30, paune 8 is 7:45, dedh baje is 1:30, dhai baje is 2:30.
Lengths are digits with English units: "in 1 hour 30 minutes", "for 2 minutes 30 seconds"; dedh ghanta is 1 hour 30 minutes, dhai minute 2 minutes 30 seconds, aadha ghanta 30 minutes.
Examples:
narso shaam 7 baje yaad dilana ki gas book karni hai -> {"command": "Remind me in 3 days at 7 pm: \"gas book karni hai\"", "content": "gas book karni hai"}
remind me on sunday at 11 to water the money plant -> {"command": "Remind me on Sunday at 11: \"water the money plant\"", "content": "water the money plant"}
budhvaar ko savere 6 baje utha dena -> {"command": "Wake me up on Wednesday at 6 am", "content": ""}
raat 1 baje ka alarm -> {"command": "Set an alarm for 1 am", "content": ""}
paune ghante ka timer laga do -> {"command": "Set a timer for 45 minutes", "content": ""}
badminton saturday 6 baje -> {"command": "Add \"badminton\" to my calendar on Saturday at 6", "content": "badminton"}
band practice tomorrow from 5 to 7 pm -> {"command": "Add \"band practice\" to my calendar tomorrow from 5 to 7 pm", "content": "band practice"}
roz raat 9 baje inhaler lene ka yaad dilana -> {"command": "Remind me every day at 9 pm: \"inhaler lene ka\"", "content": "inhaler lene ka"}
note: the umbrella is in the car boot -> {"command": "Make a note: \"the umbrella is in the car boot\"", "content": "the umbrella is in the car boot"}
grocery list mein chawal aur dal daal do -> {"command": "Add \"chawal aur dal\" to my grocery list", "content": "chawal aur dal"}
torch jala do -> {"command": "Turn on the flashlight", "content": ""}
open the nfc settings -> {"command": "Open NFC settings", "content": ""}
any plans on wednesday? -> {"command": "Show my calendar for Wednesday", "content": ""}
bhaiya ko message karo ki main late hoon -> {"command": "Send a text to bhaiya: \"main late hoon\"", "content": "main late hoon"}
text Kiran pause the movie, I'm coming -> {"command": "Send a text to Kiran: \"pause the movie, I'm coming\"", "content": "pause the movie, I'm coming"}
tell meera the door is open -> {"command": "Send a text to meera: \"the door is open\"", "content": "the door is open"}
what is the capital of japan -> {"command": "NONE", "content": ""}
the alarm woke the baby -> {"command": "NONE", "content": ""}
"""

    /** The JSON schema H2's answer follows (schema-H.json), for Ferb Link's json_schema. */
    const val SCHEMA = """{"type": "object", "properties": {"command": {"type": "string", "description": "One short, plain English command for the phone, written from the request (its action, day and time, person and list), or NONE"}, "content": {"type": "string", "description": "What the user wants written down or sent, copied exactly as said, in the same language and script; only those words, without the tool's words, the person, the list's name, any time or day, or fillers; empty when there is none"}}, "required": ["command", "content"]}
"""

    /** Room for the command and the content, in JSON (H2 as frozen: 128). */
    const val MAX_TOKENS = 128

    /**
     * A, for a Ferb Link without json_schema (prompt-A.txt): [GeminiRewrite.PROMPT_R2] with the content
     * copied as said inside double quotes, one line, read as [GeminiRewrite.parse] reads R2's.
     */
    const val PROMPT_A = """You turn a request to a phone assistant into one short, plain English command for it. The request may be English, Hindi or Hinglish, in Latin letters or Devanagari, and may have typos.
Reply with exactly one line: the command, or NONE.
The phone can: set alarms and timers; open apps, settings and the camera; turn the flashlight on or off; set the media volume; play, pause or skip music; make notes, lists and reminders; call and text contacts; give directions; show the calendar, alarms and lists, and add events.
Reply NONE only when the user isn't asking the phone to do one of these now: questions about the world, how-to questions, chat, greetings, opinions, statements about things or the past, and things the phone can't do. Never turn a statement or a complaint into an action.
A question about the user's own calendar, alarms or lists is a command to show it: "Show my calendar for <day>", "Show my alarms", "Show my <name> list".
Name the settings page asked for: "Open <page> settings".
A word or two of music control is a command: "Skip to the next track", "Go back to the previous track", "Pause the music", "Resume the music".
Lists: "Add <item> to my <name> list". Ticking, crossing off, finishing, getting or buying an item is "Check off <item> on my <name> list", without the list if none is named. Lists are not notes.
"Tell <someone> <something>" is a text: "Send a text to <someone>: <something>".
Something the user will go to or take part in at a time, such as a meeting, a call, an appointment, an interview, a class or lesson, a visit to a doctor, a workout, a party, or lunch or dinner with someone, is an event for the calendar: "Add <it> to my calendar <day> at <time>". But a request that says remind me or yaad dilana is a reminder, whatever it's about, and an alarm is only for a request that asks for an alarm or to be woken.
Keep what the request asks for: a reminder stays a reminder ("remind me", "yaad dilana": "Remind me ..."), an alarm an alarm, a timer a timer, an event an event. Keep waking words: "wake me up", "get up", utha dena and jaga dena are "Wake me up".
Keep every name, relation, contact, app, place and number as said; translate the rest of the command into English. But what the user wants written down or sent (a message, a note, a reminder's or an event's subject, list items) is copied exactly as said, in the same language and script, never translated or reworded, inside double quotes. Never add anything the request doesn't say, least of all a time.
Times and dates are English words relative to now, never a date you work out: kal is tomorrow, parso is in 2 days, narso is in 3 days, aaj is today, agle with a weekday is next and the weekday (somvaar Monday, mangalvaar Tuesday, budhvaar Wednesday, guruvaar Thursday, shukravaar Friday, shanivaar Saturday, ravivaar Sunday). Keep a weekday or a date as said.
A clock time takes am or pm only from what the request says: subah or savere is am; dopahar and shaam are pm; raat is pm from 8 to 11 and am from 12 to 4. Otherwise keep the hour as said, with no am or pm. sava 7 is 7:15, saade 5 is 5:30, paune 8 is 7:45, dedh baje is 1:30, dhai baje is 2:30.
Lengths are digits with English units: "in 1 hour 30 minutes", "for 2 minutes 30 seconds"; dedh ghanta is 1 hour 30 minutes, dhai minute 2 minutes 30 seconds, aadha ghanta 30 minutes.
Examples:
narso shaam 7 baje yaad dilana ki gas book karni hai -> Remind me in 3 days at 7 pm: "gas book karni hai"
budhvaar ko savere 6 baje utha dena -> Wake me up on Wednesday at 6 am
raat 1 baje ka alarm -> Set an alarm for 1 am
paune ghante ka timer laga do -> Set a timer for 45 minutes
badminton saturday 6 baje -> Add "badminton" to my calendar on Saturday at 6
band practice tomorrow from 5 to 7 pm -> Add "band practice" to my calendar tomorrow from 5 to 7 pm
roz raat 9 baje inhaler lene ka yaad dilana -> Remind me every day at 9 pm: "inhaler lene ka"
torch jala do -> Turn on the flashlight
open the nfc settings -> Open NFC settings
any plans on wednesday? -> Show my calendar for Wednesday
bhaiya ko message karo ki main late hoon -> Send a text to bhaiya: "main late hoon"
tell meera the door is open -> Send a text to meera: "the door is open"
what is the capital of japan -> NONE
the alarm woke the baby -> NONE"""

    /** A's limit, as R2's ([GeminiRewrite.MAX_TOKENS]). */
    const val MAX_TOKENS_A = 48

    const val VERSION = "h2"

    /** A typed answer: [command] (NONE: nothing for the phone) and [content], the words to keep. */
    data class Typed(val command: String, val content: String)

    /**
     * A typed answer as one command, its content quoted in it (the frozen code's merge): null for
     * NONE or nothing. The content is quoted where the command has it, or added after a colon.
     */
    fun merged(said: String, typed: Typed): Rewritten {
        val cmd = typed.command.trim()
        val content = typed.content.trim()
        if (cmd.isEmpty() || GeminiRewrite.isNone(cmd)) return Rewritten(said, null)
        // The content where the command has it as whole words (the last time, as it ends a command),
        // never inside a name ("hi" in "Abhishek").
        val whole = Regex("""(?<![\p{L}\p{N}])${Regex.escape(content)}(?![\p{L}\p{N}])""").findAll(cmd).lastOrNull()
        val command = when {
            content.isEmpty() || "\"$content\"" in cmd -> cmd
            whole != null -> cmd.replaceRange(whole.range, "\"$content\"")
            else -> "$cmd: \"$content\""
        }
        return Rewritten(said, command)
    }

    /**
     * A's one-line answer (the frozen code's first_line): its first line, after the last arrow before the
     * content's quote (the frozen code took the last arrow anywhere); null
     * for NONE. Unlike [GeminiRewrite.parse], quotes stay: they mark the content. Throws when empty.
     */
    fun parseLine(said: String, answer: String): Rewritten {
        val first = answer.trim().lineSequence().firstOrNull().orEmpty().trim()
        // An echoed "request ->" goes: the last arrow before the content's quote, never one inside it (review P2-27).
        val quote = first.indexOfFirst { it == '"' || it == '“' }.takeIf { it >= 0 } ?: first.length
        val arrow = first.lastIndexOf("->", quote)
        val line = (if (arrow >= 0 && arrow < quote) first.substring(arrow + 2) else first).trim()
        require(line.isNotEmpty()) { "the rewrite wrote nothing" }
        return Rewritten(said, line.takeUnless { GeminiRewrite.isNone(it) })
    }

    /** [json], an answer as JSON ({"command": ..., "content": ...}): [merged]; throws when it isn't one. */
    fun parse(said: String, json: org.json.JSONObject): Rewritten =
        merged(said, Typed(json.text("command"), json.text("content")))

    /** A field as a string; "" when it's missing or null (Python's o.get(k) or ""). */
    private fun org.json.JSONObject.text(key: String) = if (isNull(key)) "" else opt(key).toString()
}
