"""The computer's time extraction on tool-check/times.txt: each line through ferb-link's timed
schema-call, with the line's tool and its "now", then scored by the app's own TimeArgs and Resolve
(app/src/test/…/TimesExtractionReport.kt), so the score is what Ferb would do on the phone.

    times.py [--arm baseline|hybrid|t4] [--split tune|held] [--jev] [--dry-run] [--replies kept.jsonl ...]

Arms, all following Jev's pick as the app does (its "none" is no tool, unsure the routed tools):
baseline, today's Computer + Jev (Gemma's own call with Jev's tools, its string arguments through
Ferb's parsers); hybrid, the same but timed schema-call (t3) with the line's tool's shape when
Jev picks it; t4, the hybrid with ferb-link's t4 time prompt. --split sends and scores one half.

--jev: also Jev's pick for each request (Ferb Link's decide, the routed tools; costs money, about
$0.00003 a call), for the hybrid (B+Jev, with timed schema-call when Jev picks a timed tool): the
report says how often Jev's pick is the line's tool, where the hybrid gets the structure.

A model evaluation (free, but Gemma runs on the laptop's server: take /tmp/ferb-validate.lock).
Replies are kept by request and now in results/times-*.jsonl; only missing ones are sent. Writes
app/build/tool-check/times-when.jsonl, runs the report, and prints app/build/tool-check/times-report.txt.
"""
import json, os, re, subprocess, sys, time
from datetime import datetime
from laptop import BUILD, HERE, harness
from score import cued

TOOLS = {"alarm": "set_alarm", "timer": "set_timer", "event": "add_event", "reminder": "add_reminder"}

def lines():
    now = zone = split = category = None
    for raw in open(os.path.join(HERE, "times.txt")):
        l = raw.strip()
        m = re.match(r"^# now=(\S+) (\S+)", l)
        if m: now, zone = m.group(1), m.group(2)
        if l.startswith("# split="): split = l.split("=", 1)[1]
        elif l.startswith("# @"): category = l[3:]
        if not l or l.startswith("#"): continue
        expected, request, _ = [p.strip() for p in l.split("|")]
        yield dict(now=now, zone=zone, split=split, category=category, expected=expected, request=request)

def kind_of(line, cues):
    """The tool a line is for: its expected kind, or for "ask" and "none" what it talks about."""
    k = line["expected"].split()[0]
    if k in TOOLS: return k
    r = line["request"].lower()
    if "alarm" in r or "wake" in r: return "alarm"
    if "timer" in r: return "timer"
    if "remind" in r or "yaad" in r: return "reminder"
    return "event"

