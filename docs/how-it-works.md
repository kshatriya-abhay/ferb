# How Ferb works

The details behind the [README](../README.md): how a request becomes tool calls, when Ferb asks first, each tool, Ferb Link and voice, and routing. For building, testing and tuning, see [development.md](development.md).

## What it does in detail

- **A chat:** your requests on the right, the assistant's replies on the left, newest at the bottom; scroll up for the history. A request shows at once, with a "Thinking…" bubble until Needle 3 answers.
- **Requests to tool calls:** each request goes to Needle 3 with the tools it mentions (see *Routing*), as JSON schemas, and Needle 3 returns the calls it needs, with a confidence. The app checks every argument itself, then:
  - runs the calls straight away when the confidence clears the bar for the tools called;
  - asks first ("Do it" / "Skip") below that;
  - says so when no tool fits.
- **A default bar per tool**, set from the desktop check (adjustable per tool): just above the confidence of the tool's wrong calls, while letting most right ones run. Opening an app, the alarm list, the agenda and calendar events 0.3, alarms, timers and notes 0.4, volume 0.5, settings pages and directions 0.6, media and camera 0.7, flashlight 0.75 (talk about the torch, "my flashlight is broken", reads as "turn it on" at up to 0.70). Cactus's general advice is 0.7 for everything; Needle 3's confidence varies a lot with phrasing (the same alarm request scores 0.3–0.9), so a single 0.7 bar asked about most good requests.
- **Buttons on results:** "Turn off" / "Turn on" for the flashlight, a slider on the volume result (drag to change the media volume), the opposite media action, "Show alarms" and "Show timers" (to change or delete them in the clock app), "Open again" for apps and pages.
- **Keywords per tool** pick the tools a request goes to; a call to a tool the request doesn't mention (possible with the fallback) asks first, whatever its confidence. They match whole words, also with an "s" on the end ("alarm" finds "alarms"), and each tool's can be changed in its settings.
- **Numbers must come from the request:** an alarm time or timer length with a number the request doesn't say asks first ("I hate alarms" became an alarm at 6:45, the tool's example, at 0.63). Number words count ("half past six").
- **Calls that open another app run one at a time**, each after the assistant is back in front: two alarm intents 14 ms apart set only the first on HyperOS.
- **Every tool is configurable:** Settings → Tools lists them; each row's switch turns the tool on or off, and the rest of the row opens its page:
  - on or off;
  - **always confirm before running** (on by default for Calls and Messages, which reach other people, and Calendar, where a day or time read wrong is easy to miss; turning it off warns that it's not recommended);
  - Android permissions it needs, e.g. the contacts for Calls and Messages, calls for Calls, and the calendar for Agenda and Calendar: asked for when the tool is turned on, and from the reply (**Allow**) when a request needs one Ferb doesn't have. Without them a tool does what it can: Calls opens the dialer, Messages the messages app with the message written, Agenda and Calendar the calendar app;
  - a **confidence slider** (0.10–1.00, steps of 0.05) from which it runs without asking, with a reset to its default;
  - its **keywords**, on a page of their own: add, edit (tap a word), delete, or reset to the tool's own words. Saved only when changed, so later changes to the defaults reach everyone else;
  - the tool's own options, e.g. **which clock** alarms and timers go to (the phone's clock app for now; more providers later), or **which calendar** new events go to (your Google account's own until you pick one);
  - examples, and the schema Needle 3 gets.
  
  Changes across the Tools list and the tools' pages are one draft with one **Save**; leaving with unsaved changes asks. Nothing needs reloading after a save: each request picks its tools from the saved set.
- **Tools** (all offline, none needs the assistant role):

| Tool | On by default | How |
|---|---|---|
| Alarms (`set_alarm`) | ✓ | `AlarmClock.ACTION_SET_ALARM`, skipping the clock app's UI; the next time it's that time of day |
| Timers (`set_timer`) | ✓ | `AlarmClock.ACTION_SET_TIMER` |
| Open apps (`open_app`) | ✓ | Launch intent; the name is matched against the launcher's apps by the app, not the model |
| Settings pages (`open_settings`) | ✓ | `Settings.ACTION_*` (Wi-Fi, Bluetooth, battery, display…, or Settings itself) |
| Flashlight (`set_flashlight`) | ✓ | `CameraManager.setTorchMode` |
| Show alarms (`show_alarms`) | | `AlarmClock.ACTION_SHOW_ALARMS` |
| Media volume (`set_volume`) | | `AudioManager.setStreamVolume` on the media stream; requests about the ringer, alarm or notification volume ask first |
| Media controls (`control_media`) | | Media key events |
| Camera (`open_camera`) | | `MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA` |
| Notes (`create_note`) | | Google's "create note" action (`com.google.android.gms.actions.CREATE_NOTE`, the one its "Take a note" voice command sends) with the note as `EXTRA_TEXT`; Keep and other notes apps take it (Keep opens it in a small dialog to save, it doesn't save it by itself), and with several Android asks which. A note whose words aren't mostly the request's asks first |
| Lists (`add_to_list`) | | Lists are notes in the notes folder (Settings → Tools → Notes, picked through the Storage Access Framework, e.g. in an Obsidian vault) with Markdown tasks, `- [ ] milk`. Finds the list by name (as said, or by sound), else makes a new note with Ferb's id in its frontmatter; appends `- [ ] item` lines in the file's own line endings; Undo removes them while they're still the last lines. Ferb never rewrites a file whole: Syncthing syncs it |
| Show lists (`show_list`) | | The list's unticked items in the chat, each with a box that ticks it in the file (one character) |
| Tick off (`check_off`) | | Ticks the first unticked item that matches (as said, or by sound), on the list named or the one that has it; Undo unticks |
| Reminders (`add_reminder`) | | Kept by Ferb and shown as its own notification at the exact time (`USE_EXACT_ALARM`), with Done and Snooze (10 minutes); rescheduled after a reboot. The app reads the time from the request ("in 20 minutes", "tomorrow at 9", "Friday" meaning 09:00) and the repeat from its words ("every Monday"). A reminder on a note writes `ferb-id`, `remind: 2026-10-20 09:00` and `repeat` in the note's frontmatter, so it survives a rename and is rebuilt from the folder, and a `remind:` added in Obsidian becomes a reminder too (the folder is read on launch, at each reminder, after a reboot and daily). Needs notifications allowed; Undo removes it |
| Directions (`get_directions`) | | Google Maps' directions link (`https://www.google.com/maps/dir/?api=1&destination=…`), which Maps opens as a route to check and start; Maps looks the place up, so the app stays offline. The app reads the travel mode from the request's words (walking, cycling, scooter or bike, bus or train, driving), not from Needle 3, which filled one in for every request. A destination whose words aren't mostly the request's asks first |
| Calls (`make_call`) | | Finds the person in the contacts (`READ_CONTACTS`, read on the phone only) by spelling, by sound ("Shriyans" finds "Shreyansh") and by relation ("mum" finds "Maa"; Devanagari names are transliterated), and offers the best match, with "Open dialer" to find someone else. Tapping it calls them at once (`ACTION_CALL`, with `CALL_PHONE`; without it, the dialer with the number). A typed number is offered the same way. Without asking (if you turn that off), it only opens the dialer with the best match (`ACTION_DIAL`), so nobody is rung without being picked. Always asks first by default |
| Messages (`send_text`) | | Finds the best-matching contact the same way and shows them with the draft, to Proceed or Cancel. Proceed opens the messages app with the message written (`ACTION_SENDTO` with `sms_body`, no SMS permission): you press send. Always asks first by default |
| Agenda (`show_agenda`) | | Lists a day's events in the chat, read from Android's calendar provider (`READ_CALENDAR`; Google Calendar syncs it, so the app stays offline), with repeating events, all-day ones on their own dates, and only the calendars your calendar app shows. Tap an event to open it, or "Open calendar" for the day. The app reads the day from the request ("tomorrow", "Friday", "12 Oct"), not from Needle 3, which doesn't know today's date; a day that isn't one ("the agenda of the G20 summit") asks first |
| Calendar (`add_event`) | | Shows the event (title, day and time, the calendar, any clash with what's already there) and adds it on "Add" (`WRITE_CALENDAR`, straight into the calendar provider), with "Open" and "Undo" on the result. The app reads the day and time from the request: "tomorrow at 5", "Saturday from 2 to 3:30 pm", "next Tuesday morning", "for 2 hours"; a day alone is all day, a time alone the next time it comes, and an event lasts an hour unless an end or length is said. Without the permission, it opens the calendar app's new-event screen filled in (`ACTION_INSERT`), to save there. Always asks first by default |

## Ferb Link: a bigger model on your computer

**Ferb Link** (work in progress, in its own repo) is a companion app that holds the internet access Ferb doesn't have and passes requests to a model on your own computer, such as a laptop on your Tailscale network running Gemma 4 E2B through llama.cpp. Ferb talks to it through one `ContentResolver.call` on its content provider (`link/FerbLink.kt`, Ferb's copy of Ferb Link's `LinkContract`, version 1), so Ferb itself stays offline; Ferb Link answers only apps signed with its key.

**Settings → Ferb Link**, off by default:
- **Off:** Needle 3 answers everything.
- **When Needle 3 is unsure:** Needle 3 answers first; when its calls are below their bar, or it finds no tool, Ferb asks the computer, and the computer's answer wins, "no tool" included (Gemma 4 E2B turned down 30 of 36 near misses in the bench, Needle 3 14).
- **Choose in the chat:** under the chat box, Phone (Needle 3) or Computer (Ferb Link), kept until changed.

Either way the computer gets the tools Needle 3 would get, one request at a time, with 10 s to answer, and Needle 3 answers whenever the computer can't (the reply says why). In "Choose in the chat" mode with Ferb Link picked, Needle 3 doesn't run unless the computer fails. The computer's answers run without asking when the usual checks pass (the tool isn't set to always ask, the request mentions it, and its values are the request's), with no confidence bar: no stand-in for their confidence was safe in the bench (Gemma 4 E2B's first-token probability let 20 wrong calls through even at 1.00), and calls, messages and new events ask by default anyway. Answers from the computer say so with a laptop icon and its time, and Needle 3's answers in its place with a phone icon and why, in a few words (Ferb Link sends a short error and a longer one); tapping a reply opens its details: the model, the reason at length, and Needle 3's answer next to the computer's, when Needle 3 answered first. Until Ferb Link works, Settings → Ferb Link has a checklist that ticks itself off from Ferb Link's status and Ferb's own check: install it (from Releases), set up its server (its quick start), Tailscale on both, pair (the computer's QR code, `ferblink://pair`), switch it on, the computer answers. Ferb also suggests Ferb Link once in the chat, after Needle 3 has found no tool or been unsure three times while Ferb Link is off; dismissed, it doesn't come back.

