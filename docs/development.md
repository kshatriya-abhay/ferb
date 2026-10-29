# Developing Ferb

Building, testing and tuning. The [README](../README.md) has the first build; [how-it-works.md](how-it-works.md) has the design.

## Tasks

```sh
mise run test           # unit and contract tests (see Testing), about 11 s
mise run build          # the debug APK
mise run check-offline  # fails if the APK has the INTERNET permission or networking calls in native code
mise run device-test    # on the phone: loads the bundled model, swaps tools
mise run run            # install and launch on the connected phone
mise run send-over-tailscale my-phone   # or send the APK with Taildrop
mise run tool-check     # the tools against the real model, on the desktop (tuning only)
mise run bench          # other models on the same requests (tuning only)
```

Gradle runs with `--no-daemon` and compiles Kotlin in-process, so no daemon stays behind (a model server on the same computer, such as Ferb Link's, keeps its memory). Don't run `./gradlew --stop`: it stops every Gradle build on the computer.

## Releases

Releases are built and signed on the maintainer's computer with one release key, which Ferb Link's releases share: Ferb Link answers only a Ferb signed with the same key. Nothing secret is in the repo, Gradle, a file or a log.

```sh
mise run release           # build, sign, check: dist/ferb-<versionName>.apk and its .sha256
mise run release:publish   # the maintainer only: create the GitHub release v<versionName>
```

`mise run release` (`scripts/release.sh`):
1. Builds the release APK unsigned (Gradle never sees the key).
2. Aligns it, and signs it with `apksigner`, which reads the passwords from the environment, not its command line.
3. Checks the signing certificate against `release-cert.sha256`, the committed SHA-256 of the release certificate. It's public, and Ferb Link's repo holds the same line.
4. Runs `scripts/check-offline.sh` on the signed APK.
5. Writes the APK and its checksum to `dist/` (git-ignored).

On the very first release there's no `release-cert.sha256` yet: the task writes it, then stops so it can be checked and committed.

Signing settings, from the environment or asked for (the password without echo):

| Variable | |
|---|---|
| `FERB_RELEASE_KEYSTORE` | the keystore; default `~/.android/keystores/release-key-abhay.jks` |
| `FERB_RELEASE_KEY_ALIAS` | the key's alias |
| `FERB_RELEASE_STORE_PASSWORD` | the keystore's password |
| `FERB_RELEASE_KEY_PASSWORD` | the key's password, only when it isn't the keystore's |
| `FERB_RELEASE_CERT_FILE` | for tests with a throwaway key only: another file in place of `release-cert.sha256` |

`mise run release:publish` (`scripts/publish.sh`) runs only at a terminal, from a clean tree that matches `origin/main`. It checks the APK's certificate and checksum, and refuses a version that's already released. It asks for the version to be typed before `gh release create`. Raise `versionName` (and `versionCode`) in `app/build.gradle.kts` for each release.

Before publishing, install the release APK on a phone and run the smoke test below: the release build is shrunk by R8, which debug builds aren't.

## Testing

CI (`.github/workflows/ci.yml`) runs `mise run test` and `mise run check-offline` on every push to main and every pull request, with no secrets, signing or phone.

Four tiers, cheapest first:

1. **Unit tests** (`mise run test`, every change): the bulk, with no model, server or phone.
   - Routing and keywords; the time and length readers; Verdict and the bars; LinkPolicy.
   - App and contact matching; NeedleReply parsing; the tool schemas.
   - What a transcription leads to (`VoiceOutcome`).
   - `SpokenRequestsTest` keeps what the audio bench found, at the component that handles it.
   - The dates resolver (`ResolveTest`): every line of `tool-check/times.txt` (150 requests, fixed "now"s) resolves to its expected value from its gold structure; `TimeArgsTest` checks the override, the grounding and the asks.
   - Who answers and the path it records (`AnswerFlowTest`), with fakes for Ferb Link and Needle 3.
   - Device tests (`scripts/device-check.sh`, `QUIET=1` for a phone in use; take the lock, set `ANDROID_SERIAL`): the test phone is a Pixel 11 (Android 17, USB) from 2026-10-18, all four quiet checks passing. On it Needle 3 loads in 68 ms, sets up 5 tools in 0.44 s, and re-initialises with one tool and answers in 215–276 ms. A phone restored from a backup may get Ferb's permissions back on install (the Pixel did), so the first-run permission cards won't show there.
2. **Contract tests** (in `mise run test` too): Ferb's side of the link to Ferb Link, replayed from Ferb Link's example requests and results.
   - Status, check, complete, transcribe, wake, each error kind, and an older Ferb Link.
   - Ferb Link owns the fixtures ([contract/v1](https://github.com/kshatriya-abhay/ferb-link/tree/main/contract/v1)); Ferb keeps a copy in `app/src/test/resources/ferb-link-contract/v1` (with `SOURCE`, the commit it came from).
   - `mise run link:contract-sync` updates the copy, and `link:contract-check` says whether it's current. Both need `../ferb-link` (or `$FERB_LINK`); neither repo needs the other to build or test.
3. **Model evaluations**, only when tuning the tool config (schemas, descriptions, keywords, bars, the prompt), never per change:
   - `mise run tool-check` (Needle 3, `tool-check/requests.txt`) and `mise run bench` (local models, plus `suite.txt`).
   - `tool-check/laptop.py` runs Ferb's routing and reading with Ferb Link's own code against the laptop's model server. Take `/tmp/ferb-validate.lock` first.
     `laptop.py --decide` measures Jev (TypeSafe, through Ferb Link) as a second opinion where Needle 3 is unsure. Each call costs money: Ferb Link's harness keeps a ledger with a cap; use `--dry-run` first, and `--replies` to score a kept run again for free.
     `tool-check/jev.py --switch [--multi]` measures "Use Jev to select the tool" in every mode (Jev picks the tool, the answering model gets only it) against each mode without Jev. It keeps replies by request text and sends only the missing ones (same ledger, `--dry-run`, `--replies`).
   - **Hindi and Hinglish** (`# @hi`, at the end of `requests.txt` and `suite.txt`) are for the computer's model only: neither Needle 3 nor Jev understands Hindi. `tool-check`, the Needle 3 runs in `bench`, `laptop.py --decide` and `jev.py`'s Phone and Jev scores skip them (`tool-check/lines.py --skip hi`); `bench`'s other models and `laptop.py` keep them, scored apart.
   - Scored results go in `tool-check/results`.
4. **Smoke test**, by hand on the phone before an install-worthy release, not per change. First `mise run check-offline` and `mise run device-test`, then:
   1. Phone picked, type "Turn on the flashlight": the torch comes on (no source line).
   2. Computer picked, type "Set volume to 40": runs, with a laptop icon and its time.
   3. Computer picked, say "torch on karo": "Heard: …" under "Turn on the torch", and the torch comes on.
   4. Computer picked, record 3 s of silence and send: "No words heard", nothing runs.
   5. Switch off "Answer Ferb's requests" in Ferb Link, type "Set volume to 30": Needle 3 answers, with "On phone · turned off".
   6. Type "Text Priya I'm running late": "Shall I do this?" (messages always ask); Cancel.


## Tuning the tools: `mise run tool-check`

Needle 3's choices and confidence depend heavily on the tool descriptions. `mise run tool-check [all|default] [routed|fallback|unrouted]` runs Cactus's Linux build of the same engine on the desktop, with the app's own tool JSON, default keywords and bars (exported by a unit test), against `tool-check/requests.txt`: 238 requests with the right tool **and arguments**, including near misses that talk about a tool without asking for it (scored by `tool-check/score.py`, which reads times and durations the way the app does). `routed` (the default) routes each request as the app does (`tool-check/route.py`); `fallback` also checks every tool, five at a time, when the mentioned ones give no call (the removed setting); `unrouted` gives every tool to every request. The scorer ends with a table per tool: the confidence of its right calls, of its wrong calls that get past the checks, and the bar that would hold those back. With the default tools, routed (2026-10-10): 178 of 238 right, 69 of 92 calls run without asking, 2 wrong calls would (both open a harmless settings page). Lessons from it are in the comments in `Tools.kt` and `CalendarTools.kt`:
- a timer's length as text ("2 hours", parsed by the app) beats a number of seconds, which the model got confidently wrong ("2 hours" → 120 s at 0.82), and separate fields, which turned "10 minutes" into 10 h 10 min;
- an alarm time as text ("8:00 AM", parsed by the app) beats hour and minute fields, which turned "alarms at 8 and 9 am" into one alarm at 08:09;
- "wi-fi" rather than "wifi" in the enum;
- a photo/video choice was picked backwards about half the time, so the camera tool has none;
- Needle 3 doesn't know today's date and makes dates up ("Friday at 8 pm" → a Thursday), so the calendar tools read days and times from the request themselves.

## Comparing models

`mise run bench [model|all] [all|default] [routed|unrouted]` runs models on `tool-check/requests.txt` plus `tool-check/suite.txt`: 201 more requests in categories (plain, casual, Indian English and Hinglish, typos, numbers in words, two actions at once, context, near misses, unrelated), which the bars aren't tuned on. `needle3` runs as tool-check does; the models in `tool-check/models.txt` (pinned GGUF files) run on the desktop's CPU through llama.cpp's server (`tool-check/bench.py`), with the same tool JSON and routing and one short system prompt. The same scorer reports each category, and, since other models have no confidence score like Needle 3's, how many right and wrong calls would run at one bar for all tools, on three stand-ins: the probability of the weakest token a model writes, of its first token (whether to call a tool at all), and their mean. With `all` (the default) it runs every model and writes a table, `tool-check/results/summary-*.txt` (`tool-check/compare.py`). Answers are kept per model in `tool-check/results/*.jsonl`, by tool set and request, so only what's new runs: added requests, or the requests routed to a tool whose schema changed. Delete a model's file to run it afresh.

