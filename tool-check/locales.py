"""The locale probe (2026-10-30): Gemini Nano's speech recognition under several locales, on the
voice bench's recordings (build/voice, from `phone.py voice-pull`) and TTS clips the phone makes in
English, Hindi, Japanese, French and Tamil. Runs as its own app (:localeprobe), beside Ferb, so
Ferb is never touched. Phone steps need the phone lock: `mkdir /tmp/ferb-validate.lock` first.

    locales.py prep [--all]   pick the clips (all Hindi, 12 English, 12 Hinglish; --all for every one)
                              into build/locale-probe/clips.jsonl
    locales.py build          build the probe app and its test APK (gradle, no phone)
    locales.py install        install both probe APKs (adb install -r; not Ferb)        [phone]
    locales.py push           copy the picked clips into the probe's files               [phone]
    locales.py run [LOCALES] [MODES]  the probe, the phone unlocked and left alone (about 10 minutes):
                              LOCALES default en-US,en-IN,hi-IN,ja-JP,fr-FR; MODES advanced or advanced,basic  [phone]
    locales.py pull           status.jsonl and results.jsonl into build/locale-probe/   [phone]
    locales.py sysinfo        the phone's language settings, read-only (getprop, settings get)  [phone]
    locales.py report         build/locale-probe/report.md: the script of each output per locale and language,
                              each clip side by side, and the status per locale

Nothing here downloads a model: a locale AICore says needs a download is noted and skipped.
"""
import collections, json, os, re, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
VOICE = os.path.join(REPO, "build", "voice")
OUT = os.path.join(REPO, "build", "locale-probe")
PROBE = "dev.abhay.localeprobe"
SERIAL = os.environ.get("ANDROID_SERIAL", "67291FDKY0010G")
LOCK = "/tmp/ferb-validate.lock"
APK = os.path.join(REPO, "localeprobe", "build", "outputs", "apk", "debug", "localeprobe-debug.apk")
TEST_APK = os.path.join(REPO, "localeprobe", "build", "outputs", "apk", "androidTest", "debug", "localeprobe-debug-androidTest.apk")


def adb(*args, **kw):
    sdk = os.path.expanduser(os.environ.get("ANDROID_HOME", "~/Android/Sdk"))
    return subprocess.run([os.path.join(sdk, "platform-tools", "adb"), "-s", SERIAL, *args], **kw)


def phone():
    if not os.path.isdir(LOCK):
        sys.exit(f"Take the phone lock first (mkdir {LOCK}), and only in a window the user gave.")


def lang_of(card):
    """The language a bench card asked for: English, Hindi or Hinglish; "other" for the pause cards."""
    m = re.search(r"\bin (English|Hindi|Hinglish)\b", card)
    if m:
        return m.group(1)
    if "pure Hindi" in card:
        return "Hindi"
    if "mixing two languages" in card:
        return "Hinglish"
    return "other"


def prep(everything=False):
    entries = [json.loads(l) for l in open(os.path.join(VOICE, "entries.jsonl")) if l.strip()]
    entries = [e for e in entries if os.path.exists(os.path.join(VOICE, "clips", e["id"] + ".wav"))]
    by = collections.defaultdict(list)
    for e in sorted(entries, key=lambda e: e["id"]):
        by[lang_of(e["card"])].append(e)
    picked = []
    for lang, es in by.items():
        if everything or lang == "Hindi":
            picked += es
        elif lang in ("English", "Hinglish"):
            step = max(1, len(es) / 12)
            picked += [es[int(i * step)] for i in range(min(12, len(es)))]
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "clips.jsonl"), "w") as f:
        for e in picked:
            f.write(json.dumps({"id": e["id"], "lang": lang_of(e["card"]), "card": e["card"], "en_us_then": e.get("heard", "")}, ensure_ascii=False) + "\n")
    print(f"{len(picked)} clips: " + ", ".join(f"{k} {v}" for k, v in collections.Counter(lang_of(e['card']) for e in picked).items()))


def build():
    subprocess.run(["./gradlew", "--no-daemon", "-q", ":localeprobe:assembleDebug", ":localeprobe:assembleDebugAndroidTest"], cwd=REPO, check=True)


def install():
    phone()
    for apk in (APK, TEST_APK):
        adb("install", "-r", apk, check=True)


def push():
    phone()
    clips = os.path.join(OUT, "clips.jsonl")
    adb("exec-in", "run-as", PROBE, "sh", "-c", "mkdir -p files/clips && cat > files/clips.jsonl", input=open(clips, "rb").read(), check=True)
    for l in open(clips):
        i = json.loads(l)["id"]
        adb("exec-in", "run-as", PROBE, "sh", "-c", f"cat > files/clips/{i}.wav", input=open(os.path.join(VOICE, "clips", i + ".wav"), "rb").read(), check=True)
    print("pushed")


