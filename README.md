# Ferb

*A private assistant for Android that does things, on the phone.*

Type a request, such as "set a timer for 10 minutes" or "turn on the flashlight", and Ferb does it: it sets the timer, switches on the torch, opens the app or settings page. A small tool-calling model, [Needle 3](https://huggingface.co/Cactus-Compute/needle3) (35 MB), runs on the phone inside the app. Or tap the mic and say it: on a Pixel 10 or 11, Gemini Nano hears English and Hindi on the phone (through Android's AICore) and rewrites what you say as one plain English command for Needle 3; elsewhere, [Whistle](https://huggingface.co/Cactus-Compute/whistle) (17 MB) transcribes English on the phone.

**Private by design:** Ferb has no internet permission, so nothing you type leaves your phone. The one exception is opt-in: Ferb Link, below, which takes the steps you choose to a computer of your own.

Ferb doesn't chat or answer questions; it runs tools on your phone, and says so when none fits.

## Get started

### Install

Grab the latest APK from [Releases](https://github.com/kshatriya-abhay/ferb/releases) and open it on your phone (Android asks you to allow installing from your browser or files app). Or [build it from source](#build-from-source).

You need an Android phone with a 64-bit ARM processor (most phones since 2017) and Android 13 or later.

### First launch

A short welcome says what Ferb does and that it runs on the phone; tap **Start**. Three pages follow, one for each step of how Ferb understands you (how it hears you, whether it rewrites your request first, and whether Jev picks the tool), each with the recommended choice already picked and anything your phone can't use yet greyed out; then one on making Ferb your phone's assistant. **Skip** goes straight to the chat, and **Settings → Run setup again** brings the pages back. Then "How can I help?" shows a few requests to try; tap one, or type your own in the box at the bottom. Try:

- "Set a timer for 10 minutes"
- "Turn on the flashlight"
- "Wake me up at 6:45"
- "Open the calculator"
- "Open Wi-Fi settings"

Each reply shows what Ferb did, with buttons such as **Turn off**, **Undo** or **Open again**. When Ferb isn't sure enough, or the tool is one that reaches other people, it asks first: **Shall I do this?** Tap a reply to see the details (how sure Needle 3 was, and why it asked or ran). Tips explain each of these once, the first time it appears; **Settings → Show tips again** brings them back, with the setup pages.

### Turn on more tools

Five tools are on at first: **Alarms, Timers, Open apps, Settings pages** and **Flashlight**. The others are off until you turn them on in **Settings → Tools**; Ferb asks for an Android permission when a tool needs one (contacts for Calls and Messages, the calendar for Agenda and Calendar), and uses it on the phone only. Each tool's page also has its keywords, whether it always asks first, and how sure Needle 3 must be before it runs without asking.

### Choose how Ferb understands you

**Settings → How Ferb understands you** has the steps before Needle 3 picks the action: **Audio processing** (Off, Whistle, Gemini Nano, or Ferb Link), **Prompt rewrite** (Off, Gemini Nano, or Ferb Link: "Helps with other languages and improves accuracy") and **Tool selection** (Off, or Jev through Ferb Link: "Improves accuracy by narrowing the tool choice"). Ferb picks Gemini Nano for the first two where your phone has it. If a step fails for a request, Ferb goes on without it and says so on the reply. Gemini Nano's page (its settings button) shows its download progress and the language it expects.

### Make Ferb your phone's assistant

In Android's **Settings → Apps → Default apps → Digital assistant app**, pick **Ferb**. Then holding the power button, or swiping up from a bottom corner, opens Ferb's chat, ready to type. Ferb doesn't read the screen and asks for no extra permission for this.

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
| Notes | | "Note that the car is parked on level 3" (to your notes app, such as Google Keep, or, with Obsidian installed, "Markdown notes (powered by Obsidian)": Markdown files in a folder of your vault, opened in Obsidian) |
| Lists | | "Add milk and eggs to the shopping list" (a note with checkboxes in your notes folder) |
| Show lists | | "What's on my shopping list?" (tick items off in the chat) |
| Tick off | | "Tick milk off the shopping list" |
| Reminders | | "Remind me on Friday to pay the rent" (Ferb's own notification, with Done and Snooze; can repeat; listed, made by hand and cleared on the Notes screen's Reminders tab) |
| Directions | | "Show me the way to the Taj Mahal" (opens Google Maps) |
| Calls | | "Call Kritika" (always asks first) |
| Messages | | "Text Priya I'm running late" (always asks first; you press send) |
| Agenda | | "What's on tomorrow?" |
| Calendar | | "Add the dentist to my calendar tomorrow at 5 pm" (always asks first) |

How each one works, and when Ferb asks first: [docs/how-it-works.md](docs/how-it-works.md).

## Ferb Link (optional)

**[Ferb Link](https://github.com/kshatriya-abhay/ferb-link)** is a companion app for a bigger model on a computer of your own (one with a GPU, such as a gaming laptop, reached over Tailscale). It holds the internet access Ferb doesn't have. With it, any of the steps above can use your computer: Gemma 4 there can hear you and rewrite your requests (Hindi and Hinglish too), and Jev can pick the tool. Needle 3 on the phone still makes the call. Ferb Link answers only a Ferb signed with the same key, so get both the same way: both from Releases, or both built on the same computer. Set it up with [Ferb Link's quick start](https://github.com/kshatriya-abhay/ferb-link#quick-start).

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
| [Whistle](https://huggingface.co/Cactus-Compute/whistle) by Cactus Compute | Apache-2.0 | Speech to text on the phone (audio processing: Whistle, English), bundled |
| [Needle engine](https://github.com/cactus-compute/needle) by Cactus Compute | Apache-2.0 | Runs Needle 3 and Whistle (`libneedle.a`, linked prebuilt; Linux build for `tool-check`) |
| [ML Kit GenAI Speech Recognition](https://developers.google.com/ml-kit/genai/speech-recognition/android) by Google (`genai-speech-recognition`) | [ML Kit Terms](https://developers.google.com/ml-kit/terms) | Gemini Nano speech to text through AICore (audio processing: English and Hindi, on Pixel 10 and 11). Its network permissions are removed from Ferb |
| [ML Kit GenAI Prompt API](https://developers.google.com/ml-kit/genai/prompt/android) by Google (`genai-prompt`), with Gemini Nano | [ML Kit Terms](https://developers.google.com/ml-kit/terms) | The prompt rewrite with Gemini Nano, through AICore: each request as one English command for Needle 3, or none |
| Local Models Demo (the author's own app) | — | Whistle's transcribe bridge and PCM recorder, adapted |
| [AndroidX](https://developer.android.com/jetpack/androidx) (Compose, Material 3, Lifecycle) | Apache-2.0 | UI |
| [Material Components for Android](https://github.com/material-components/material-components-android) | Apache-2.0 | The Material 3 window theme behind Compose |
| [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache-2.0 | Background work |
| [Material Icons](https://github.com/google/material-design-icons) | Apache-2.0 | Icons |
| [JetBrains Markdown](https://github.com/JetBrains/markdown) | Apache-2.0 | Parses Markdown (for the renderer) |
| [Zig](https://ziglang.org) (via the `ziglang` Python package) | MIT | Builds the desktop `tool-check` against libc++ (not shipped) |
| [llama.cpp](https://github.com/ggml-org/llama.cpp) | MIT | Runs other models for `mise run bench` (not shipped) |
| [FunctionGemma 270M](https://huggingface.co/google/functiongemma-270m-it) by Google (GGUF by Unsloth) | Gemma terms | Compared in `mise run bench` (not shipped) |
| [Gemma 4 E2B](https://huggingface.co/google/gemma-4-E2B-it) by Google (GGUF by Unsloth) | Apache-2.0 | Compared in `mise run bench` (not shipped) |
| [LFM2.5-350M, LFM2.5-1.2B](https://huggingface.co/LiquidAI) by Liquid AI | LFM Open License | Compared in `mise run bench` (not shipped) |
| [Qwen3.5-0.8B, Qwen3.5-2B](https://huggingface.co/Qwen) by Alibaba (GGUF by Unsloth) | Apache-2.0 | Compared in `mise run bench` (not shipped) |
| [Hammer2.1-0.5b](https://huggingface.co/MadeAgents/Hammer2.1-0.5b) by MadeAgents (GGUF by mradermacher) | CC-BY-NC-4.0 | Compared in `mise run bench` (not shipped) |

## License

Apache-2.0: see [LICENSE](LICENSE). Needle 3 and the Needle engine, fetched at build time, are Apache-2.0 too.
