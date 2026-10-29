# Voice bench, 2026-10-29: how Gemini Nano hears and how R rewrites

The user's first voice-bench recording (Settings → Bench → Voice bench, debug build).
- 81 entries cover 77 of the 78 cards. Card 30 (call someone by a relation) holds card 29's request ("am I free tomorrow"), so card 30 is still to record.
- The cards come with no expected answers, and nothing is tuned on them. Every fix proposed below is general and has to be checked on a fresh recording before it ships.
- Names, contacts and personal details are replaced with [name] or summarised. The raw entries and clips stay on the laptop, in build/voice/ (not committed).

The pipeline is the shipped one:
- Gemini Nano speech (one locale, en-US);
- rewrite R;
- Needle 3;
- DateGate.

R2's rewrite is logged beside R's for comparison. Nothing ran on the phone.

## Gemini Nano's transcription

| Tendency | Count | Examples |
|---|---|---|
| Hindi comes out in Devanagari, even when spoken as Hinglish | Hinglish cards: 13 Devanagari, 10 Latin, 2 mixed. Pure-Hindi cards: 14 of 15 Devanagari | "कल सुबह जल्दी उठा देना"; mixed: "Mero Ko Roz शाम 6:00 बजे मेडिसिन लेने का याद दिलाना" |
| English words inside Hindi are written in Devanagari | most Devanagari entries | "डॉक्टर से अपॉइंटमेंट … कैलेंडर में इवेंट", "70% वॉल्यूम", "रिमाइंडर" |
| Wrong script once | 1 | a mixed request came out in Bengali script, which R read as "open the podium" |
| The opening command words get misheard | 11 | "Set an alarm" heard as "Certain alarm", "Sudden alarm" (×2), "sudden Rampur", "Alon for", "Saturday timer"; "Pause" as "Paws"; "Show me" as "Shomi"; "add an event" as "I don't have an event" |
| A stray clause before the request | 7 | "She's at the bottom. Mummy ko message karo…", "Take a break. परसों सुबह…", "Yeah, Rick, event add kar do…". It isn't tap noise (the first 300 ms are quiet); these clips start speech a little later (1.0 s vs 0.7 s median) |
| A Hindi hour gets ":00" added | 6 | "शाम 6:00 बजे", "पौने 9:00 बजे" (paune nau), "8:00 बजे" |
| Fraction words stay words; other numbers become digits | fractions 3/3 | "सवा तीन", "ढाई मिनट", "पौने"; but "75 मिनट", "15 तारीख", "5000" |
| English times are written "5 a.m." / "3:00 p.m." | 8 | |
| A name gets several spellings | one contact, 3 spellings | Latin, क्रिटिका, कृतिका; an uncommon name came out as two English words ("[name]" → "math three") |
| Fillers are kept | 6 | "yaar mujhe like kal shaam ko na 6 baje…", "Um", "oh," |
| Lists have no commas | 1 of 1 | "add cheese milk and bread", which became one list item |
| Pauses show as a double space; nothing is cut off | 4 of 4 pause cards whole | "remind me of  my dog tomorrow"; a change of mind is kept whole: "…5 p.m. No, … 6 p.m. instead" |
| Content words misheard | about 3 | "दो-दो बल रहा है" (unclear); "तूने" heard as "तोने" |

Bench note: one-shot recognition failed on every clip of 8.1 s or longer (5 of them), and the bench fell back to streaming, which worked. Clips of 7.6 s or shorter all worked one-shot.

## R's rewrite

