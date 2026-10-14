# Ferb

*A private assistant for Android that does things, on the phone.*

Type a request, such as "set a timer for 10 minutes" or "turn on the flashlight", and Ferb does it: it sets the timer, switches on the torch, opens the app or settings page. A small tool-calling model, [Needle 3](https://huggingface.co/Cactus-Compute/needle3) (35 MB), runs on the phone inside the app.

**Private by design:** Ferb has no internet permission, so nothing you type leaves your phone. The one exception is opt-in: Ferb Link, below, which sends requests to a computer of your own.

Ferb doesn't chat or answer questions; it runs tools on your phone, and says so when none fits.

## Get started

### Install

There are no ready-made downloads yet (see *Download*, below). For now, build Ferb from source and install it on your phone over USB: [Build from source](#build-from-source) takes a few commands.

You need an Android phone with a 64-bit ARM processor (most phones since 2017) and Android 13 or later.

### Download

*Coming: signed APKs on GitHub Releases. Until then, build from source.*

### First launch

Ferb opens on "How can I help?" with a few requests to try; tap one, or type your own in the box at the bottom. Try:

- "Set a timer for 10 minutes"
- "Turn on the flashlight"
- "Wake me up at 6:45"
- "Open the calculator"
- "Open Wi-Fi settings"

Each reply shows what Ferb did, with buttons such as **Turn off**, **Undo** or **Open again**. When Ferb isn't sure enough, or the tool is one that reaches other people, it asks first: **Shall I do this?** Tap a reply to see the details (how sure Needle 3 was, and why it asked or ran).

### Turn on more tools

Five tools are on at first: **Alarms, Timers, Open apps, Settings pages** and **Flashlight**. The others are off until you turn them on in **Settings → Tools**; Ferb asks for an Android permission when a tool needs one (contacts for Calls and Messages, the calendar for Agenda and Calendar), and uses it on the phone only. Each tool's page also has its keywords, whether it always asks first, and how sure Needle 3 must be before it runs without asking.

## What it can do

| Tool | On at first | Try |
|---|---|---|
| Alarms | ✓ | "Wake me up at 6:45" |
| Timers | ✓ | "Timer for 10 minutes" |
| Open apps | ✓ | "Open the calculator" |
| Settings pages | ✓ | "Open Wi-Fi settings" |
| Flashlight | ✓ | "Turn on the flashlight" |
| Show alarms | | "Show my alarms" |
| Media volume | | "Set volume to 40" |
| Media controls | | "Pause the music" |
| Camera | | "Take a picture" |
| Notes | | "Note that the car is parked on level 3" |
| Directions | | "Show me the way to the Taj Mahal" (opens Google Maps) |
| Calls | | "Call Kritika" (always asks first) |
| Messages | | "Text Priya I'm running late" (always asks first; you press send) |
| Agenda | | "What's on tomorrow?" |
| Calendar | | "Add the dentist to my calendar tomorrow at 5 pm" (always asks first) |

How each one works, and when Ferb asks first: [docs/how-it-works.md](docs/how-it-works.md).

## Ferb Link (optional)

**Ferb Link** is a companion app for a bigger model on a computer of your own (one with a GPU, such as a gaming laptop, reached over Tailscale). It holds the internet access Ferb doesn't have. With it, you can pick **Computer** under Ferb's chat box, and Ferb understands more phrasings and Hinglish, and gains voice input: tap the mic, speak, and the computer transcribes. Ferb Link answers only a Ferb signed with the same key, so build or install both the same way. It's in its own repository, not yet published.

## Build from source

Needs Linux or macOS, and:

- [mise](https://mise.jdx.dev), which installs the right Java and runs the build tasks;
- the Android SDK (from [Android Studio](https://developer.android.com/studio), or the command-line tools) with these packages:
  ```sh
  sdkmanager "platforms;android-37.2" "build-tools;37.0.0" "ndk;28.2.13676358" "cmake;3.22.1" "platform-tools"
  ```
  The SDK is looked for in `~/Android/Sdk` (Android Studio's place on Linux); set `ANDROID_HOME` if yours is elsewhere (on macOS, usually `~/Library/Android/sdk`);
- your phone with [USB debugging](https://developer.android.com/studio/debug/dev-options) on, connected (`adb devices` lists it).

```sh
git clone https://github.com/kshatriya-abhay/ferb.git
cd ferb
mise trust && mise install   # Java 21
mise run bootstrap           # writes local.properties (the SDK's path)
mise run fetch               # the Needle engine and the Needle 3 model (pinned, checksummed)
mise run test                # the unit tests, about 10 s
mise run run                 # builds, installs and opens Ferb on the phone
```

The app is signed with your computer's debug key (`~/.android/debug.keystore`, created on the first build). Build Ferb Link on the same computer, and the two recognise each other.

Working on Ferb itself (tests, tuning the tools, comparing models): [docs/development.md](docs/development.md).

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
| [llama.cpp](https://github.com/ggml-org/llama.cpp) | MIT | Runs other models for `mise run bench` (not shipped) |
| [FunctionGemma 270M](https://huggingface.co/google/functiongemma-270m-it) by Google (GGUF by Unsloth) | Gemma terms | Compared in `mise run bench` (not shipped) |
| [Gemma 4 E2B](https://huggingface.co/google/gemma-4-E2B-it) by Google (GGUF by Unsloth) | Apache-2.0 | Compared in `mise run bench` (not shipped) |
| [LFM2.5-350M, LFM2.5-1.2B](https://huggingface.co/LiquidAI) by Liquid AI | LFM Open License | Compared in `mise run bench` (not shipped) |
| [Qwen3.5-0.8B, Qwen3.5-2B](https://huggingface.co/Qwen) by Alibaba (GGUF by Unsloth) | Apache-2.0 | Compared in `mise run bench` (not shipped) |
| [Hammer2.1-0.5b](https://huggingface.co/MadeAgents/Hammer2.1-0.5b) by MadeAgents (GGUF by mradermacher) | CC-BY-NC-4.0 | Compared in `mise run bench` (not shipped) |

## License

Apache-2.0: see [LICENSE](LICENSE). Needle 3 and the Needle engine, fetched at build time, are Apache-2.0 too.
