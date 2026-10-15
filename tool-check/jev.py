"""Jev as the answerer (the chat's "Jev" mode), on the laptop: Jev picks the tool (Ferb Link's
decide, over the tools the request mentions, else every tool), then Needle 3 fills the call with
only that tool. Compared with Phone (Needle 3 alone, routed as the app does) and Computer (Gemma
through Ferb Link), per bench category. The @hi section (Hindi and Hinglish) is the computer's
only: Jev and Needle 3 don't understand Hindi, so no Jev call goes out for it and Phone and Jev
aren't scored on it; Computer's score on it is shown apart.

    jev.py [--dry-run] [--limit N] [--replies kept.jsonl ...]

Bench: requests.txt + suite.txt. Jev's and Gemma's replies are kept by request text in
tool-check/results/jev-mode-*.jsonl; every kept file passed with --replies is reused, and only
the requests missing from them are sent (Jev costs money: Ferb Link's harness keeps the ledger
and its cap; --dry-run prints the estimate). Take /tmp/ferb-validate.lock for a real run: Gemma's
lines use the laptop's model server. Needle 3 runs here (cached in results/needle3.jsonl).
"""
import json, os, re, shlex, statistics, sys, time
import route
from laptop import BUILD, HERE, NEEDLE_SALT, harness, scored
from score import cued, grounded
from lines import kept

def bench_lines():
    """(request, expected tools, expected values, category) for requests.txt then suite.txt."""
    out = []
    for name in ("requests.txt", "suite.txt"):
        category = "requests"
        for line in kept([os.path.join(HERE, name)], set()):
            line = line.strip()
            if line.startswith("# @"): category = line[3:].split(":")[0].strip()
            if not line or line.startswith("#"): continue
            spec, request = [x.strip() for x in line.split("|", 1)]
            tool, *pairs = shlex.split(spec)
            from score import normalise
            out.append((request, tool.split("/"), {k: normalise(tool, k, v) for k, v in (p.split("=", 1) for p in pairs)}, category))
    return out

