package dev.abhay.assistant.bench

/**
 * Debug builds only: the voice bench's scenario cards (2026-10-29). Each describes a situation and
 * a language, never a sentence: the user says it in their own words. Written fresh, without looking
 * at any tool-check set; an observation tool, so nothing here has an expected answer or is tuned on.
 */
object VoiceCards {
    val ALL: List<String> = listOf(
        // Alarms
        "Set an alarm for early tomorrow morning, in English",
        "Set an alarm for early tomorrow morning, in Hinglish",
        "Ask to be woken at a time with minutes (like 6:40), in Hindi",
        "An alarm for this evening, saying am or pm, in English",
        "An alarm using quarter past / half past / quarter to, in English",
        "An alarm with saade, sava or paune, in Hinglish",
        "An alarm for a weekday later this week, in English",
        "An alarm without saying morning or evening, in Hinglish",
        // Timers
        "A timer for a few minutes while cooking, in English",
        "A timer with a Hindi amount (dedh, dhai, aadha), in Hinglish",
        "A timer for over an hour, with hours and minutes, in English",
        "A short timer in seconds, in Hindi",
        // Reminders
        "Remind yourself about a bill, mentioning a date, in English",
        "Remind yourself to take medicine every day at a time, in Hinglish",
        "A reminder for later today, in a few minutes or hours, in Hindi",
        "A reminder for the day after tomorrow, in Hinglish",
        "A reminder for next week on a weekday, in English",
        "A reminder that repeats on a weekday every week, in Hinglish",
        "A reminder for tonight with no exact time, in English",
        "A reminder on a date next month, in Hindi",
        "A reminder that mentions a person and a place, in English",
        "A reminder with yaad dilana and a time, in Hinglish",
        // Calendar
        "Add a meeting to your calendar for a day and time, in English",
        "Add a doctor's or dentist's appointment, in Hinglish",
        "Lunch or dinner with a friend on a weekday, in English",
        "An event with a start and an end time, in English",
        "An event for kal or parso with a time, in Hindi",
        "Ask what's on your calendar on some day, in Hinglish",
        "Ask if you're free tomorrow afternoon, in English",
        // Calls and messages
        "Call someone using a relation (mama ji, didi, bhaiya), in Hinglish",
        "Call a friend by name, in English",
        "Message someone with a time inside the message, in English",
        "Message someone in Hinglish, keeping your exact words",
        "Tell someone you're running late, in Hindi",
        "Text a number or an amount (like a code or ₹ price), in English",
        // Apps, settings, phone
        "Open an Indian app (like a payments or food app), in Hinglish",
        "Open an app by its brand name, in English",
        "Open a specific settings page, in English",
        "Turn the torch on or off, in Hinglish",
        "Set the volume to a level, in Hindi",
        "Show your alarms, in English",
        "Take a photo or open the camera, in Hinglish",
        // Music
        "Pause the music, in a word or two, in English",
        "Resume or continue the music, in Hinglish",
        "Skip to the next song, in Hindi",
        "Go back to the previous track, in English",
        // Notes and lists
        "Make a note of something to remember, in Hinglish",
        "Add a few items to a shopping list, in English",
        "Tick something off a list, in Hinglish",
        "Show a list by name, in English",
        // Directions
        "Ask for directions to a well-known place, in Hinglish",
        "Ask how to get somewhere by metro or walking, in English",
        // Hindi in Devanagari-style speech
        "Set a reminder in pure Hindi (no English words)",
        "Set an alarm in pure Hindi",
        "Ask to call someone in pure Hindi",
        "Add an event in pure Hindi",
        // Numbers, names, odd words
        "Say a time like 10:05 or 7:50, in English",
        "Say a date with a month name and a year, in English",
        "Say a number of minutes in Hindi words (pachees, chalees)",
        "A request with an uncommon Indian name in it, in English",
        "A request mixing two languages mid-sentence",
        "A request with a filler (umm, like, na, yaar), in Hinglish",
        "A request spoken fast, in English",
        "A request spoken softly or from further away, in English",
        // Pauses
        "Any reminder, with a pause in the middle of the sentence",
        "Any message, with a pause before the message text",
        "Any alarm, with a pause before the time",
        "Start, stop to think, then finish a calendar event",
        // Near misses (Ferb shouldn't act, or should ask)
        "Something Ferb shouldn't do: a question about the world, in English",
        "Something Ferb shouldn't do: a complaint about your alarm, in Hinglish",
        "Something Ferb can't do: send money or book a ticket, in English",
        "A statement about the past (I already called her…), in Hindi",
        "Ask about the weather, in Hinglish",
        "A greeting or chit-chat, in English",
        "A request with no time where one is needed (remind me to…), in English",
        "A vague time (sometime next week, later), in Hinglish",
        "Two things in one request, in English",
        "Change your mind halfway through a request, in English",
    )
}
