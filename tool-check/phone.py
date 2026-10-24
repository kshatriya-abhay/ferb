"""The phone bench (text only): Needle 3 on the Pixel, alone and after Gemini Nano's rewrite (the
app's GeminiRewrite prompt), on `mise run bench`'s requests (requests.txt + suite.txt) and the
rewrite prompt's held-out lines (rewrite-heldout.txt), routed and scored as the laptop's bench is
(route.py, score.py), so the numbers line up with summary-all-routed.txt.

    phone.py prep          write build/phone-bench/requests.jsonl: each request with its routed tools
    phone.py push          copy it into Ferb's files/bench/ (debug build; adb)
    phone.py needle        Needle 3's pass with no screen (PhoneBenchTest); take /tmp/ferb-validate.lock first
    phone.py pull          copy every answers file back
    phone.py score needle3 score Needle 3 alone with score.py
    phone.py laptop        the laptop's saved Needle 3 and Gemma 4 E2B answers on the 2026-10-11 overlap
    phone.py rewritten M   Gemini Nano's rewrites (M, e.g. v2-gemini-full, from Settings → Bench) routed and pushed for Needle 3
    phone.py needle M      Needle 3 on M's rewrites, with no screen (lock first)
    phone.py pipeline M    score rewrite -> Needle 3 against Needle 3 alone, with what the rewrite helped and hurt
    phone.py compare M ... Needle 3 alone and each rewrite, side by side on every set
    phone.py dates-prep    the date lines (datetime-v2.txt, each with its "now") pushed as files/bench/dates.jsonl
    phone.py blind-prep    the blind set's lines, the same way, as files/bench/dates-blind.jsonl
    phone.py readers SET R.jsonl OUT  the date bench app (:datebench, ML Kit and TextClassifier) on SET's words as
                           said and R's rewrites (from dates-out): pushes its inputs, runs it (no screen), pulls to OUT
    phone.py dates-out     after Settings -> Bench -> "Dates" and pull: the passes as build/datetime/ inputs
                           (dates.py needle FILE for the rewrites, DateTimeEval for the slots), with Gemini's latency

Gemini Nano making the tool call itself (the schema call) and its scoring are on poc/gemini-nano-direct.
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
    # The rewrite prompt's held-out lines (rewrite-heldout.txt, frozen 2026-10-24), as their own set.
    held = requests_of(open(os.path.join(HERE, "rewrite-heldout.txt")))
    open(os.path.join(OUT, "rewrite-heldout.txt"), "w").writelines(open(os.path.join(HERE, "rewrite-heldout.txt")))
    seen, rows = set(), []
    for prefix, requests in (("r", main_set), ("h", hi_set), ("x", held)):
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
    print(f"{len(rows)} requests ({sum(r['id'][0] == 'r' for r in rows)} without @hi, {sum(r['id'][0] == 'h' for r in rows)} @hi, "
          f"{sum(r['id'][0] == 'x' for r in rows)} held out); "
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
    """Every answers file the bench wrote (files/bench/*.jsonl but the requests)."""
    listed = subprocess.run(["adb", "exec-out", "run-as", PACKAGE, "sh", "-c", "cd files/bench && ls *.jsonl"], capture_output=True, text=True).stdout.split()
    for model in (f[:-len(".jsonl")] for f in listed if not f.startswith("requests")):
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

def answers(model):
    rows = [json.loads(l) for l in open(os.path.join(OUT, f"{model}.jsonl")) if l.strip()]
    out, errors = {}, []
    for r in rows:
        if "error" in r:
            errors.append(r)
            out[r["request"]] = json.dumps({"function_calls": [], "confidence": 0.0, "ms": r.get("ms")})
        else:
            reply = json.loads(r["raw"])
            reply["ms"] = r.get("ms")
            out[r["request"]] = json.dumps(reply)
    return rows, out, errors

def score(model):
    rows, out, errors = answers(model)
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
    text = "\n".join(report)
    path = os.path.join(OUT, f"score-{model}.txt")
    open(path, "w").write(text + "\n")
    print(text)
    print(f"\n(saved in {os.path.relpath(path, REPO)})")

SETS = (("without @hi", "requests-no-hi.txt"), ("the 2026-10-11 overlap", "requests-overlap.txt"), ("@hi", "requests-hi.txt"),
        ("held out", "rewrite-heldout.txt"))

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
    report = [f"# Gemini Nano rewrite ({model}) -> Needle 3, against Needle 3 direct (phone, text only)"]
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
            if path in ("requests-no-hi.txt", "rewrite-heldout.txt"):
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

def compare(models):
    """Needle 3 direct and each rewrite pipeline ([models], e.g. gemini-full v2-gemini-full), side by side on every set."""
    direct = {json.loads(l)["request"]: json.dumps(json.loads(json.loads(l)["raw"])) for l in open(os.path.join(OUT, "needle3.jsonl")) if l.strip()}
    columns = [("Needle 3 direct", direct)]
    for model in models:
        rewrites = {json.loads(l)["id"]: json.loads(l) for l in open(os.path.join(OUT, f"rewrites-{model}.jsonl")) if l.strip()}
        on = {json.loads(l)["id"]: json.loads(l) for l in open(os.path.join(OUT, f"needle3-on-{model}.jsonl")) if l.strip()}
        answers = {r["request"]: json.dumps(json.loads(on[i]["raw"]) if r.get("command") and i in on else {"function_calls": []}) for i, r in rewrites.items()}
        columns.append((model, answers))
    print(f"{'set':<26}" + "".join(f"{name:>26}" for name, _ in columns))
    for name, path in SETS:
        cells = []
        for _, answers in columns:
            wanted = requests_of(open(os.path.join(OUT, path)))
            if any(r not in answers for r in wanted): cells.append("not run"); continue
            _, right, marks = run_score(path, answers)
            none = [q for q in wanted if marks[q].split()[2] == "none"]
            cells.append(f"{sum(right.values())}/{len(wanted)} (none {sum(right[q] for q in none)}/{len(none)})")
        print(f"{name:<26}" + "".join(f"{c:>26}" for c in cells))

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

DATES_SET = "datetime-v2.txt"
DATE_PASSES = ["dates-rewrites-v2", "dates-calendar", "dates-slots", "dates-r", "blind-r", "blind-rewrites-v2"]

def dates_prep(set_name=DATES_SET, out_name="dates.jsonl"):
    """The lines of [set_name] (read by the program, not shown), each with its now, pushed as files/bench/[out_name]."""
    rows = []
    for raw in open(os.path.join(HERE, set_name)):
        if not raw.strip() or raw.startswith("#"): continue
        now, tool, expected, request = [x.strip() for x in raw.split("|", 3)]
        rows.append({"id": str(len(rows)), "request": request, "tools": [], "now": now.split()[0]})
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, out_name), "w") as f:
        for r in rows: f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"{len(rows)} lines of {set_name}")
    push(out_name)

def dates_out():
    dest = os.path.join(REPO, "build", "datetime")
    os.makedirs(dest, exist_ok=True)
    for name in DATE_PASSES:
        path = os.path.join(OUT, f"{name}.jsonl")
        if not os.path.exists(path): print(f"{name}: not pulled"); continue
        rows = [json.loads(l) for l in open(path) if l.strip()]
        ms = sorted(r["ms"] for r in rows if "error" not in r and "ms" in r)
        with open(os.path.join(dest, f"{name}.jsonl"), "w") as f:
            for r in rows:
                out = {"i": int(r["id"]), "raw": r.get("raw"), "ms": r.get("ms")}
                # NONE: no call (null); an error: the app falls back to the request as typed.
                if "command" in r: out["command"] = r["command"]
                if "error" in r: out["error"] = r["error"]; out["command"] = r["request"]
                f.write(json.dumps(out, ensure_ascii=False) + "\n")
        p90 = ms[int(len(ms) * 0.9)] if ms else None
        print(f"{name}: {len(rows)} lines, {sum('error' in r for r in rows)} errors; Gemini ms median {ms[len(ms) // 2] if ms else '-'}, 90th {p90}")

BENCH = "dev.abhay.datebench"

def readers(set_name, r_file, out_dir):
    rows = []
    for raw in open(os.path.join(HERE, set_name)):
        if not raw.strip() or raw.startswith("#"): continue
        now, tool, expected, request = [x.strip() for x in raw.split("|", 3)]
        rows.append({"i": len(rows), "now": now.split()[0], "text": request, "source": "said"})
    rewritten = {json.loads(l)["i"]: json.loads(l).get("command") for l in open(r_file) if l.strip()}
    rows += [{"i": r["i"], "now": r["now"], "text": rewritten[r["i"]], "source": "R"} for r in list(rows) if rewritten.get(r["i"])]
    data = "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows).encode()
    subprocess.run(["adb", "exec-in", "run-as", BENCH, "sh", "-c", "mkdir -p files && cat > files/inputs.jsonl"], input=data, check=True)
    print(f"{len(rows)} inputs pushed")
    subprocess.run(["adb", "shell", "am", "instrument", "-w", "-e", "class", f"{BENCH}.DateBenchTest", f"{BENCH}.test/androidx.test.runner.AndroidJUnitRunner"], check=True)
    os.makedirs(out_dir, exist_ok=True)
    for name in ["reader-mlkit.jsonl", "reader-tc.jsonl"]:
        with open(os.path.join(out_dir, name), "wb") as f:
            subprocess.run(["adb", "exec-out", "run-as", BENCH, "cat", f"files/{name}"], stdout=f, check=False)
        print(name, sum(1 for l in open(os.path.join(out_dir, name)) if l.strip()))

if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else ""
    if command == "prep": prep()
    elif command == "push": push()
    elif command == "pull": pull()
    elif command == "needle": needle(sys.argv[2] if len(sys.argv) > 2 else None)
    elif command == "rewritten": rewritten(sys.argv[2])
    elif command == "pipeline": pipeline(sys.argv[2])
    elif command == "compare": compare(sys.argv[2:])
    elif command == "score": score(sys.argv[2])
    elif command == "laptop": laptop()
    elif command == "dates-prep": dates_prep()
    elif command == "blind-prep": dates_prep("datetime-blind.txt", "dates-blind.jsonl")
    elif command == "dates-out": dates_out()
    elif command == "readers": readers(sys.argv[2], sys.argv[3], sys.argv[4])
    else: sys.exit(__doc__)
