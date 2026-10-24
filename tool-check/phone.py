"""The phone bench (text only): Needle 3 and Gemini Nano on the Pixel, on `mise run bench`'s
requests (requests.txt + suite.txt), routed and scored the same way, so their numbers line up
with the laptop's (summary-all-routed.txt).

    phone.py prep        write build/phone-bench/requests.jsonl: each request with its routed tools
    phone.py push        copy it into Ferb's files/bench/ (debug build; adb)
    phone.py needle      Needle 3's pass with no screen (PhoneBenchTest); take /tmp/ferb-validate.lock first
    phone.py pull        copy every model's answers back
    phone.py score MODEL score needle3, gemini-full or gemini-fast with score.py (and the @hi lines apart)
    phone.py laptop      the laptop's saved Needle 3 and Gemma 4 E2B answers on the same overlap, to compare

Routing is route.py's (each tool's cues; none mentioned: every tool). Needle 3 answers through the
app's engine; Gemini Nano gets Computer mode's schema call (timed, t4; see the app's debug
bench/SchemaCall.kt), and its "when" becomes the time arguments as schema.py reads them. Gemini
Nano has no confidence score: its calls count as 1.00, so only "right" compares with Needle 3's.
LENIENT=1 also reads the whole call objects out of answers that aren't valid JSON (score-MODEL-lenient.txt).
"""
import json, os, re, statistics, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
BUILD = os.path.join(REPO, "app", "build", "tool-check")
OUT = os.path.join(REPO, "build", "phone-bench")
PACKAGE = "dev.abhay.assistant"
LAPTOP = os.path.join(REPO, "build", "bench", "results", "{}-all-routed.answers")
sys.path.insert(0, HERE)
from lines import kept

def bench_files(skip):
    return list(kept([os.path.join(HERE, "requests.txt"), os.path.join(HERE, "suite.txt")], skip))

def requests_of(lines):
    return [l.split("|", 1)[1].strip() for l in lines if l.strip() and not l.startswith("#") and "|" in l]

def hi_lines():
    everything, without = bench_files(set()), set(bench_files({"hi"}))
    # The @hi sections' lines: those the --skip hi files leave out.
    return [l for l in everything if l not in without or l.startswith("#")]

def prep():
    tools = json.load(open(os.path.join(BUILD, "all.json")))
    cues = json.load(open(os.path.join(BUILD, "cues.json")))
    everything = [t["name"] for t in tools]
    def routed(request):
        mentioned = [t["name"] for t in tools if t["name"] not in cues or re.search(cues[t["name"]], request, re.IGNORECASE)]
        return mentioned or everything
    os.makedirs(OUT, exist_ok=True)
    main_set = requests_of(bench_files({"hi"}))
    hi_set = [r for r in requests_of(hi_lines()) if r not in set(main_set)]
    seen, rows = set(), []
    for prefix, requests in (("r", main_set), ("h", hi_set)):
        for n, r in enumerate(requests):
            if r in seen: continue
            seen.add(r)
            rows.append({"id": f"{prefix}{n}", "request": r, "tools": routed(r)})
    with open(os.path.join(OUT, "requests.jsonl"), "w") as f:
        for row in rows: f.write(json.dumps(row, ensure_ascii=False) + "\n")
    open(os.path.join(OUT, "requests-no-hi.txt"), "w").writelines(bench_files({"hi"}))
    open(os.path.join(OUT, "requests-hi.txt"), "w").writelines(hi_lines())
    # The laptop's 329 and 390 (summary-all-routed.txt) were on the 439 requests of 2026-10-11;
    # 421 are still in the files as they were, so all models are also scored on those.
    old = {l.split("\t", 1)[0] for l in open(LAPTOP.format("needle3")) if "\t" in l}
    overlap = [l for l in bench_files({"hi"}) if l.startswith("#") or ("|" in l and l.split("|", 1)[1].strip() in old)]
    open(os.path.join(OUT, "requests-overlap.txt"), "w").writelines(overlap)
    print(f"{len(rows)} requests ({sum(r['id'][0] == 'r' for r in rows)} without @hi, {sum(r['id'][0] == 'h' for r in rows)} @hi); "
          f"every tool for {sum(len(r['tools']) == len(everything) for r in rows)}")

def adb(*args, stdin=None, out=None):
    return subprocess.run(["adb", *args], stdin=stdin, stdout=out, check=True)