def main(argv):
    extra, kept_paths, use_jev, arm, split = [], [], False, "hybrid", None
    i = 0
    while i < len(argv):
        if argv[i] == "--dry-run": extra.append("--dry-run"); i += 1
        elif argv[i] == "--replies": kept_paths.append(argv[i + 1]); i += 2
        elif argv[i] == "--jev": use_jev = True; i += 1
        elif argv[i] == "--arm": arm = argv[i + 1]; i += 2
        elif argv[i] == "--split": split = argv[i + 1]; i += 2
        else: sys.exit(f"Unknown option {argv[i]}")
    tools = {t["name"]: t for t in json.load(open(os.path.join(BUILD, "all.json")))}
    cues = json.load(open(os.path.join(BUILD, "cues.json")))
    kept = {}
    for path in kept_paths:
        for r in (json.loads(l) for l in open(path) if l.strip()):
            kept[(r.get("kind", "call"), r["request"], r["now"])] = r
    all_lines = list(lines())
    # The timed arm's kept kind: "call" for t3 (the first run), "call:t4" for ferb-link's t4 prompt.
    timed_kind = "call:t4" if arm == "t4" else "call"

    def routed(line):
        return [t for name, t in tools.items() if name in cues and cued(name, line["request"], cues)] or list(tools.values())

    def pick(line):
        """Jev's pick at 0.5 as B+Jev uses it: [tool, next ones at 0.25 or more] (three at most), [] for none, None if unsure."""
        d = kept.get(("decide", line["request"], line["now"]), {})
        p = (d.get("probabilities") or {}).get(d.get("choice"), 0.0) if d.get("success") else 0.0
        if p < 0.5: return None
        if d.get("choice") not in tools: return []
        probs = d.get("probabilities") or {}
        others = sorted((n for n, q in probs.items() if n in tools and n != d["choice"] and q >= 0.25), key=lambda n: -probs[n])
        return [d["choice"]] + others[:2]

    def timed_pick(line):
        names = pick(line)
        return bool(names) and names[0] == TOOLS.get(line["expected"].split()[0], TOOLS[kind_of(line, cues)])

    send, ids = [], {}
    for n, line in enumerate(all_lines):
        if split and line["split"] != split: continue
        if use_jev and ("decide", line["request"], line["now"]) not in kept:
            ids[f"d{n}"] = ("decide", line)
            send.append({"id": f"d{n}", "method": "decide", "request": line["request"], "tools": routed(line), "timeout_ms": 10000})
            continue  # the other calls depend on Jev's pick: a second run sends them
        names = pick(line)
        stamp = datetime.fromisoformat(line["now"])
        now = f"{line['now']} ({stamp.strftime('%A')})"
        if arm in ("baseline", "hybrid", "t4") and names != [] and ("complete", line["request"], line["now"]) not in kept and not (arm != "baseline" and timed_pick(line)):
            # B+Jev: Gemma's own tool call with Jev's tools (routed when Jev is unsure).
            ids[f"c{n}"] = ("complete", line)
            send.append({"id": f"c{n}", "request": line["request"], "tools": [tools[x] for x in names] if names else routed(line), "timeout_ms": 20000})
        if arm in ("hybrid", "t4") and timed_pick(line) and (timed_kind, line["request"], line["now"]) not in kept:
            ids[f"t{n}"] = (timed_kind, line)
            send.append({"id": f"t{n}", "method": "schema-call", "request": line["request"], "now": now,
                         "tools": [tools[TOOLS.get(line["expected"].split()[0], TOOLS[kind_of(line, cues)])]], "timed": True,
                         **({"time_prompt": "t4"} if arm == "t4" else {}), "timeout_ms": 20000})
    print(f"{len(all_lines)} lines; {sum(1 for x in send if x.get('method') == 'decide')} decide, "
          f"{sum(1 for x in send if x.get('method') == 'schema-call')} schema-calls and {sum(1 for x in send if not x.get('method'))} completes to send")
    if send:
        replies = harness(send, extra)
        if replies is None: return
        path = os.path.join(HERE, "results", time.strftime("times-%Y%m%d-%H%M%S.jsonl"))
        with open(path, "w") as f:
            for rid, r in replies.items():
                kind, line = ids[rid]
                r = {**r, "request": line["request"], "now": line["now"], "kind": kind}
                kept[(kind, line["request"], line["now"])] = r
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        print(f"Replies kept in {os.path.relpath(path)}")

    def call_for(line):
        """The call the arm's path makes for a line, as {tool, arguments, when, asks, trimmed}, and its ms."""
        names = pick(line)
        if names == []: return None, None
        if arm != "baseline" and timed_pick(line):
            r = kept.get((timed_kind, line["request"], line["now"]), {})
            return next(iter(r.get("calls") or []), None), r.get("ms")
        r = kept.get(("complete", line["request"], line["now"]), {})
        c = next(iter(r.get("function_calls") or []), None)
        return ({"tool": c["name"], "arguments": c.get("arguments") or {}} if c else None), r.get("ms")

    scored = [l for l in all_lines if not split or l["split"] == split]
    out_dir = os.path.join(HERE, "..", "app", "build", "tool-check")
    os.makedirs(out_dir, exist_ok=True)
    timed_ms, other_ms = [], []
    with open(os.path.join(out_dir, "times-when.jsonl"), "w") as f:
        for line in scored:
            call, ms = call_for(line)
            if ms: (timed_ms if arm != "baseline" and timed_pick(line) else other_ms).append(ms)
            f.write(json.dumps({**line, "kind": kind_of(line, cues), "call": call}, ensure_ascii=False) + "\n")
    subprocess.run(["./gradlew", "--no-daemon", "-q", "testDebugUnitTest", "--tests", "*TimesExtractionReport*", "--rerun"],
                   cwd=os.path.join(HERE, ".."), check=True)
    report = open(os.path.join(out_dir, "times-report.txt")).read()
    print(f"== arm {arm}" + (f", {split} half" if split else ""))
    print(report)
    for label, ms in (("timed schema-call", sorted(timed_ms)), ("B+Jev's Gemma call", sorted(other_ms))):
        if ms: print(f"{label}: median / 90th {ms[len(ms) // 2]} / {ms[int(len(ms) * 0.9)]} ms (n={len(ms)})")
    trimmed = [l["request"] for l in scored for c in [call_for(l)[0]] if c and c.get("trimmed")]
    print(f"trimmed calls: {len(trimmed)}" + (f": {'; '.join(trimmed)}" if trimmed else ""))
    with open(os.path.join(HERE, "results", f"times-report-{arm}{'-' + split if split else ''}.txt"), "w") as f: f.write(report)

if __name__ == "__main__":
    main(sys.argv[1:])
