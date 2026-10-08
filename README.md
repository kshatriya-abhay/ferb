# Ferb

*An on-device assistant for Android.*

A private assistant for Android. Type a request ("wake me up at 6:45", "open Wi-Fi settings") and it acts, using **[Needle 3](https://huggingface.co/Cactus-Compute/needle3)**, Cactus Compute's 35 MB tool-calling model, running on the phone. The model is bundled in the app, and the app has **no internet permission**: nothing you type leaves the phone.

Phase 1 is a normal app with a text box. Voice and becoming the phone's default assistant come later.

## What it does

- **A chat:** your requests on the right, the assistant's replies on the left, newest at the bottom; scroll up for the history. A request shows at once, with a "Thinking…" bubble until Needle 3 answers.
- **Requests to tool calls:** each request goes to Needle 3 with the tools it mentions (see *Routing* below), as JSON schemas, and Needle 3 returns the calls it needs, with a confidence. The app checks every argument itself, then:
  - runs the calls straight away when the confidence clears the bar for the tools called;
  - asks first ("Do it" / "Skip") below that;
  - says so when no tool fits.
- **A default bar per tool**, set from the desktop check (adjustable per tool): just above the confidence of the tool's wrong calls, while letting most right ones run. Opening an app and the alarm list 0.3, alarms, timers and notes 0.4, volume 0.5, settings pages and directions 0.6, media and camera 0.7, flashlight 0.75 (talk about the torch, "my flashlight is broken", reads as "turn it on" at up to 0.70). Cactus's general advice is 0.7 for everything; Needle 3's confidence varies a lot with phrasing (the same alarm request scores 0.3–0.9), so a single 0.7 bar asked about most good requests.
- **Buttons on results:** "Turn off" / "Turn on" for the flashlight, a slider on the volume result (drag to change the media volume), the opposite media action, "Show alarms" and "Show timers" (to change or delete them in the clock app), "Open again" for apps and pages.
- **Keywords per tool** pick the tools a request goes to; a call to a tool the request doesn't mention (possible with the fallback) asks first, whatever its confidence. They match whole words, also with an "s" on the end ("alarm" finds "alarms"), and each tool's can be changed in its settings.
- **Numbers must come from the request:** an alarm time or timer length with a number the request doesn't say asks first ("I hate alarms" became an alarm at 6:45, the tool's example, at 0.63). Number words count ("half past six").
- **Calls that open another app run one at a time**, each after the assistant is back in front: two alarm intents 14 ms apart set only the first on HyperOS.
- **Every tool is configurable:** Settings → Tools lists them; each row's switch turns the tool on or off, and the rest of the row opens its page:
  - on or off;
  - **always confirm before running** (on by default for Calls and Messages, which reach other people; turning it off warns that it's not recommended);
  - Android permissions it needs, e.g. the contacts for Calls and Messages: asked for when the tool is turned on, with a row to allow them later;
  - a **confidence slider** (0.10–1.00, steps of 0.05) from which it runs without asking, with a reset to its default;
  - its **keywords**, on a page of their own: add, edit (tap a word), delete, or reset to the tool's own words. Saved only when changed, so later changes to the defaults reach everyone else;
  - the tool's own options, e.g. **which clock** alarms and timers go to (the phone's clock app for now; more providers later);
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
| Directions (`get_directions`) | | Google Maps' directions link (`https://www.google.com/maps/dir/?api=1&destination=…`), which Maps opens as a route to check and start; Maps looks the place up, so the app stays offline. The app reads the travel mode from the request's words (walking, cycling, scooter or bike, bus or train, driving), not from Needle 3, which filled one in for every request. A destination whose words aren't mostly the request's asks first |
| Calls (`make_call`) | | Finds the person in the contacts (`READ_CONTACTS`, read on the phone only) by spelling, by sound ("Shriyans" finds "Shreyansh") and by relation ("mum" finds "Maa"; Devanagari names are transliterated), and offers the best three, with "Open dialer" to pick by hand. The pick opens the dialer with the number (`ACTION_DIAL`, no call permission): you press call. A typed number is dialled as it is. Always asks first by default |
| Messages (`send_text`) | | Finds the best-matching contact the same way and shows them with the draft, to Proceed or Cancel. Proceed opens the messages app with the message written (`ACTION_SENDTO` with `sms_body`, no SMS permission): you press send. Always asks first by default |

### Routing

Needle 3 weighs every tool when it has five or fewer; with more, only the five that best match a request, so a right tool can be left out. So the app doesn't give it every tool turned on: each request gets only the tools whose keywords it contains (`ToolRouter`), and `needle_init` re-runs on the loaded model when that set changes (about 0.1 s on the phone; the model isn't reloaded). A request that mentions no tool gets every tool turned on, as before routing, and Needle 3's own pick of the five that match best ("I want to watch youtube" → YouTube); those calls ask first, since the request doesn't name the tool. The details sheet shows which tools were checked.

