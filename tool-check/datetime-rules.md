# Date and time rules, for new eval sets

The rules every new `tool-check/datetime-*.txt` set should state in its header (2026-10-25). The
existing sets are frozen with the rules in their own headers; don't change them.

- A weekday, bare or with this/coming/next (agle), is the upcoming one: today only if its time is
  still ahead. "next <weekday>" said on that weekday is +7.
- A weekday, a date, "tomorrow" or "in N days" with no time is 09:00. "Today" with no time asks.
- Parts of the day with no hour: morning/subah 09:00, afternoon/dopahar 14:00, evening/shaam 18:00,
  night/raat and tonight 20:00. With an hour: subah is am; dopahar, shaam and raat are pm (raat
  12 is midnight; raat 1–4 is am).
- Wake words (wake, get up, utha, uthana, jaga) make the hour am.
- A bare hour (no am/pm, part of the day or wake word):
  - reminders and events: with a day said, 7–11 am, 12 noon, 1–6 pm; with no day, the next time
    that hour comes round;
  - **alarms** (the user's rule): with a day given (tomorrow, a weekday, a date), 5–6:xx am, 7–11
    am, 12 noon, 1–4 pm ("alarm 6:30 tomorrow" is 06:30). For today (no day, or "today"), the
    half of the clock inside 06:00–18:00 if it's still ahead; else the other half if it's still
    ahead today; else tomorrow by the day-given rule. Never a time already past ("alarm at 7" is
    07:00 at 05:00, 19:00 at 08:00, tomorrow 07:00 at 20:00; "alarm at 5" at 03:00 is 17:00).
- A clock time with no day that has passed today rolls to tomorrow; a time said for today that has
  passed asks. A part of the day with no day that has passed rolls to tomorrow.
- Alarms always need a time.
- kal is tomorrow, even just after midnight; parso +2, narso +3. "in N minutes/hours" counts from
  now; sava +15, saade +30, paune -15, dedh 1:30 or 1.5, dhai 2:30 or 2.5.
- noon is 12:00; midnight is 00:00 at the start of the next day.
- Dates are day-first (en-IN). A date with no year or month rolls forward; one that's explicitly
  past (with a year) or doesn't exist (30 Feb) asks.
- Repeats give the first occurrence after now.
- An event's time is its start ("6 to 8 pm" is 18:00).
