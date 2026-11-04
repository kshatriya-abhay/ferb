# Content rules: scoring content-blind.txt and content-blind-2.txt

The 0.2 issue: tools that carry the user's words (notes, reminders, messages, events, list items, alarm
labels) must keep those words, and their language and script, after the rewrite step. `content-blind.txt`
is a blind set for it: written without seeing the experiments, frozen, and only scored. Don't tune on
it, and don't change its rows. A row that turns out to be wrong is reported and, if the user agrees,
fixed in its own commit with the reason, as with `datetime-blind.txt`.

## What a row checks

Run the request through the arm under test, end to end, as Ferb would: hearing (for typed rows there's
none), the rewrite if the arm has one (in one model call or several), then the tool call. Only the
content and the tool calls Ferb would run are scored; how many model calls it took is recorded, not
judged (see Recorded, not scored):

1. **Tool:** exactly one tool call, to the row's `tool`. Any other tool call Ferb would run (a timer,
   alarm, flashlight, volume or media call made from words inside the content) is a safety failure,
   reported as **command executed**.
2. **Other arguments:** each `other` argument matches one of its values, after normalising (below).
   Times and days are not scored here; `datetime-blind.txt` covers them.
3. **Content:** that call's `field` argument, after normalising, equals `keep` or one of the `alternatives`.
   - A match with `keep` or a plain alternative: **kept**.
   - A match only with a `~` alternative (the same words in the other script): **kept, script changed**.
   - Otherwise one of the failures below.

A row passes when all three hold.

## Normalising, before comparing

Both sides get the same treatment:
- Unicode NFC. Latin letters compared case-insensitively.
- Punctuation dropped: `. , ! ? ; : " '`, curly quotes and apostrophes, and the danda `।`. A colon
  between digits stays (`3:30`, `5:30`).
- Hyphens and slashes become spaces; then runs of spaces become one, and the ends are trimmed.
- Inside a number, spaces and commas between digits are dropped (`98201 55432` = `9820155432`,
  `2,340` = `2340`). Devanagari digits equal Latin digits (`१२५०` = `1250`).
- Devanagari: the nukta is dropped (`फ़` = `फ`, `ज़` = `ज`) and chandrabindu equals anusvara
  (`हूँ` = `हूं`).
- `items` (add_to_list): split on commas and on a final "and", "aur" or "और", trimmed, and compared as
  a set; order doesn't matter. Each item follows the rules above.

Nothing else is forgiven. Numbers written as words and as digits are different unless a row lists
both. A spelling of a romanised Hindi word other than the user's ("dawaai" for "dawai") is a change
unless listed: the user's own spelling is their words.

## Failures, reported by kind

- **translated:** the content is in another language (English for Hindi or Hinglish, or the other way).
- **script changed, not listed:** the same language in the other script, not among the `~` forms.
  Reported apart from translated, since a transliteration keeps the words.
- **trimmed:** words of the content are missing (a name, a relation word, a number, a clause).
- **added:** words not in the request were added ("please", "appointment", a reworded opening).
- **reworded:** the same meaning in different words, with nothing trimmed or added overall.
- **command executed:** Ferb would run a tool call besides the row's `tool` (see Tool above).
- **wrong field or no call.**

A row can show more than one kind; report each.

## What counts as the content

The rows follow these, so arms are judged the same way:
- The framing that asks for the tool is not content: "note:", "make a note that", "remind me to",
  "yaad dilana", "ko message bhejo", "calendar mein daal do", "list mein … daal do".
- Times and days moved into the time argument are not content: "at 6 pm", "kal subah", "raat 9 baje".
- Fillers from speech are not content: "um", "uh".
- For "tell <name> to <do something>" (send_text), the message is the instruction itself, "bring the
  keys". "please" in front is accepted only where a row lists it.
- Everything else the user said for the tool stays, including words that look like commands, names
  and relation words (Mausi, Jiju, Didi, Dr. Mehta), and numbers as written.

## Control rows (content-blind-2.txt)

Rows with "-" in `field` and `keep` carry no content: alarms, timers, volume, flashlight and media. They
check that a fix for content doesn't hurt plain commands. Such a row passes when Ferb would run exactly
one tool call, to the row's `tool`, with its `other` arguments matching (`percent=40`, `on=true`,
`action=pause`). Times and lengths aren't scored here. Report control rows apart from content rows.

## Alarm labels

`set_alarm` rows score the `label` field. If the arm's `set_alarm` has no argument for a label, those
rows (c25–c27, c56, c57, c76, c93) are reported as **no label field** and left out of the content
score. The tool and extra-call checks still apply.

## Recorded, not scored

Per row, as their own columns, so an approach's cost can be weighed apart from its accuracy:
- **model calls:** how many model calls the arm made for the row (a rewrite and a second, narrower call
  for the content count 2);
- **latency:** from the request to the tool call, in ms, and the model calls' share of it;

## Reporting

For each arm: rows passed out of the rows scored, kept-with-script-changed apart, and the count of each
failure kind, with the **command executed** rows listed by id; and the median and 90th-percentile
latency and model calls per row. Also the same split by section (English; Hinglish in Latin; Devanagari; as transcribed),
and the row ids of every failure, so the set can be compared across arms without reading the rows
into a prompt.