On the desktop check with all nine tools, routing kept as many answers right as giving Needle 3 every tool (111 vs 110 of 132), and raised the confidence of right calls (opening apps: 0.38–0.95 → 0.64–0.96). The confident wrong calls left are harmless: "I hate alarms" shows the alarm list, "what apps do I have" opens the apps settings page.

**Settings → Check all tools as a fallback** (off by default): instead of Needle 3's pick of five, a request that names no tool, or that its mentioned tools give no call, is tried against every tool turned on, five at a time, keeping the most confident answer. One more engine run per five tools, so it's slower with many tools on; on the desktop check it added one right call that runs, and made unrelated requests reach a tool (those ask first).

## How it works

```
text box ─► AssistantViewModel ─► AssistantEngine ─► Needle.kt ─JNI─► assistant_jni.cpp ─► libneedle.a
            checks and runs calls    tools, facts      one engine thread   maps the bundled    (prebuilt)
                                                                           model from the APK
```

- The model is stored uncompressed in the APK and read through `AAssetManager`, so it's mapped in place rather than copied.
- The engine is process-global, not thread-safe and has no unload call; every call runs on one thread, and the model stays loaded while the app runs.
- System facts (date, locale, device) are given with the tools; the app re-initialises when the date changes.

## Tuning the tools: `mise run tool-check`

Needle 3's choices and confidence depend heavily on the tool descriptions. `mise run tool-check [all|default] [routed|fallback|unrouted]` runs Cactus's Linux build of the same engine on the desktop, with the app's own tool JSON, default keywords and bars (exported by a unit test), against `tool-check/requests.txt`: 206 requests with the right tool **and arguments**, including near misses that talk about a tool without asking for it (scored by `tool-check/score.py`, which reads times and durations the way the app does). `routed` (the default) routes each request as the app does (`tool-check/route.py`); `fallback` checks every tool instead, as with the setting; `unrouted` gives every tool to every request. The scorer ends with a table per tool: the confidence of its right calls, of its wrong calls that get past the checks, and the bar that would hold those back. With the default tools, routed (2026-10-08): 157 of 206 right, 69 of 92 calls run without asking, 2 wrong calls would (both open a harmless settings page). Lessons from it are in the comments in `Tools.kt`:
- a timer's length as text ("2 hours", parsed by the app) beats a number of seconds, which the model got confidently wrong ("2 hours" → 120 s at 0.82), and separate fields, which turned "10 minutes" into 10 h 10 min;
- an alarm time as text ("8:00 AM", parsed by the app) beats hour and minute fields, which turned "alarms at 8 and 9 am" into one alarm at 08:09;
- "wi-fi" rather than "wifi" in the enum;
- a photo/video choice was picked backwards about half the time, so the camera tool has none.

## Build and run

Requires [mise](https://mise.jdx.dev), the Android SDK with NDK 28.2.13676358 and CMake 3.22.1, and an arm64 phone.

```sh
mise run bootstrap      # local.properties
mise run fetch          # Needle engine and the bundled model (pinned, checksummed)
mise run test           # JVM unit tests
mise run tool-check     # the tools against the real model, on the desktop
mise run check-offline  # fails if the APK has the INTERNET permission or networking calls in native code
mise run device-test    # on the phone: loads the bundled model, swaps tools
mise run run            # install and launch
mise run send-over-tailscale my-phone   # or send the APK with Taildrop
```

## Credits

| Project | Licence | Used for |
|---|---|---|
| [Needle 3](https://huggingface.co/Cactus-Compute/needle3) by Cactus Compute | Apache-2.0 | The tool-calling model, bundled |
| [Needle engine](https://github.com/cactus-compute/needle) by Cactus Compute | Apache-2.0 | Runs Needle 3 (`libneedle.a`, linked prebuilt; Linux build for `tool-check`) |
| [AndroidX](https://developer.android.com/jetpack/androidx) (Compose, Material 3, Lifecycle) | Apache-2.0 | UI |
| [Material Components for Android](https://github.com/material-components/material-components-android) | Apache-2.0 | The Material 3 window theme behind Compose |
| [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache-2.0 | Background work |
| [Material Icons](https://github.com/google/material-design-icons) | Apache-2.0 | Icons |
| [Zig](https://ziglang.org) (via the `ziglang` Python package) | MIT | Builds the desktop `tool-check` against libc++ (not shipped) |

## License

Apache-2.0: see [LICENSE](LICENSE). Needle 3 and the Needle engine, fetched at build time, are Apache-2.0 too.