**Jev selects the tool** ("Use Jev to select the tool" on Settings → Ferb Link: off by default, in every mode but Off, and only while Jev is on in Ferb Link, which holds TypeSafe's key and a spending limit). With it on, every request (typed, or a spoken one's English text) goes first to Jev, TypeSafe's cloud model, through Ferb Link: the request's text and the routed tools' names and descriptions (or every tool that's on), never values. Ferb itself stays offline. Jev picks a tool or "none" with a probability. From 0.5 (`LinkPolicy.JEV_BAR`):
- a tool: the answering model gets only that tool. Needle 3 runs it at that tool's own bar plus the usual checks; the computer gets Jev's next tools too (0.25 or more, three in all), so a request for two things can get both, and its answer runs under the usual checks. In "When Needle 3 is unsure", Needle 3 with Jev's tool first, then the computer with Jev's tools;
- "none": no tool, with the usual offers to turn one on.

Below 0.5, failed, after 6 s, or at the spending limit, it's as without Jev, and the reply says why in a few words ("Jev wasn't sure"). The chat box shows "Using Jev", and the details show Jev's pick ("Jev picked Alarm (0.93)"). In Ferb's bench (2026-10-16, 460 English requests), right answers / wrong runs: Phone 360 / 19 to 426 / 6, Computer 418 / 21 to 434 / 10, "When Needle 3 is unsure" 409 / 33 to 434 / 12; the computer on Hindi and Hinglish 20 to 22 of 24 right, no wrong runs either way. **Known limit:** on the phone Jev's one tool means a request for two things gets at most one, and on the computer one of the bench's seven two-thing requests still ran only the second. It replaced Jev as a third answerer in the chat box and Jev as a second opinion when Needle 3 is unsure (both 2026-10-15/16): one switch over every mode is simpler, and the bench showed every mode gains from it.

