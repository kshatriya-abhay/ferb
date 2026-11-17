# Changelog

## 0.2

The focus of this release is code quality: a file-by-file review of the whole app, following the principles of [pstack](https://github.com/cursor/plugins/tree/main/pstack). What you'll notice:

- With Gemini Nano, voice waits 2.2 s after its last words, not 2 s, so a pause mid-sentence doesn't send the request early.
- Messages, notes, reminders, events and list items keep your own words, in Hindi too (in Devanagari when it was written that way), instead of the rewrite's English. With Gemini Nano or Gemma 4. Ferb asks first when those words aren't in what you said, when what you said asks for another action, or when the rewrite dropped a time you gave. With Needle + Whistle nothing changes.
- Messages also understands Hinglish "tell" after a name: "papa ko bolo …", "Rahul ko bata do …". If the rewrite turns one into a call, Ferb asks first.
- A time inside the words you want kept is no longer taken as the time to set. In "remind me at 6 to tell Priya the bus comes at 8:40", the reminder is at 6. A text like "I'll be there by 6" no longer makes Ferb ask about the time. With Gemini Nano or Gemma 4.
- Times with the minutes in words: "seven thirty", "ten fifteen", "eight forty five", "six oh five".

Reminders
- Reminders are never lost: if one entry in the reminders file can't be read, the rest are kept, and the file is saved safely.
- A monthly reminder on the 29th to 31st, or a yearly one on 29 February, keeps its day after a short month instead of moving earlier for good.
- A snoozed reminder shows again even if the phone was off when it was due, and a snoozed daily or weekly one no longer skips its next time.
- A repeating reminder shows once each time, not twice.
- The New reminder form offers Android's settings when notifications are turned off for good.

Dates, the calendar and timers
- The agenda can look back: "what did I have yesterday" shows yesterday.
- An event with a day and no time is added as an all-day event. Alarms and reminders still default to 9:00.
- A night hour after a day ("kal raat 1") asks which night, naming both days, instead of guessing.
- An event said to repeat ("every Monday") asks first, as Ferb adds it once, and one at the same time as another of the same name no longer goes in without a word.
- Timers read "a quarter of an hour" and "1 hour 30" right, and ask when they can't read a length instead of guessing.

Calls, texts, lists and more
- "Call 112" and other short numbers call the number instead of looking for a contact.
- A contact's default number is used, then a mobile, and a word like "bhaiya" alone no longer matches everyone with it in their name.
- A call without the call permission offers Allow, and opens the dialer meanwhile.
- "chawal aur dal" adds two items to a list; ticking off something that isn't there no longer ticks a blank line; and the list that matches best wins.
- "Directions to the bus stop" no longer opens public transport directions.
- "Turn the volume up a little" asks before setting a number you didn't say.
- A note's title starting with a dot, or a long one ending in an emoji, is saved whole.

Replies
- A reply's card says every reason Ferb asks first, and Details names the real one.
- A failed action no longer leaves the rest spinning, and clearing the chat while actions run no longer closes Ferb.

The assistant and the screens
- Holding the power button opens Ferb's sheet listening at once. Opened by another app, it waits for you to type or tap the mic.
- The sheet says why voice stopped (no microphone, nothing heard), and keeps your computer ready and wakes it when you speak.
- A notice shows once, not again after a trip to Settings.
- A quick double tap on back no longer closes Ferb or skips a screen, and back keeps your place in a list.
- Allow works after you grant a permission in Android's settings; a first refusal asks again before sending you to settings; and turning the phone during Android's dialog no longer loses your answer.
- Setup's tool switches show whether Ferb can do it now; a permission taken back says "Permission removed: Allow".
- A tool turned on from the chat shows as on in Settings, and saving another change keeps it on.
- "Try it" on a tool's page waits until your changes are saved.
- A link with no browser to open it says so instead of closing Ferb.
- The Notes screen reads your notes folder once each time it opens, not twice.
- Open-source licences lists everything Ferb ships.

Gemini Nano
- A busy AICore says Gemini Nano "couldn't be checked", not that it can't run here, and no longer sets Ferb on the larger model for good.
- While it downloads, Ferb asks about it every so often, not every second, and a download that never ends no longer shows "Downloading…" forever.

Ferb Link
- Ferb talks only to a Ferb Link signed with the same key, and sends nothing to any other app in its place.
- Its page says what to do when Ferb Link refuses Ferb or is another version, instead of pairing again; shows "Checking…", not "Turned off", while it looks; and checks your computer again when you come back to it. The check holds Wi-Fi out of power save, as requests do.
- Works with Ferb Link 0.1 too. With 0.1, Gemma 4 answers in one line instead of a structured answer, and your own words are kept either way.

## 0.1.1

- Voice waits for your pauses: a spoken request is sent after 1.8 s of silence (2.5 s while you're starting), not 1 s, and short words after a pause keep it listening. With Gemini Nano, 2 s after its last words.
- Gemini Nano listening stops at 30 s, as the other voice options do, and waits for Send.
- A warning while Battery Saver is on: Gemini Nano voice doesn't work until it's off. The warning links to Battery Saver's settings, and the error now says so plainly.

## 0.1

The first release.
