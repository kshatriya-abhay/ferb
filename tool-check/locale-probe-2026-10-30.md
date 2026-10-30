# Locale probe, 2026-10-30

Does the locale Ferb gives Gemini Nano's speech recognition (ML Kit GenAI, Advanced mode) change
what it writes? Asked after the user's Japanese and French phrases came back in Devanagari, with
Ferb set to en-US. Harness: the `:localeprobe` app and `tool-check/locales.py` (a separate app, so
the release Ferb wasn't touched; uninstalled after). Redacted: names, places and personal requests
are left out; the full report stayed on the laptop.

## What ran

- 50 clips, each under 8 locale and mode pairs (400 recognitions, one-shot from a file):
  - 35 of the user's voice bench recordings: 9 English, 12 Hinglish, 14 Hindi;
  - 15 clips made by the phone's TTS: "set an alarm for seven tomorrow morning", "call my
    mother" and "pause the music", in English, Hindi, Japanese, French and Tamil.
- Advanced mode: en-US, en-IN, hi-IN, ja-JP, fr-FR. Basic mode: en-US, en-IN, ja-JP. Basic
  hi-IN and fr-FR needed a download, which the probe never asks for.
- Two long recordings failed under every locale (AUDIO_BUFFER_OVERFLOW: the probe writes the
  whole clip at once). That's the harness, not the locale.

## From the library (bytecode, 1.0.0-alpha1) and Google's docs

- One locale per client (default Locale.US), no query for supported locales, no detected language.
- checkStatus in Advanced mode is the same for every locale. AICore lists Advanced models by an
  id, not a language, so ML Kit says AVAILABLE once any one is there. Basic mode is per locale.
- The locale goes to AICore as a hint: in the download request, and in the session's config as a
  list of language tags (the public API fills in one).
- Documented Advanced locales include en-US, hi-IN (beta), ja-JP and fr-FR; en-IN and ta-IN
  aren't listed.

## The phone (read-only)

System locale en-IN only (`persist.sys.locale`, `system_locales`); product locale en-US; region
India (SIM, time zone). Nothing in the phone's settings asks for Hindi.

## Script of what came back, per language spoken (Advanced mode)

| spoken | en-US | en-IN | hi-IN | ja-JP | fr-FR |
|---|---|---|---|---|---|
| English (user, 8 heard) | Latin 7, Devanagari 1 | same as en-US | Devanagari 4, mixed 2, Latin 2 | Latin 3, Japanese 2, mixed 3 | Latin 8 |
| Hinglish (user, 11 heard) | Devanagari 6, Latin 5 | same as en-US | Devanagari 10, mixed 1 | Japanese 5, Devanagari 4, mixed 1, Latin 1 | Latin 7, Devanagari 3, mixed 1 |
| Hindi (user, 14) | Devanagari 13, Latin 1 | same as en-US | Devanagari 14 | Japanese 8, Devanagari 4, mixed 1, Latin 1 | Latin 10, Devanagari 3, mixed 1 |
| English (TTS, 3) | Latin 3 | Latin 3 | Latin 2, Devanagari 1 | Latin 3 | Latin 3 |
| Hindi (TTS, 3) | Latin 3 | Latin 3 | Devanagari 3 | Japanese 3 | Latin 2, mixed 1 |
| Japanese (TTS, 3) | Japanese 3 | Japanese 3 | Japanese 1, Devanagari 1, nothing 1 | Japanese 3 | Japanese 3 |
| French (TTS, 3) | Latin 3 | Latin 3 | Latin 2, mixed 1 | Latin 2, mixed 1 | Latin 3 |
| Tamil (TTS, 3) | Latin 2, Malayalam 1 | Latin 2, Malayalam 1 | Devanagari 3 | Latin 1, Japanese 1, Korean 1 | Latin 2, Tamil 1 |

The error rows (the two long clips) are left out. Basic en-IN wrote everything in Latin, Hindi as
romanised Hinglish ("Papa ko call karo.").

## Findings

1. **en-IN is en-US.** Every clip came back the same, character for character.
2. **en-US detects other languages itself.** The TTS Japanese came back as Japanese
   ("明日の朝7時にアラームをセットして") and the French as French ("Appelle ma mère."). So en-US
   doesn't turn Japanese or French into Devanagari. What the user saw is more likely their own
   voice and accent with the model's lean to Hindi here; it would take their own recordings to tell.
3. **hi-IN hears the user's Hindi about as well as en-US**: the same text on almost every clip,
   one better and one worse. On English it's worse: it writes English in Devanagari ("अलार्म फॉर
   10:05" for "Alarm for 10:05.") and gets more wrong ("Show me my alarms" as "Xiaomi my alarms",
   "Set an alarm" as "सेटरन अलार्म"). On the TTS Hindi it was clearly better ("कल सुबह 7:00 बजे का
   अलार्म लगा दो", where en-US wrote "can's subah 7 baje ka alarm laga do").
4. **A wrong locale is worse than none.** ja-JP and fr-FR translate Hindi into Japanese or French,
   or garble it ("अगला गाना बजाओ", "play the next song", came back as "あ、次の歌な。" under ja-JP).

## Decision (the user's, 2026-10-30)

en-US for English and for English + Hindi, and no language setting: a wrong locale translates, and
Ferb's rewrite and Needle 3 handle English and Hindi only anyway (`NanoSpeech.LOCALE`).
