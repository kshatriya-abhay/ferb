"""The computer's time extraction on tool-check/times.txt: each line through ferb-link's timed
schema-call, with the line's tool and its "now", then scored by the app's own TimeArgs and Resolve
(app/src/test/…/TimesExtractionReport.kt), so the score is what Ferb would do on the phone.

    times.py [--jev] [--dry-run] [--replies kept.jsonl ...]

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
    extra, kept_paths, use_jev = [], [], False
    i = 0
    while i < len(argv):
        if argv[i] == "--dry-run": extra.append("--dry-run"); i += 1
        elif argv[i] == "--replies": kept_paths.append(argv[i + 1]); i += 2
        elif argv[i] == "--jev": use_jev = True; i += 1
        else: sys.exit(f"Unknown option {argv[i]}")
    tools = {t["name"]: t for t in json.load(open(os.path.join(BUILD, "all.json")))}
    cues = json.load(open(os.path.join(BUILD, "cues.json")))
    kept = {}
    for path in kept_paths:
        for r in (json.loads(l) for l in open(path) if l.strip()):
            kept[(r.get("kind", "call"), r["request"], r["now"])] = r
    all_lines = list(lines())
    send, ids = [], {}
    for n, line in enumerate(all_lines):
        if use_jev and ("decide", line["request"], line["now"]) not in kept:
            routed = [t for name, t in tools.items() if name in cues and cued(name, line["request"], cues)] or list(tools.values())
            ids[f"d{n}"] = ("decide", line)
            send.append({"id": f"d{n}", "method": "decide", "request": line["request"], "tools": routed, "timeout_ms": 10000})
        if ("call", line["request"], line["now"]) in kept: continue
        stamp = datetime.fromisoformat(line["now"])
        ids[f"t{n}"] = ("call", line)
        send.append({"id": f"t{n}", "method": "schema-call", "request": line["request"],
                     "now": f"{line['now']} ({stamp.strftime('%A')})", "tools": [tools[TOOLS[kind_of(line, cues)]]],
                     "timed": True, "timeout_ms": 20000})
    print(f"{len(all_lines)} lines; {sum(1 for x in send if x['method'] == 'decide')} decide and {sum(1 for x in send if x['method'] != 'decide')} schema-calls to send")
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
    out_dir = os.path.join(HERE, "..", "app", "build", "tool-check")
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "times-when.jsonl"), "w") as f:
        for line in all_lines:
            r = kept.get(("call", line["request"], line["now"]), {})
            call = next(iter(r.get("calls") or []), None)
            f.write(json.dumps({**line, "kind": kind_of(line, cues), "call": call}, ensure_ascii=False) + "\n")
    subprocess.run(["./gradlew", "--no-daemon", "-q", "testDebugUnitTest", "--tests", "*TimesExtractionReport*", "--rerun"],
                   cwd=os.path.join(HERE, ".."), check=True)
    print(open(os.path.join(out_dir, "times-report.txt")).read())
    ms = sorted(r["ms"] for (k, *_), r in kept.items() if k == "call" and r.get("ms"))
    if ms: print(f"timed schema-call: median / 90th {ms[len(ms) // 2]} / {ms[int(len(ms) * 0.9)]} ms (n={len(ms)})")
    if use_jev:
        # The hybrid gets the structure where Jev's pick (from 0.5) is the line's tool.
        def pick(line):
            d = kept.get(("decide", line["request"], line["now"]), {})
            p = (d.get("probabilities") or {}).get(d.get("choice"), 0.0) if d.get("success") else 0.0
            return d.get("choice") if p >= 0.5 else None
        timed = [l for l in all_lines if l["expected"].split()[0] in TOOLS]
        same = sum(1 for l in timed if pick(l) == TOOLS[l["expected"].split()[0]])
        print(f"Jev's pick is the line's tool on {same}/{len(timed)} timed lines (the hybrid's structure); "
              f"another tool or unsure on {len(timed) - same}; on the {len(all_lines) - len(timed)} ask and none lines it picks: "
              + ", ".join(f"{pick(l) or 'unsure/none'}" for l in all_lines if l["expected"].split()[0] not in TOOLS))

if __name__ == "__main__":
    main(sys.argv[1:])
