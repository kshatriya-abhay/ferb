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
- A bare hour (no am/pm, part of the day, wake word or 24-hour form like 02:00 or 14:00):
  - for today, or with no day (alarms, reminders and events alike, the user's rule): the nearest of
    its am and pm that's still ahead, today or tomorrow, never in 00:00–04:59 unless said ("2 am",
    "raat 2 baje", "02:30"). "alarm for 5:45" at 03:00 is 05:45; "alarm at 7" at 08:00 is 19:00,
    at 20:00 tomorrow 07:00; "at 2" at 15:00 is tomorrow 14:00; "remind me at 12" at 13:00 is
    tomorrow noon;
  - with a day said: alarms 5–11 am, 12 noon, 1–4 pm ("alarm 6:30 tomorrow" is 06:30); reminders
    and events 7–11 am, 12 noon, 1–6 pm ("kal 6 baje yaad dilana" is 18:00).
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