**Voice**, only with Computer picked under the chat box and a computer whose model takes audio: with the box empty, its button is a mic; tap it, speak, tap stop, then Send (✕ drops the recording). Ferb records (mono 16 kHz Opus, at most 30 s; it has the microphone permission but still no internet) and Ferb Link has the computer transcribe it. The computer returns what it heard (Hindi in Latin letters, as Hinglish is typed) and the request as a plain English command, which Ferb answers like a typed one: its keywords, dates and Needle 3 need English. The request's bubble shows the English command with "Heard: …" under it. Ferb asks Ferb Link to wake the computer's model when recording starts, so its load hides behind the speech. It does the same, at most once a minute, when the chat box is tapped or Computer is picked, for typed requests; spoken requests then follow the same rules as typed ones.

## Routing

Needle 3 weighs every tool when it has five or fewer; with more, only the five that best match a request, so a right tool can be left out. So the app doesn't give it every tool turned on: each request gets only the tools whose keywords it contains (`ToolRouter`), and `needle_init` re-runs on the loaded model when that set changes (about 0.1 s on the phone; the model isn't reloaded). A request that mentions no tool gets every tool turned on, as before routing, and Needle 3's own pick of the five that match best ("I want to watch youtube" → YouTube); those calls ask first, since the request doesn't name the tool. The details sheet shows which tools were checked.

