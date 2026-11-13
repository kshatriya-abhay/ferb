# Changelog

## Unreleased

- With Gemini Nano, voice waits 2.2 s after its last words, not 2 s, so a pause mid-sentence doesn't send the request early.
- Messages, notes, reminders, events and list items keep your own words, in Hindi too (in Devanagari when it was written that way), instead of the rewrite's English. With Gemini Nano or Gemma 4. Ferb asks first when those words aren't in what you said, when what you said asks for another action, or when the rewrite dropped a time you gave. With Needle + Whistle nothing changes.

## 0.1.1

- Voice waits for your pauses: a spoken request is sent after 1.8 s of silence (2.5 s while you're starting), not 1 s, and short words after a pause keep it listening. With Gemini Nano, 2 s after its last words.
- Gemini Nano listening stops at 30 s, as the other voice options do, and waits for Send.
- A warning while Battery Saver is on: Gemini Nano voice doesn't work until it's off. The warning links to Battery Saver's settings, and the error now says so plainly.

## 0.1

The first release.