def main(argv):
    extra, kept_paths = [], []
    i = 0
    while i < len(argv):
        if argv[i] == "--limit": extra += ["--limit", argv[i + 1]]; i += 2
        elif argv[i] == "--dry-run": extra.append("--dry-run"); i += 1
        elif argv[i] == "--replies": kept_paths.append(argv[i + 1]); i += 2
        else: sys.exit(f"Unknown option {argv[i]}")
    lines = bench_lines()
    texts = [r for r, _, _, _ in lines]
    tools = json.load(open(os.path.join(BUILD, "all.json")))
    by_name = {t["name"]: t for t in tools}
    cues = json.load(open(os.path.join(BUILD, "cues.json")))
    bars = json.load(open(os.path.join(BUILD, "bars.json")))
    confirm = set(json.load(open(os.path.join(BUILD, "confirm.json"))))

    # Kept replies, by request: {"decide": …, "complete": …}.
    kept = {}
    for path in kept_paths:
        for r in (json.loads(l) for l in open(path) if l.strip()):
            if "request" in r: kept.setdefault(r["request"], {})[r["kind"]] = r
    def routed(text):
        return [t for t in tools if cued(t["name"], text, cues) and t["name"] in cues] or tools
    send = []
    for n, text in enumerate(texts):
        have = kept.get(text, {})
        if "complete" not in have: send.append({"id": f"c{n}", "request": text, "tools": routed(text), "timeout_ms": 10000})
        if "decide" not in have and lines[n][3] != "hi": send.append({"id": f"d{n}", "method": "decide", "request": text, "tools": routed(text), "timeout_ms": 10000})
    print(f"{len(texts)} requests; {sum(1 for s in send if s.get('method'))} decide and {sum(1 for s in send if not s.get('method'))} complete lines to send")
    if send:
        replies = harness(send, extra)
        if replies is None: return
        path = os.path.join(HERE, "results", time.strftime("jev-mode-%Y%m%d-%H%M%S.jsonl"))
        with open(path, "w") as f:
            for rid, r in replies.items():
                n = int(rid[1:])
                r = {**r, "request": texts[n], "kind": "decide" if rid[0] == "d" else "complete"}
                kept.setdefault(texts[n], {})[r["kind"]] = r
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        print(f"Replies kept in {os.path.relpath(path)}")

    # Needle 3: routed as the app does (Phone), and with only Jev's tool (Jev mode).
    engine = os.path.join(HERE, "..", "build", "tool-check", "tool_check")
    model = os.path.join(HERE, "..", "app", "src", "main", "assets", "needle3.cact")
    ask = route.Cache(os.path.join(HERE, "results", "needle3.jsonl"), NEEDLE_SALT).wrap(lambda t, asked: route.run(engine, model, t, asked))
    phone = {r: json.loads(a) for r, a in route.answer(ask, tools, cues, texts)[0].items()}
    picks = {}
    for text in texts:
        d = kept.get(text, {}).get("decide", {})
        if d.get("success") and d.get("choice") in by_name: picks.setdefault(d["choice"], []).append(text)
    alone = {}
    for name, asked in picks.items():
        for r, a in ask([by_name[name]], asked).items(): alone[r] = json.loads(a)

    def first(reply):
        return next(iter(reply.get("function_calls") or []), None)

    def checks(call, text):
        return call["name"] not in confirm and cued(call["name"], text, cues) and grounded(call, text)

    def phone_outcome(text):
        reply = phone[text]
        call = first(reply)
        if call is None: return None, None
        runs = checks(call, text) and (reply.get("confidence") or 0) >= max(bars.get(c["name"], 0.7) for c in reply["function_calls"])
        return call, "run" if runs else "ask"

    def computer_outcome(text):
        c = kept.get(text, {}).get("complete", {})
        call = first(c)
        if call is None: return None, None
        return call, "run" if checks(call, text) else "ask"

    def jev_outcome(text, bar, needle_bar):
        """Jev mode: Jev failed -> Phone; "none" at the bar -> no tool; a tool -> Needle 3's call with
        only it, run at the bar when the checks pass (and, with [needle_bar], Needle 3's confidence
        reaches the tool's bar), else asked; Jev below the bar -> asked."""
        d = kept.get(text, {}).get("decide", {})
        if not d.get("success"): return phone_outcome(text)
        p = (d.get("probabilities") or {}).get(d.get("choice"), 0.0)
        if d.get("choice") not in by_name:
            return (None, None) if p >= bar else phone_outcome(text)
        reply = alone.get(text, {})
        call = first(reply)
        if call is None: return None, None
        sure = p >= bar and checks(call, text) and (not needle_bar or (reply.get("confidence") or 0) >= bars.get(call["name"], 0.7))
        return call, "run" if sure else "ask"

    def tally(outcome, subset):
        t = dict(right=0, right_runs=0, wrong_runs=0, right_asks=0, wrong_asks=0, missed=0)
        for request, want_tools, want, _ in subset:
            call, how = outcome(request)
            ok = scored(call, want_tools, want, request)
            t["right"] += ok
            if how == "run": t["right_runs" if ok else "wrong_runs"] += 1
            elif how == "ask": t["right_asks" if ok else "wrong_asks"] += 1
            elif not ok: t["missed"] += 1
        return t

    modes = [("Phone", phone_outcome), ("Computer", computer_outcome)]
    for bar in (0.5, 0.7, 0.9):
        modes.append((f"Jev {bar:.1f}", lambda t, b=bar: jev_outcome(t, b, False)))
        modes.append((f"Jev {bar:.1f} + Needle bar", lambda t, b=bar: jev_outcome(t, b, True)))
    hindi = [l for l in lines if l[3] == "hi"]
    lines = [l for l in lines if l[3] != "hi"]
    scored_texts = [l[0] for l in lines]
    for name, subset in [("without @hi", lines)]:
        print(f"\n== {name} ({len(subset)} requests)")
        print(f"{'':<26}{'right':>7}{'runs ok':>9}{'runs ✗':>8}{'asks ok':>9}{'asks ✗':>8}{'missed':>8}")
        for label, outcome in modes:
            t = tally(outcome, subset)
            print(f"{label:<26}{t['right']:>7}{t['right_runs']:>9}{t['wrong_runs']:>8}{t['right_asks']:>9}{t['wrong_asks']:>8}{t['missed']:>8}")
    t = tally(computer_outcome, hindi)
    print(f"\nComputer on @hi ({len(hindi)} requests): right {t['right']}, runs ok {t['right_runs']}, runs ✗ {t['wrong_runs']}, "
          f"asks ok {t['right_asks']}, asks ✗ {t['wrong_asks']}, missed {t['missed']}")

    # Per category: right runs / wrong runs, for Phone, Computer and Jev 0.7 with Needle 3's bar.
    jev7 = dict(modes)["Jev 0.7 + Needle bar"]
    print(f"\n{'category':<12}{'n':>4}   {'Phone':>9}   {'Computer':>9}   {'Jev 0.7+N':>9}   (right runs / wrong runs)")
    for category in dict.fromkeys(l[3] for l in lines):
        subset = [l for l in lines if l[3] == category]
        cells = [tally(o, subset) for o in (phone_outcome, computer_outcome, jev7)]
        print(f"{category:<12}{len(subset):>4}   " + "   ".join(f"{t['right_runs']:>4} / {t['wrong_runs']:<2}" for t in cells))

    # What Jev mode runs wrongly, and the latencies.
    print()
    for request, want_tools, want, _ in lines:
        call, how = jev7(request)
        if how == "run" and not scored(call, want_tools, want, request):
            d = kept[request]["decide"]
            print(f"wrong run (Jev 0.7 + Needle bar): {request} -> {call['name']}({', '.join(f'{k}={v}' for k, v in (call.get('arguments') or {}).items())}), "
                  f"jev {d.get('choice')} {(d.get('probabilities') or {}).get(d.get('choice'), 0):.2f}")
    jev_ms = sorted(kept[t]["decide"]["ms"] for t in scored_texts if kept.get(t, {}).get("decide", {}).get("success"))
    needle_ms = sorted(a["ms"] for a in alone.values() if a.get("ms"))
    if jev_ms: print(f"\nJev: median {statistics.median(jev_ms):.0f} ms, 90th {jev_ms[int(len(jev_ms) * 0.9)]} ms (n={len(jev_ms)})")
    if needle_ms: print(f"Needle 3 with one tool, on the laptop: median {statistics.median(needle_ms):.0f} ms, 90th {needle_ms[int(len(needle_ms) * 0.9)]} ms")
    print(f"Jev failed on {sum(1 for t in scored_texts if not kept.get(t, {}).get('decide', {}).get('success'))} of {len(scored_texts)}; "
          f"cost of the kept replies ${sum(r.get('decide', {}).get('usd') or 0 for r in kept.values()):.5f}")

if __name__ == "__main__":
    main(sys.argv[1:])
