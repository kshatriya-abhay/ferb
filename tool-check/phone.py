"""The phone bench (text only): Needle 3 and Gemini Nano on the Pixel, on `mise run bench`'s
requests (requests.txt + suite.txt), routed and scored the same way, so their numbers line up
with the laptop's (summary-all-routed.txt).

    phone.py prep        write build/phone-bench/requests.jsonl: each request with its routed tools
    phone.py push        copy it into Ferb's files/bench/ (debug build; adb)
    phone.py needle      Needle 3's pass with no screen (PhoneBenchTest); take /tmp/ferb-validate.lock first
    phone.py pull        copy every model's answers back
    phone.py score MODEL score needle3, gemini-full or gemini-fast with score.py (and the @hi lines apart)
    phone.py laptop      the laptop's saved Needle 3 and Gemma 4 E2B answers on the same overlap, to compare
    phone.py rewritten M Gemini Nano's rewrites (M: gemini-full or gemini-fast) routed and pushed for Needle 3 (after pull)
    phone.py needle M    Needle 3 on M's rewrites, with no screen (lock first)
    phone.py pipeline M  score rewrite -> Needle 3 against Needle 3 direct, with what the rewrite helped and hurt

Routing is route.py's (each tool's cues; none mentioned: every tool). Needle 3 answers through the
app's engine; Gemini Nano gets Computer mode's schema call (timed, t4; see the app's debug
bench/SchemaCall.kt), and its "when" becomes the time arguments as schema.py reads them. Gemini
Nano has no confidence score: its calls count as 1.00, so only "right" compares with Needle 3's.
Answers are read as Ferb reads them (engine/SchemaCallReply.kt): the whole answer, else its first
complete call, else a "tool" with its arguments; calls to tools not offered are dropped.
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

def routed(request):
    """route.py's routing: the tools whose cues the text has; every tool when it has none."""
    tools = json.load(open(os.path.join(BUILD, "all.json")))
    cues = json.load(open(os.path.join(BUILD, "cues.json")))
    mentioned = [t["name"] for t in tools if t["name"] not in cues or re.search(cues[t["name"]], request, re.IGNORECASE)]
    return mentioned or [t["name"] for t in tools]

def prep():
    everything = [t["name"] for t in json.load(open(os.path.join(BUILD, "all.json")))]
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

def push(name="requests.jsonl"):
    with open(os.path.join(OUT, name), "rb") as f:
        adb("exec-in", "run-as", PACKAGE, "sh", "-c", f"mkdir -p files/bench && cat > files/bench/{name}", stdin=f)
    print(f"pushed {name}")

def rewritten(model):
    """Gemini Nano's rewrites ([model]: gemini-full or gemini-fast) routed as route.py routes, for Needle 3; NONE left out (no call)."""
    rows = [json.loads(l) for l in open(os.path.join(OUT, f"rewrites-{model}.jsonl")) if l.strip()]
    out = [{"id": r["id"], "request": r["command"], "tools": routed(r["command"])} for r in rows if r.get("command")]
    name = f"requests-{model}.jsonl"
    with open(os.path.join(OUT, name), "w") as f:
        for row in out: f.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"{len(rows)} rewrites: {len(out)} commands for Needle 3, {sum(1 for r in rows if 'error' not in r and not r.get('command'))} NONE, {sum('error' in r for r in rows)} errors")
    push(name)

def pull():
    for model in ("needle3", "gemini-full", "gemini-fast", "rewrites-gemini-full", "rewrites-gemini-fast", "needle3-on-gemini-full", "needle3-on-gemini-fast"):
        path = os.path.join(OUT, f"{model}.jsonl")
        with open(path, "wb") as f:
            subprocess.run(["adb", "exec-out", "run-as", PACKAGE, "sh", "-c", f"cat files/bench/{model}.jsonl 2>/dev/null"], stdout=f, check=False)
        n = sum(1 for l in open(path) if l.strip())
        print(f"{model}: {n} answers")

