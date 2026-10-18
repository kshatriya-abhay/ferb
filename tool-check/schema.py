"""ferb-link's schema-call (one Gemma call answering {tool, arguments, when} under a JSON schema)
as Computer mode with Jev would use it: Jev's kept pick decides the tools (its tool and the next
ones it rates at 0.25 or more, as B+Jev), "none" is no tool, unsure goes to the routed tools.

    schema.py --set requests|suite [--arm plain|rewrite|timed ...] [--dry-run] [--replies kept.jsonl ...]

Arms: plain (rewrite false), rewrite (rewrite true: its commands are counted, not scored), timed
(timed true: "when" required in the timed tools); "timed2" etc. is timed again, as a fresh run
after a harness change (kept replies are per arm name). Scored like B+Jev (right, runs, asks, missed;
the checks against the request), @hi apart; a time argument comes from "when" where it's filled
(alarm hours by TimeOfDay's rules, timer lengths), so a filled "when" is what's scored. Also: how
often "when" is filled on lines with a time, and the latency. Replies kept by request and arm in
results/schema-*.jsonl; take /tmp/ferb-validate.lock (Gemma runs on the laptop's server).
"""
import json, os, statistics, sys, time
from jev import bench_lines
from laptop import BUILD, HERE, harness, scored
from score import cued, grounded

NOW = "2026-10-16T09:00 (Friday)"
TIMED = {"set_alarm": "time", "set_timer": "duration", "add_event": "when", "add_reminder": "time"}

def alarm_time(w):
    """An alarm's HH:MM from "when", by TimeOfDay.of's rules; None if it has no hour."""
    t = (w or {}).get("time") or {}
    h, m = t.get("hour"), t.get("minute") or 0
    if h is None: return None
    meridiem, part = t.get("meridiem"), t.get("part")
    period = meridiem if h <= 12 else None
    if period is None and h <= 12:
        period = {"morning": "am", "afternoon": "pm", "evening": "pm"}.get(part)
        if part == "night": period = "am" if h == 12 or 1 <= h <= 5 else "pm"
    if period == "am" and h == 12: h = 0
    elif period == "pm" and h != 12: h += 12
    return f"{h:02d}:{m:02d}"

def seconds(w):
    l = (w or {}).get("length")
    if not l: return None
    return ((l.get("days") or 0) * 86400 + (l.get("hours") or 0) * 3600 + (l.get("minutes") or 0) * 60 + (l.get("seconds") or 0))