def run(locales=None, modes=None):
    phone()
    extra = []
    if locales:
        extra += ["-e", "locales", locales]
    if modes:
        extra += ["-e", "modes", modes]
    adb("shell", "am", "instrument", "-w", *extra, "-e", "class", f"{PROBE}.LocaleProbeTest", f"{PROBE}.test/androidx.test.runner.AndroidJUnitRunner", check=True)


def pull():
    phone()
    for name in ("status.jsonl", "results.jsonl"):
        with open(os.path.join(OUT, name), "wb") as f:
            adb("exec-out", "run-as", PROBE, "cat", f"files/{name}", stdout=f, check=False)
    print("pulled into", OUT)


def sysinfo():
    """Read-only: what the phone's language and region are set to."""
    phone()
    found = {}
    for prop in ("persist.sys.locale", "ro.product.locale", "persist.sys.timezone", "ro.boot.hardware.sku", "gsm.sim.operator.iso-country"):
        found[prop] = adb("shell", "getprop", prop, capture_output=True, text=True).stdout.strip()
    for ns, key in (("system", "system_locales"), ("secure", "selected_input_method_subtype"), ("secure", "voice_recognition_service"),
                    ("secure", "assistant")):
        found[f"settings {ns} {key}"] = adb("shell", "settings", "get", ns, key, capture_output=True, text=True).stdout.strip()
    os.makedirs(OUT, exist_ok=True)
    json.dump(found, open(os.path.join(OUT, "sysinfo.json"), "w"), indent=1, ensure_ascii=False)
    for k, v in found.items():
        print(f"{k}: {v}")


SCRIPTS = [("Devanagari", r"[ऀ-ॿ]"), ("Japanese", r"[぀-ヿ一-鿿]"), ("Tamil", r"[஀-௿]"),
           ("Latin", r"[A-Za-zÀ-ÿ]")]


def script(text):
    """The writing systems in text, most letters first: "Latin", "Devanagari+Latin", or "none"."""
    counts = {name: len(re.findall(rx, text)) for name, rx in SCRIPTS}
    used = [n for n, c in sorted(counts.items(), key=lambda kv: -kv[1]) if c]
    return "+".join(used) or "none"


def report():
    results = [json.loads(l) for l in open(os.path.join(OUT, "results.jsonl")) if l.strip()]
    status = [json.loads(l) for l in open(os.path.join(OUT, "status.jsonl")) if l.strip()]
    sysinfo_path = os.path.join(OUT, "sysinfo.json")
    lines = ["# Locale probe", ""]
    lines += ["## AICore status and the phone's locales", ""] + [f"- `{json.dumps(s, ensure_ascii=False)}`" for s in status]
    if os.path.exists(sysinfo_path):
        lines += [""] + [f"- {k}: `{v}`" for k, v in json.load(open(sysinfo_path)).items()]
    locales = list(dict.fromkeys(f"{r['locale']} {r['mode']}" for r in results))
    langs = list(dict.fromkeys(r["lang"] for r in results))
    lines += ["", "## Script of the output, per language spoken and locale", "",
              "| spoken | " + " | ".join(locales) + " |", "|---|" + "---|" * len(locales)]
    for lang in langs:
        row = []
        for loc in locales:
            c = collections.Counter(script(r["heard"]) if r.get("heard") else ("error" if r.get("error") else "nothing")
                                    for r in results if r["lang"] == lang and f"{r['locale']} {r['mode']}" == loc)
            row.append(", ".join(f"{k} {v}" for k, v in c.most_common()))
        lines.append(f"| {lang} | " + " | ".join(row) + " |")
    lines += ["", "## Each clip, side by side", ""]
    by_clip = collections.OrderedDict()
    for r in results:
        by_clip.setdefault(r["id"], []).append(r)
    for i, rs in by_clip.items():
        first = rs[0]
        lines.append(f"### {first['lang']}: {first.get('card') or first['id']}")
        if first.get("text"):
            lines.append(f"Said: {first['text']}")
        lines.append("")
        for r in rs:
            what = r.get("heard") or (f"error: {r['error']}" if r.get("error") else "(nothing)")
            lines.append(f"- {r['locale']} {r['mode']} ({r.get('ms', '?')} ms): {what}")
        lines.append("")
    path = os.path.join(OUT, "report.md")
    open(path, "w").write("\n".join(lines))
    print("\n".join(lines[: lines.index("## Each clip, side by side")]))
    print("written:", path)


if __name__ == "__main__":
    cmd, rest = (sys.argv[1] if len(sys.argv) > 1 else "help"), sys.argv[2:]
    if cmd == "prep":
        prep("--all" in rest)
    elif cmd == "run":
        run(*(rest + [None, None])[:2])
    elif cmd in ("build", "install", "push", "pull", "sysinfo", "report"):
        globals()[cmd]()
    else:
        print(__doc__)