def needle(on=None):
    """Needle 3's pass with no screen: on the bench's requests, or on [on]'s rewrites (needle3-on-[on].jsonl)."""
    extra = ["-e", "input", f"requests-{on}.jsonl", "-e", "output", f"needle3-on-{on}"] if on else []
    subprocess.run(["adb", "shell", "am", "instrument", "-w", "-e", "class", f"{PACKAGE}.PhoneBenchTest", *extra,
                    f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"], check=True)

# --- Gemini Nano's answer, read as Ferb reads it (the app's engine/SchemaCallReply.kt, the same
# rules; SchemaCallReplyTest checks the two agree on the bench's answers), then scored as schema.py
# scores schema calls: "when" becomes the time arguments. ---
from schema import alarm_time, seconds

TOOL_ARGUMENTS = re.compile(r'"tool"\s*:\s*"(\w+)"\s*,\s*"arguments"\s*:\s*')
WHEN_AFTER = re.compile(r'\s*,\s*"when"\s*:\s*')
DECODER = json.JSONDecoder()

def value_at(raw, i):
    while i < len(raw) and raw[i].isspace(): i += 1
    try:
        return DECODER.raw_decode(raw, i)
    except (json.JSONDecodeError, IndexError):
        return None, None

def call_of(v):
    if isinstance(v, dict) and isinstance(v.get("tool"), str) and isinstance(v.get("arguments"), dict):
        return {"tool": v["tool"], "arguments": v["arguments"], "when": v["when"] if isinstance(v.get("when"), dict) else None}
    return None

def parse(raw, offered):
    """(calls, how): how is Whole, FirstCall, ToolArguments or Unreadable, as SchemaCallReply.parse."""
    def only(calls, how): return [c for c in calls if c["tool"] in offered], how
    start = raw.find("{")
    if start < 0: return [], "Unreadable"
    answer, _ = value_at(raw, start)
    if isinstance(answer, dict) and isinstance(answer.get("calls"), list):
        return only([c for c in map(call_of, answer["calls"]) if c], "Whole")
    i = raw.find("{", start + 1)
    while i >= 0:
        c = call_of(value_at(raw, i)[0])
        if c: return only([c], "FirstCall")
        i = raw.find("{", i + 1)
    for m in TOOL_ARGUMENTS.finditer(raw):
        args, end = value_at(raw, m.end())
        if not isinstance(args, dict): continue
        w = WHEN_AFTER.match(raw, end)
        when = value_at(raw, w.end())[0] if w else None
        return only([{"tool": m[1], "arguments": args, "when": when if isinstance(when, dict) else None}], "ToolArguments")
    return [], "Unreadable"

def scored_calls(calls):
    """[{name, arguments}] for score.py, with "when" as the time arguments (schema.py's rules)."""
    out = []
    for call in calls:
        args, w = dict(call["arguments"]), call["when"]
        if w and call["tool"] == "set_alarm" and alarm_time(w): args["time"] = alarm_time(w)
        if w and call["tool"] == "set_timer" and seconds(w): args["duration"] = f"{seconds(w)} seconds"
        out.append({"name": call["tool"], "arguments": args})
    return out

def answers(model):
    rows = [json.loads(l) for l in open(os.path.join(OUT, f"{model}.jsonl")) if l.strip()]
    offered = {r["id"]: set(r["tools"]) for r in (json.loads(l) for l in open(os.path.join(OUT, "requests.jsonl")))}
    out, hows, errors = {}, {}, []
    for r in rows:
        if "error" in r:
            errors.append(r)
            out[r["request"]] = json.dumps({"function_calls": [], "confidence": 0.0, "ms": r.get("ms")})
        elif model == "needle3":
            reply = json.loads(r["raw"])
            reply["ms"] = r.get("ms")
            out[r["request"]] = json.dumps(reply)
        else:
            calls, how = parse(r["raw"], offered[r["id"]])
            hows.setdefault(how, []).append(r)
            out[r["request"]] = json.dumps({"function_calls": scored_calls(calls), "confidence": 1.0, "ms": r.get("ms")})
    return rows, out, hows, errors

def score(model):
    rows, out, hows, errors = answers(model)
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
    if hows:
        report.append("answers read as: " + ", ".join(f"{h} {len(v)}" for h, v in sorted(hows.items())) +
                      (f"; unreadable (no call), e.g. {hows['Unreadable'][0]['raw'][:160]!r}" if hows.get("Unreadable") else ""))
    if model != "needle3":
        shown = [r for r in rows if "schema" in r]
        report.append(f"schema in the prompt: {sum(r['schema'] for r in shown)} of {len(shown)} (left out where it made the request too long)")
    text = "\n".join(report)
    path = os.path.join(OUT, f"score-{model}.txt")
    open(path, "w").write(text + "\n")
    print(text)
    print(f"\n(saved in {os.path.relpath(path, REPO)})")

SETS = (("without @hi", "requests-no-hi.txt"), ("the 2026-10-11 overlap", "requests-overlap.txt"), ("@hi", "requests-hi.txt"))

def run_score(path, answers_by_request):
    wanted = requests_of(open(os.path.join(OUT, path)))
    text = "".join(f"{r}\t{answers_by_request[r]}\n" for r in wanted)
    out = subprocess.run([sys.executable, os.path.join(HERE, "score.py"), os.path.join(BUILD, "all.json"), os.path.join(OUT, path),
                          os.path.join(BUILD, "bars.json"), os.path.join(BUILD, "cues.json")], input=text, capture_output=True, text=True, check=True).stdout
    marks = [l for l in out.splitlines() if re.match(r"(OK  |ok\? |ARGS|MISS) ", l)]
    assert len(marks) == len(wanted), (len(marks), len(wanted))
    right = {r: m[:4] in ("OK  ", "ok? ") for r, m in zip(wanted, marks)}
    return out, right, dict(zip(wanted, marks))

def pipeline(model):
    """Gemini Nano's rewrite ([model]) then Needle 3, against Needle 3 direct, on the same sets."""
    by_id = {}
    for l in open(os.path.join(OUT, f"rewrites-{model}.jsonl")):
        if l.strip(): r = json.loads(l); by_id[r["id"]] = r
    needle_on = {}
    for l in open(os.path.join(OUT, f"needle3-on-{model}.jsonl")):
        if l.strip(): r = json.loads(l); needle_on[r["id"]] = r
    direct = {json.loads(l)["request"]: json.loads(l) for l in open(os.path.join(OUT, "needle3.jsonl")) if l.strip()}
    answers, rewrite_ms, total_ms, said = {}, [], [], {}
    for rid, r in by_id.items():
        request = r["request"]
        said[request] = r
        if "error" in r or not r.get("command"):
            answers[request] = json.dumps({"function_calls": [], "confidence": 0.0})
            if "error" not in r: rewrite_ms.append(r["ms"]); total_ms.append(r["ms"])
            continue
        n = needle_on[rid]
        reply = json.loads(n["raw"]) if "raw" in n else {"function_calls": []}
        answers[request] = json.dumps(reply)
        rewrite_ms.append(r["ms"]); total_ms.append(r["ms"] + n["ms"])
    direct_answers = {q: json.dumps(json.loads(d["raw"])) for q, d in direct.items()}
    report = [f"# Gemini Nano {model.split('-')[1].upper()} rewrite -> Needle 3, against Needle 3 direct (phone, text only)"]
    for name, path in SETS:
        out, right, marks = run_score(path, answers)
        report.append(f"\n== {name}\n" + "\n".join(l for l in out.splitlines() if not re.match(r"(OK  |ok\? |ARGS|MISS) ", l)))
        if path == "requests-hi.txt":
            d_out, d_right, _ = run_score(path, direct_answers)
            report.append("Needle 3 direct on @hi: " + next(l for l in d_out.splitlines() if "right (tool" in l))
        else:
            d_out, d_right, d_marks = run_score(path, direct_answers)
            report.append("Needle 3 direct: " + next(l for l in d_out.splitlines() if "right (tool" in l))
            helped = [q for q in right if right[q] and not d_right[q]]
            hurt = [q for q in right if d_right[q] and not right[q]]
            report.append(f"helped {len(helped)}, hurt {len(hurt)}")
            if path == "requests-no-hi.txt":
                def show(q): return f"  {q!r} -> {said[q].get('command') or 'NONE'!r}: {marks[q][:4].strip()} {marks[q][60:].strip()[:110]}"
                report.append("helped, e.g.:\n" + "\n".join(show(q) for q in helped[:12]))
                report.append("hurt, all:\n" + "\n".join(show(q) for q in hurt))
        per = {}
        for q, m in marks.items():
            t = per.setdefault(re.match(r"\S+\s+[\d.]+\s+(\S+)", m)[1], [0, 0]); t[0] += right[q]; t[1] += 1
        report.append("right by expected tool: " + ", ".join(f"{t} {a}/{b}" for t, (a, b) in sorted(per.items())))
    def stats(x):
        x = sorted(x); return f"median {statistics.median(x):.0f}, 90th percentile {x[int(len(x) * 0.9)]} (n={len(x)})"
    errors = [r for r in by_id.values() if "error" in r]
    report.append(f"\nrewrite ms: {stats(rewrite_ms)}\nend to end ms (rewrite + Needle 3; NONE is the rewrite alone): {stats(total_ms)}")
    report.append(f"NONE: {sum(1 for r in by_id.values() if 'error' not in r and not r.get('command'))} of {len(by_id)}; rewrite errors: {len(errors)}" +
                  (f", e.g. {errors[0]['error'][:100]}" if errors else ""))
    text = "\n".join(report)
    path = os.path.join(OUT, f"score-pipeline-{model}.txt")
    open(path, "w").write(text + "\n")
    print(text)

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
    elif command == "needle": needle(sys.argv[2] if len(sys.argv) > 2 else None)
    elif command == "rewritten": rewritten(sys.argv[2])
    elif command == "pipeline": pipeline(sys.argv[2])
    elif command == "score": score(sys.argv[2])
    elif command == "laptop": laptop()
    else: sys.exit(__doc__)