def push():
    with open(os.path.join(OUT, "requests.jsonl"), "rb") as f:
        adb("exec-in", "run-as", PACKAGE, "sh", "-c", "mkdir -p files/bench && cat > files/bench/requests.jsonl", stdin=f)
    print("pushed")

def pull():
    for model in ("needle3", "gemini-full", "gemini-fast"):
        path = os.path.join(OUT, f"{model}.jsonl")
        with open(path, "wb") as f:
            subprocess.run(["adb", "exec-out", "run-as", PACKAGE, "sh", "-c", f"cat files/bench/{model}.jsonl 2>/dev/null"], stdout=f, check=False)
        n = sum(1 for l in open(path) if l.strip())
        print(f"{model}: {n} answers")

def needle():
    subprocess.run(["adb", "shell", "am", "instrument", "-w", "-e", "class", f"{PACKAGE}.PhoneBenchTest",
                    f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"], check=True)

# --- Gemini Nano's answer, read as Ferb Link's SchemaCall.reply reads it, then as schema.py scores it. ---
from schema import alarm_time, seconds

LEAKED = re.compile(r"\}\}|```")
MALFORMED = []

def complete_calls(raw):
    """LENIENT=1: the whole call objects in broken JSON, in order (Gemini Nano often writes one,
    then runs on repeating it until max tokens): what an enforced schema would likely have kept."""
    decoder, calls, i = json.JSONDecoder(), [], 0
    while (i := raw.find("{", i)) >= 0:
        try:
            value, end = decoder.raw_decode(raw, i)
        except json.JSONDecodeError:
            i += 1
            continue
        if isinstance(value, dict) and "tool" in value:
            calls.append(value)
            i = end
        else:
            i += 1
    if calls: return calls
    # No whole call object (a brace left out: {"tool": "set_timer", "arguments": {...}]}): each
    # "tool": name with the arguments object after it, and a "when" object if one follows.
    for m in re.finditer(r'"tool"\s*:\s*"(\w+)"\s*,\s*"arguments"\s*:\s*', raw):
        try:
            args, end = decoder.raw_decode(raw, m.end())
        except json.JSONDecodeError:
            continue
        when = None
        w = re.match(r'\s*,\s*"when"\s*:\s*', raw[end:])
        if w:
            try:
                when, _ = decoder.raw_decode(raw, end + w.end())
            except json.JSONDecodeError:
                pass
        calls.append({"tool": m[1], "arguments": args, "when": when})
    return calls

def gemini_calls(raw):
    """[{name, arguments}] from the schema call's JSON, or None if it doesn't parse."""
    start, end = raw.find("{"), raw.rfind("}")
    if start < 0 or end <= start: return None
    try:
        answer = json.loads(raw[start:end + 1])
    except json.JSONDecodeError:
        if not os.environ.get("LENIENT"): return None
        answer = {"calls": complete_calls(raw)}
    calls = []
    for call in answer.get("calls") or []:
        if not isinstance(call, dict) or not call.get("tool"): continue
        # Gemini Nano sometimes nests the call in "tool" ({"tool": {"name", "arguments"}}): the API
        # can't enforce the schema as llama.cpp did. Read leniently, and counted (MALFORMED).
        if isinstance(call["tool"], dict):
            MALFORMED.append(call)
            call = {"tool": call["tool"].get("name"), "arguments": call["tool"].get("arguments"), "when": call.get("when")}
            if not isinstance(call["tool"], str): continue
        if not isinstance(call["tool"], str): continue
        args = {k: (v[:LEAKED.search(v).start()].rstrip() if isinstance(v, str) and LEAKED.search(v) else v)
                for k, v in (call.get("arguments") if isinstance(call.get("arguments"), dict) else {}).items()}
        w = call.get("when") if isinstance(call.get("when"), dict) else None
        if w and call["tool"] == "set_alarm" and alarm_time(w): args["time"] = alarm_time(w)
        if w and call["tool"] == "set_timer" and seconds(w): args["duration"] = f"{seconds(w)} seconds"
        calls.append({"name": call["tool"], "arguments": args})
    return calls

def answers(model):
    rows = [json.loads(l) for l in open(os.path.join(OUT, f"{model}.jsonl")) if l.strip()]
    out, unread, errors = {}, [], []
    for r in rows:
        if "error" in r:
            errors.append(r)
            out[r["request"]] = json.dumps({"function_calls": [], "confidence": 0.0, "ms": r.get("ms")})
        elif model == "needle3":
            reply = json.loads(r["raw"])
            reply["ms"] = r.get("ms")
            out[r["request"]] = json.dumps(reply)
        else:
            calls = gemini_calls(r["raw"])
            if calls is None: unread.append(r)
            out[r["request"]] = json.dumps({"function_calls": calls or [], "confidence": 1.0, "ms": r.get("ms")})
    return rows, out, unread, errors

def score(model):
    rows, out, unread, errors = answers(model)
    tools = os.path.join(BUILD, "all.json")
    report = []
    for name, path in (("without @hi", "requests-no-hi.txt"), ("the 2026-10-11 overlap", "requests-overlap.txt"), ("@hi", "requests-hi.txt")):
        wanted = requests_of(open(os.path.join(OUT, path)))
        missing = [r for r in wanted if r not in out]
        if missing:
            report.append(f"== {name}: {len(missing)} of {len(wanted)} not answered yet; not scored")
            continue
        text = "".join(f"{r}\t{out[r]}\n" for r in wanted)
        scored = subprocess.run([sys.executable, os.path.join(HERE, "score.py"), tools, os.path.join(OUT, path),
                                 os.path.join(BUILD, "bars.json"), os.path.join(BUILD, "cues.json")],
                                input=text, capture_output=True, text=True, check=True).stdout
        report.append(f"== {model}, {name}\n{scored}")
        # Right per expected tool, from score.py's lines ("OK  0.93  set_alarm  request  calls").
        per = {}
        for line in scored.splitlines():
            m = re.match(r"(OK  |ok\? |ARGS|MISS) [\d.]+  (\S+)", line)
            if m:
                t = per.setdefault(m[2], [0, 0])
                t[0] += m[1] in ("OK  ", "ok? "); t[1] += 1
        report.append("right by expected tool: " + ", ".join(f"{t} {a}/{b}" for t, (a, b) in sorted(per.items())))
    ms = sorted(r["ms"] for r in rows if "error" not in r and r.get("ms") is not None)
    if ms:
        report.append(f"latency on the phone, ms: median {statistics.median(ms):.0f}, 90th percentile {ms[int(len(ms) * 0.9)]}, n={len(ms)}")
    if errors:
        report.append(f"errors: {len(errors)}, e.g. " + "; ".join(sorted({e['error'][:80] for e in errors})[:3]))
    if unread:
        report.append(f"answers that didn't parse as JSON (scored as no call): {len(unread)}, e.g. {unread[0]['raw'][:160]!r}")
    if model != "needle3":
        report.append(f"calls nested in \"tool\" (read leniently): {len(MALFORMED)}")
        shown = [r for r in rows if "schema" in r]
        report.append(f"schema in the prompt: {sum(r['schema'] for r in shown)} of {len(shown)} (left out where it made the request too long)")
    text = "\n".join(report)
    path = os.path.join(OUT, f"score-{model}{'-lenient' if os.environ.get('LENIENT') else ''}.txt")
    open(path, "w").write(text + "\n")
    print(text)
    print(f"\n(saved in {os.path.relpath(path, REPO)})")

def laptop():
    """The laptop's saved answers (Needle 3, Gemma 4 E2B; 2026-10-11) scored on the overlap, today's labels."""
    for model in ("needle3", "gemma-4-e2b"):
        out = dict(l.rstrip("\n").split("\t", 1) for l in open(LAPTOP.format(model)) if "\t" in l)
        wanted = requests_of(open(os.path.join(OUT, "requests-overlap.txt")))
        text = "".join(f"{r}\t{out[r]}\n" for r in wanted)
        scored = subprocess.run([sys.executable, os.path.join(HERE, "score.py"), os.path.join(BUILD, "all.json"), os.path.join(OUT, "requests-overlap.txt"),
                                 os.path.join(BUILD, "bars.json"), os.path.join(BUILD, "cues.json")], input=text, capture_output=True, text=True, check=True).stdout
        open(os.path.join(OUT, f"score-laptop-{model}.txt"), "w").write(scored)
        print(model, "(laptop):", next(l for l in scored.splitlines() if "right (tool" in l))

if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else ""
    if command == "prep": prep()
    elif command == "push": push()
    elif command == "pull": pull()
    elif command == "needle": needle()
    elif command == "score": score(sys.argv[2])
    elif command == "laptop": laptop()
    else: sys.exit(__doc__)
