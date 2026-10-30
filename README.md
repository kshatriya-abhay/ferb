# Ferb

*A private assistant for Android that does things, on the phone.*

Type a request, such as "set a timer for 10 minutes" or "turn on the flashlight", and Ferb does it: it sets the timer, switches on the torch, opens the app or settings page. A small tool-calling model, [Needle 3](https://huggingface.co/Cactus-Compute/needle3) (35 MB), runs on the phone inside the app. Or tap the mic and say it: on a Pixel 10 or 11, Gemini Nano hears English and Hindi on the phone (through Android's AICore) and rewrites what you say as one plain English command for Needle 3; elsewhere, [Whistle](https://huggingface.co/Cactus-Compute/whistle) (17 MB) transcribes English on the phone.

**Private by design:** Ferb has no internet permission, so nothing you type leaves your phone. The one exception is opt-in: Ferb Link, below, which takes the steps you choose to a computer of your own.

Ferb doesn't chat or answer questions; it runs tools on your phone, and says so when none fits.

## Get started

### Install

You need an Android phone with a 64-bit ARM processor (most phones since 2017) and Android 13 or later. Ferb isn't on the Play Store; install it from GitHub, with updates through Obtainium if you like.

**From GitHub:** download the latest `ferb-*.apk` from [Releases](https://github.com/kshatriya-abhay/ferb/releases/latest) on your phone and open it. Android asks once to allow installs from your browser or files app. To update, install the newer APK the same way: your settings and reminders stay.

**With [Obtainium](https://obtainium.imranr.dev)** (updates without checking GitHub yourself):

1. Install Obtainium, from its site or F-Droid.
2. In Obtainium, tap **Add app**, paste `https://github.com/kshatriya-abhay/ferb`, and tap **Add**.
3. Tap **Install**. Obtainium then checks Ferb's releases and offers each update; Android asks you to confirm it.

Using Ferb Link too? Add `https://github.com/kshatriya-abhay/ferb-link` the same way. Get both apps from the same place: Ferb Link answers only a Ferb signed with the same key, so releases go with releases and your own builds with your own builds. Android won't update a release with your own build either (the keys differ); switching means uninstalling, which removes Ferb's settings and reminders.

Or [build it from source](#build-from-source).

### First launch

Four pages: a short welcome; **Choose your preferred model** (Needle + Whistle, Gemini Nano, or Gemma 4 via Ferb Link, with Gemini Nano picked where it can run, and its download right there when it's still needed: Ferb works meanwhile and switches to it once it's ready); **Choose Ferb's tools**, whose button opens Settings → Tools, where each switch turns a tool on or off (Back returns to setup); and making Ferb your phone's assistant (left out if it already is). Every tool that needs nothing set up is on from the start; the ones that need a permission or a notes folder (calls, messages, the calendar, reminders and lists) start off. **Skip** goes straight to the chat, and **Settings → Run setup again** brings the pages back. In the chat, "How can I help?" shows a few requests to try; tap one, or type your own in the box at the bottom. Try:

- "Set a timer for 10 minutes"
- "Turn on the flashlight"
- "Wake me up at 6:45"
- "Open the calculator"
- "Open Wi-Fi settings"

Each reply shows what Ferb did, with buttons such as **Turn off**, **Undo** or **Open again**. When Ferb isn't sure enough, or the tool is one that reaches other people, it asks first: **Shall I do this?** Tap a reply to see the details (how sure Needle 3 was, and why it asked or ran). Tips explain each of these once, the first time it appears; **Settings → Show tips again** brings them back, with the setup pages.

### Turn on more tools

Five tools are on at first: **Alarms, Timers, Open apps, Settings pages** and **Flashlight**. The others are off until you turn them on in **Settings → Tools**; Ferb asks for an Android permission when a tool needs one (contacts for Calls and Messages, the calendar for Agenda and Calendar), and uses it on the phone only. Each tool's page also has its keywords, whether it always asks first, and how sure Needle 3 must be before it runs without asking.

### Choose your preferred model

**Settings → Choose your preferred model** has three choices. Each one hears you and tidies what you say; Needle 3 makes the tool call on the phone whichever you choose, and you can always type.

- **Needle + Whistle**: English-only, wide-compatibility. Whistle hears you on the phone; no rewrite.
- **Gemini Nano**: on-device, multilingual, compatibility varies (Pixel 10 and 11 among others). It hears you and rewrites the request, on the phone. Greyed out where Gemini Nano can't run; where it still needs a one-time download, the download is right there, and Ferb uses Whistle until it's ready.
- **Gemma 4 (via Ferb Link)**: run it on your own server, multilingual. Your computer hears you and rewrites the request, through Ferb Link. Greyed out until Ferb Link is installed; until it's paired, a line and a button open it.

If your computer can't be reached for a request, Ferb answers on the phone and says so on the reply. **Settings → Advanced** has the steps one by one: audio processing, prompt rewrite and tool selection (Jev, through Ferb Link). Options your phone can't ever use there are hidden, with a line saying why.

### Make Ferb your phone's assistant

In Android's **Settings → Apps → Default apps → Digital assistant app**, pick **Ferb**. Then holding the power button, or swiping up from a bottom corner, opens Ferb in a sheet over whatever app you're in, already listening (or ready to type, with audio processing off): say it, tap ✓, and the reply shows there. Drag it up, or tap it, for the full chat. Ferb doesn't read the screen and asks for no extra permission for this.

## What it can do

| Tool | On at first | Try |
|---|---|---|
| Alarms | ✓ | "Wake me up at 6:45" |
| Timers | ✓ | "Timer for 10 minutes" |
| Open apps | ✓ | "Open the calculator" |
| Settings pages | ✓ | "Open Wi-Fi settings" |
| Flashlight | ✓ | "Turn on the flashlight" |
| Show alarms |  ✓ | "Show my alarms" |
| Media volume |  ✓ | "Set volume to 40" |
| Media controls |  ✓ | "Pause the music" |
| Camera |  ✓ | "Take a picture" |
| Notes |  ✓ | "Note that the car is parked on level 3" (to your notes app, such as Google Keep, or, with Obsidian installed, "Markdown notes (powered by Obsidian)": Markdown files in a folder of your vault, opened in Obsidian) |
| Lists | | "Add milk and eggs to the shopping list" (a note with checkboxes in your notes folder) |
| Show lists | | "What's on my shopping list?" (tick items off in the chat) |
| Tick off | | "Tick milk off the shopping list" |
| Reminders | | "Remind me on Friday to pay the rent" (Ferb's own notification, with Done and Snooze; can repeat; listed, made by hand and cleared on the Notes screen's Reminders tab) |
| Directions |  ✓ | "Show me the way to the Taj Mahal" (opens Google Maps) |
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

## Privacy

Ferb has no internet access, analytics or accounts. What stays on the phone and what can leave it: [PRIVACY.md](PRIVACY.md), and Settings → Privacy in the app.

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
| [Zig](https://ziglang.org) (via the `ziglang` Python package) | MIT | Builds the desktop `tool-check` against libc++ (not shipped) |
| [llama.cpp](https://github.com/ggml-org/llama.cpp) | MIT | Runs other models for `mise run bench` (not shipped) |
| [FunctionGemma 270M](https://huggingface.co/google/functiongemma-270m-it) by Google (GGUF by Unsloth) | Gemma terms | Compared in `mise run bench` (not shipped) |
| [Gemma 4 E2B](https://huggingface.co/google/gemma-4-E2B-it) by Google (GGUF by Unsloth) | Apache-2.0 | Compared in `mise run bench` (not shipped) |
| [LFM2.5-350M, LFM2.5-1.2B](https://huggingface.co/LiquidAI) by Liquid AI | LFM Open License | Compared in `mise run bench` (not shipped) |
| [Qwen3.5-0.8B, Qwen3.5-2B](https://huggingface.co/Qwen) by Alibaba (GGUF by Unsloth) | Apache-2.0 | Compared in `mise run bench` (not shipped) |
| [Hammer2.1-0.5b](https://huggingface.co/MadeAgents/Hammer2.1-0.5b) by MadeAgents (GGUF by mradermacher) | CC-BY-NC-4.0 | Compared in `mise run bench` (not shipped) |

## License

Apache-2.0: see [LICENSE](LICENSE). Needle 3 and the Needle engine, fetched at build time, are Apache-2.0 too.
