# On-device Assistant

A private assistant for Android. Type a request ("wake me up at 6:45", "open Wi-Fi settings") and it acts, using **[Needle 3](https://huggingface.co/Cactus-Compute/needle3)**, Cactus Compute's 35 MB tool-calling model, running on the phone. The model is bundled in the app, and the app has **no internet permission**: nothing you type leaves the phone.

Phase 1 is a normal app with a text box. Voice and becoming the phone's default assistant come later.

## What it does

- **A chat:** your requests on the right, the assistant's replies on the left, newest at the bottom; scroll up for the history. A request shows at once, with a "Thinking…" bubble until Needle 3 answers.
- **Requests to tool calls:** Needle 3 gets the tools that are turned on (as JSON schemas) and returns the calls a request needs, with a confidence. The app checks every argument itself, then:
  - runs the calls straight away when the confidence clears the bar for the tools called;
  - asks first ("Do it" / "Skip") below that;
  - says so when no tool fits.
- **A default bar per tool**, by how easy the action is to undo (adjustable per tool): 0.3 for harmless ones a tap reverses (flashlight, volume, media, opening an app, settings page or camera), 0.4 for alarms and timers (they create something, shown on the card). Cactus's general advice is 0.7; Needle 3's confidence varies a lot with phrasing (the same alarm request scores 0.3–0.9), so a single 0.7 bar asked about most good requests.
- **Buttons on results:** "Turn off" / "Turn on" for the flashlight, "Undo" / "Redo" for volume, the opposite media action, "Show alarms" and "Show timers" (to change or delete them in the clock app), "Open again" for apps and pages.
- **Calls that open another app run one at a time**, each after the assistant is back in front: two alarm intents 14 ms apart set only the first on HyperOS.
- **Every tool is configurable:** Settings → Tools lists them; each row's switch turns the tool on or off, and the rest of the row opens its page:
  - on or off;
  - **always confirm before running**;
  - a **confidence slider** (0.10–1.00, steps of 0.05) from which it runs without asking, with a reset to its default;
  - the tool's own options, e.g. **which clock** alarms and timers go to (the phone's clock app for now; more providers later);
  - examples, and the schema Needle 3 gets.
  
  Changes across the Tools list and the tools' pages are one draft with one **Save**; leaving with unsaved changes asks. Saving a different set of tools re-runs `needle_init` on the loaded model, which replaces the tools without reloading it (checked on the desktop; answers match a fresh start); other changes need no reload.
- **Tools** (all offline, none needs the assistant role):

| Tool | On by default | How |
|---|---|---|
| Alarms (`set_alarm`) | ✓ | `AlarmClock.ACTION_SET_ALARM`, skipping the clock app's UI; the next time it's that time of day |
| Timers (`set_timer`) | ✓ | `AlarmClock.ACTION_SET_TIMER` |
| Open apps (`open_app`) | ✓ | Launch intent; the name is matched against the launcher's apps by the app, not the model |
| Settings pages (`open_settings`) | ✓ | `Settings.ACTION_*` (Wi-Fi, Bluetooth, battery, display…, or Settings itself) |
| Flashlight (`set_flashlight`) | ✓ | `CameraManager.setTorchMode` |
| Show alarms (`show_alarms`) | | `AlarmClock.ACTION_SHOW_ALARMS` |
| Volume (`set_volume`) | | `AudioManager.setStreamVolume` |
| Media controls (`control_media`) | | Media key events |
| Camera (`open_camera`) | | `MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA` |

Five tools are on by default because Needle 3 considers every tool when there are five or fewer; with more, it considers the five that best match each request.

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

Needle 3's choices and confidence depend heavily on the tool descriptions. `mise run tool-check [all|default]` runs Cactus's Linux build of the same engine on the desktop, with the app's own tool JSON (exported by a unit test), against `tool-check/requests.txt`: requests whose right tool is known. On 2026-10-07, with the default tools: 25 of 29 right, 19 sure enough to run without asking. Lessons from it are in the comments in `Tools.kt`:
- one total in seconds beats separate hours, minutes and seconds fields, which turned "10 minutes" into 10 h 10 min;
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
mise run send-over-tailscale poco-f5-1   # or send the APK with Taildrop
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