On the desktop check with all nine tools, routing kept as many answers right as giving Needle 3 every tool (111 vs 110 of 132), and raised the confidence of right calls (opening apps: 0.38–0.95 → 0.64–0.96). The confident wrong calls left are harmless: "I hate alarms" shows the alarm list, "what apps do I have" opens the apps settings page.

A setting to check every tool, five at a time, when the mentioned ones gave no call was removed on 2026-10-12: on the desktop check it added one right call that runs and let unrelated requests reach tools, and Ferb Link now answers when Needle 3 finds nothing. `tool-check`'s `fallback` mode still measures it.

## Notes in Ferb

With a notes folder picked, the chat's top bar has **Notes**: the folder's `.md` files, newest first. A note shows as formatted Markdown (mikepenz's multiplatform-markdown-renderer, on JetBrains' Markdown parser; no images, so no network), its frontmatter folded into **Properties**, its tasks as boxes that tick in the file: one character, found again by order and text if Obsidian or Syncthing changed the file meanwhile. **Open** on a list or a saved note, and tapping a reminder's notification, show it there. **Edit** opens it as raw Markdown, styled as it's typed (headings, bold, italic, links and `[[links]]`, tasks, quotes) without changing a character; the frontmatter is kept apart and joined back as it was, the file keeps its line endings, and nothing is written when nothing changed. If the note changed elsewhere meanwhile, a change to the frontmatter only (a reminder moved) is kept with the edit; a change to the text asks: keep mine or keep theirs.

## Inside the app

```
text box ─► AssistantViewModel ─► AssistantEngine ─► Needle.kt ─JNI─► assistant_jni.cpp ─► libneedle.a
            checks and runs calls    tools, facts      one engine thread   maps the bundled    (prebuilt)
                                                                           model from the APK
```

- The model is stored uncompressed in the APK and read through `AAssetManager`, so it's mapped in place rather than copied.
- The engine is process-global, not thread-safe and has no unload call; every call runs on one thread, and the model stays loaded while the app runs.
- System facts (date, locale, device) are given with the tools; the app re-initialises when the date changes.