| Tendency | Count | Examples |
|---|---|---|
| Repairs a misheard command word | 10 of 11 | "Certain alarm for tomorrow 5 a.m." → "Set an alarm for tomorrow at 5 a.m."; "Paws the music" → "Pause the music" |
| …but keeps a misheard word as content | 1 | "Saturday timer for two hours…" → "…on Saturday" (R2 dropped it) |
| Keeps the stray opening clause as content | 2 of 7 | "Take a break." became the reminder's text; "For you." went into a message |
| Converts fraction times wrong | 2 of 4 | "quarter to 9 pm" → 9:15 pm; "पौने 9" → 9:30. DateGate caught the second because the words as said read 8:45 |
| Adds am/pm nobody said | R 1, R2 1 | "दो बजे उठा देना" → "2 pm" (the gate asked); R2: "सवा तीन" → "3:15 pm" |
| Translates messages despite "keep the user's own words" | 3 of 4 Hindi messages | "main late ho jaunga ghar aane mein" → "I will be late coming home"; "keep it verbatim" became "Keep it private." and went into the message. The Latin-Hinglish one was kept as said |
| Drops the event's subject | 2 of 7 calendar adds | "a jog … from 7:15 a.m. to 8:45" → no title, so Needle held back; "my anniversary on first of December 2029" → the title became the date. R2 kept both subjects |
| Changes the tool | 1 | an event said as a plan ("kal shaam 7 baje garba khelne jaunga") → a reminder (R2: calendar) |
| Acts on things that aren't requests | 3 of 7 non-requests | complaint about an alarm → "Remind me to wake me up soon"; past statement → "Send a text to [contact]…" (a placeholder); "send [name] ₹5000" → a text saying "₹5000" (Confirm). World question, weather and greeting → NONE, 3 of 3 |
| Placeholder text | R 1, R2 1 | "[contact]"; R2: "…on the 5th at [time]." |
| Relation words normalised inconsistently | 1 | "Mummy" kept once, "Mom" another time, which matters for contact lookup |
| Fillers and Hinglish handled cleanly | 6 of 6 | "yaar mujhe like kal shaam ko na 6 baje ka alarm laga dena" → "Set an alarm for tomorrow evening at 6 o'clock." |

R2 compared with R on these entries:
- **Better on 6:** the jog and anniversary subjects kept, garba as an event, "on Saturday" dropped, the past statement and the ticket booking → NONE.
- **Worse on 3:**
  - an alarm with a misheard start ("sudden Rampur Friday 2 pm") became a calendar event;
  - a note became a list item;
  - a reminder became a timer (R's rewrite of that one was also wrong).

## Downstream: what Ferb read from the words as said

- **Ordinal and तारीख dates aren't read** (4 needless asks): "on the fifth of November", "first of December 2029", "15 तारीख", "5 तारीख".
- **A yearly repeat isn't read:** "1 December … recurring annually" became a one-off.
- **An event with a day but no time runs at 9:00,** e.g. "dinner … on Thursday".
- **"Show my alarms" got no call** at 0.81. Check whether show_alarms is switched off in this phone's tool settings.
- **The bench shows Needle's raw call.** The resume → play fix applies when the call is prepared, so the "pause" logged for "music resume karna" is not what would run.

## Proposed general fixes (none shipped)

1. **Messages and notes:** keep Hindi as said, transliterated into Latin Hinglish, rather than translated. Drop remarks about the message ("keep it verbatim") instead of putting them in it.
2. **Fraction times:** add rules and examples for "quarter to / paune X" = (X−1):45 and "sava X" = X:15. Never add am/pm unless said or implied (subah, shaam, raat, tonight). Keep DateGate as the guard.
3. **Openers:** ignore a short clause before the request that doesn't belong to it. Never use a misheard leading word as content: a weekday right before "timer" is not a day.
4. **Non-requests:** statements about the past, complaints and money transfers → NONE. Never output placeholders like [contact] or [time]; ask instead.
5. **Date reader (A):** read ordinal day words (first … thirty-first), "tarikh/तारीख", and yearly repeats ("every year", "annually", "har saal").
6. **Lists:** have the rewrite separate list items with commas.
7. **Relations:** keep the relation word as said ("Mummy", not "Mom").

Each should be checked first on the existing sets (tool-check, datetime-v2, and blind only for scoring), then on a fresh voice recording.