def main(argv):
    extra, kept_paths, which, arms = [], [], "requests", []
    i = 0
    while i < len(argv):
        if argv[i] == "--set": which = argv[i + 1]; i += 2
        elif argv[i] == "--arm": arms.append(argv[i + 1]); i += 2
        elif argv[i] == "--dry-run": extra.append("--dry-run"); i += 1
        elif argv[i] == "--replies": kept_paths.append(argv[i + 1]); i += 2
        else: sys.exit(f"Unknown option {argv[i]}")
    arms = arms or ["plain", "rewrite", "timed"]
    from lines import kept as kept_lines
    in_set = {l.split("|", 1)[1].strip() for l in kept_lines([os.path.join(HERE, f"{which}.txt")], set()) if "|" in l and not l.startswith("#")}
    lines = [l for l in bench_lines() if l[0] in in_set]
    tools = json.load(open(os.path.join(BUILD, "all.json")))
    by_name = {t["name"]: t for t in tools}
    cues = json.load(open(os.path.join(BUILD, "cues.json")))
    confirm = set(json.load(open(os.path.join(BUILD, "confirm.json"))))
    kept = {}
    for path in kept_paths:
        for r in (json.loads(l) for l in open(path) if l.strip()):
            if "request" in r: kept.setdefault(r["request"], {})[r.get("arm") or r["kind"]] = r

    def routed(text):
        return [t for t in tools if cued(t["name"], text, cues) and t["name"] in cues] or tools

    def jev_tools(text):
        """B+Jev's tools: Jev's pick and its next ones; [] for none; None when Jev is unsure (routed)."""
        d = kept.get(text, {}).get("decide", {})
        p = (d.get("probabilities") or {}).get(d.get("choice"), 0.0) if d.get("success") else 0.0
        if p < 0.5: return None
        if d.get("choice") not in by_name: return []
        probabilities = d.get("probabilities") or {}
        others = sorted((n for n, q in probabilities.items() if n in by_name and n != d["choice"] and q >= 0.25), key=lambda n: -probabilities[n])
        return [by_name[n] for n in [d["choice"]] + others[:2]]

    ids, send = {}, []
    for n, (text, _, _, _) in enumerate(lines):
        picked = jev_tools(text)
        if picked == []: continue
        for arm in arms:
            if arm in kept.get(text, {}): continue
            rid = f"{arm[0]}{n}"
            ids[rid] = (arm, text)
            send.append({"id": rid, "method": "schema-call", "request": text, "now": NOW, "tools": picked or routed(text),
                         "rewrite": arm == "rewrite", "timed": arm.startswith("timed"), "timeout_ms": 20000})
    print(f"{len(lines)} requests ({which}.txt); {len(send)} schema-calls to send")
    if send:
        replies = harness(send, extra)
        if replies is None: return
        path = os.path.join(HERE, "results", time.strftime("schema-%Y%m%d-%H%M%S.jsonl"))
        with open(path, "w") as f:
            for rid, r in replies.items():
                arm, text = ids[rid]
                r = {**r, "request": text, "arm": arm}
                kept.setdefault(text, {})[arm] = r
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        print(f"Replies kept in {os.path.relpath(path)}")

    def outcome(arm, text):
        """The first call as Ferb would run it ({name, arguments}, "run"/"ask"), or None."""
        if jev_tools(text) == []: return None
        r = kept.get(text, {}).get(arm, {})
        call = next(iter(r.get("calls") or []), None)
        if not call: return None
        args = dict(call.get("arguments") or {})
        w = call.get("when")
        param = TIMED.get(call.get("tool"))
        if w and param == "time" and call["tool"] == "set_alarm" and alarm_time(w): args["time"] = alarm_time(w)
        if w and call["tool"] == "set_timer" and seconds(w): args["duration"] = f"{seconds(w)} seconds"
        c = {"name": call.get("tool"), "arguments": args}
        resolved_time = bool(w) and param is not None
        # The harness's ask-only checks ("parso" not +2, a repeat asked for but not given) ask.
        # A call the harness cut at leaked JSON ("trimmed") always asks (ferb-manager's rule).
        runs = not call.get("asks") and not call.get("trimmed") and c["name"] not in confirm and cued(c["name"], text, cues) and (resolved_time or grounded(c, text))
        return c, "run" if runs else "ask"

    def b_jev(text):
        names = jev_tools(text)
        if names == []: return None
        r = kept.get(text, {}).get("complete_jev3" if names and len(names) > 1 else "complete_jev" if names else "complete", {})
        call = next(iter(r.get("function_calls") or []), None)
        if not call: return None
        runs = all(f["name"] not in confirm and cued(f["name"], text, cues) and grounded(f, text) for f in r["function_calls"])
        return call, "run" if runs else "ask"

    def tally(fn, subset):
        t = dict(right=0, right_runs=0, wrong_runs=0, right_asks=0, wrong_asks=0, missed=0)
        for request, want_tools, want, _ in subset:
            got = fn(request)
            call, how = got if got else (None, None)
            ok = scored(call, want_tools, want, request)
            t["right"] += ok
            if how == "run": t["right_runs" if ok else "wrong_runs"] += 1
            elif how == "ask": t["right_asks" if ok else "wrong_asks"] += 1
            elif not ok: t["missed"] += 1
        return t

    # H (ferb-manager's hybrid): B+Jev, except that a request whose Jev pick is a timed tool goes to
    # timed schema-call, for the time structure. From kept replies (the timed ones were made with
    # Jev's tools, its pick first, rather than that tool alone).
    timed_arm = next((a for a in reversed(arms) if a.startswith("timed")), None)
    def timed_pick(text):
        names = jev_tools(text)
        return bool(names) and names[0]["name"] in TIMED
    def hybrid(text):
        return outcome(timed_arm, text) if timed_pick(text) else b_jev(text)
    rows = [("B+Jev", b_jev)] + [(f"S {arm}", lambda t, a=arm: outcome(a, t)) for arm in arms] + ([("H hybrid", hybrid)] if timed_arm else [])
    for name, subset in [(f"{which}.txt without @hi", [l for l in lines if l[3] != "hi"]), ("@hi", [l for l in lines if l[3] == "hi"])]:
        print(f"\n== {name}: {len(subset)} requests")
        print(f"{'':<12}{'right':>7}{'runs ok':>9}{'runs ✗':>8}{'asks ok':>9}{'asks ✗':>8}{'missed':>8}")
        for label, fn in rows:
            t = tally(fn, subset)
            print(f"{label:<12}{t['right']:>7}{t['right_runs']:>9}{t['wrong_runs']:>8}{t['right_asks']:>9}{t['wrong_asks']:>8}{t['missed']:>8}")
    # SHOW=<arm>: that arm's wrong runs and misses, to read.
    show = os.environ.get("SHOW")
    if show:
        print(f"\n{show}: wrong runs and misses")
        for request, want_tools, want, _ in lines:
            got = outcome(show, request) if show != "B+Jev" else b_jev(request)
            call, how = got if got else (None, None)
            if not scored(call, want_tools, want, request) and (how == "run" or call is None and want_tools != ["none"]):
                w = next(iter(kept.get(request, {}).get(show, {}).get("calls") or []), {}).get("when")
                print(f"  {how or 'missed':<6} {'/'.join(want_tools):<14} {want} {request[:42]:<42} -> {call and call['name']} {json.dumps(call and call['arguments'])[:50]} when={json.dumps(w)[:80]}")
    if which == "suite":
        held = [l for l in lines if l[3] != "hi"]
        print(f"\nBy category, right runs / wrong runs: " + ", ".join(label for label, _ in rows))
        for category in dict.fromkeys(l[3] for l in held):
            subset = [l for l in held if l[3] == category]
            print(f"  {category:<10}{len(subset):>4}  " + "  ".join(f"{t['right_runs']:>3}/{t['wrong_runs']:<2}" for t in (tally(fn, subset) for _, fn in rows)))

    # "when": how often it's filled where the expected call is a timed tool with a time, and right.
    print("\n\"when\" on lines whose expected tool takes a time (alarm hours and timer lengths checked):")
    for arm in arms:
        filled = right = checked = n = 0
        for request, want_tools, want, _ in lines:
            if want_tools[0] not in TIMED: continue
            r = kept.get(request, {}).get(arm, {})
            call = next((c for c in r.get("calls") or [] if c.get("tool") == want_tools[0]), None)
            if not call: continue
            n += 1
            w = call.get("when")
            filled += bool(w)
            if w and want_tools[0] == "set_alarm" and "time" in want:
                checked += 1; right += alarm_time(w) == want["time"]
            if w and want_tools[0] == "set_timer" and "seconds" in want:
                checked += 1; right += seconds(w) == want["seconds"]
        print(f"  {arm:<8} filled {filled}/{n}; right {right}/{checked} of the alarm times and timer lengths checked")
    print("\nLatency, ms (median / 90th):")
    for arm in arms:
        ms = sorted(kept[t][arm]["ms"] for t, *_ in lines if kept.get(t, {}).get(arm, {}).get("ms"))
        if ms: print(f"  {arm:<8} {statistics.median(ms):.0f} / {ms[int(len(ms) * 0.9)]}  (n={len(ms)}; failed {sum(1 for t, *_ in lines if arm in kept.get(t, {}) and not kept[t][arm].get('success'))})")
    # Calls the harness cut at leaked JSON ("trimmed"): should be rare.
    for arm in arms:
        cut = [t for t, *_ in lines for c in kept.get(t, {}).get(arm, {}).get("calls") or [] if c.get("trimmed")]
        print(f"  {arm}: {len(cut)} calls trimmed" + (f": {'; '.join(cut)}" if cut else ""))
    if timed_arm:
        def computer_ms(t):
            names = jev_tools(t)
            kind = "complete_jev3" if names and len(names) > 1 else "complete_jev" if names else "complete"
            return kept.get(t, {}).get(kind, {}).get("ms")
        timed_ms = sorted(m for t, *_ in lines if timed_pick(t) for m in [kept.get(t, {}).get(timed_arm, {}).get("ms")] if m)
        other_ms = sorted(m for t, *_ in lines if not timed_pick(t) and jev_tools(t) != [] for m in [computer_ms(t)] if m)
        print(f"\nH hybrid: {len(timed_ms)} timed requests (median / 90th {statistics.median(timed_ms):.0f} / {timed_ms[int(len(timed_ms) * 0.9)]} ms, timed schema-call),"
              f" {len(other_ms)} untimed (median / 90th {statistics.median(other_ms):.0f} / {other_ms[int(len(other_ms) * 0.9)]} ms, B+Jev's Gemma call)")
    if "rewrite" in arms:
        print(f"\nrewrite: commands instead of calls on {sum(1 for t, *_ in lines if kept.get(t, {}).get('rewrite', {}).get('commands'))} requests (not scored)")

if __name__ == "__main__":
    main(sys.argv[1:])
