"""Runs requests the way Ferb does with Computer picked, on the laptop, with no phone: Ferb's
keyword routing (tools the request mentions, else every tool), then Ferb Link's own code against
the laptop's model server (ferb-link's `mise run companion:harness`), then the calls read as
Ferb reads them (score.py's copies of its time and length readers), scored against a bench file.

    laptop.py bench.txt [texts.txt]

A model evaluation, for tuning the tool config (keywords, schemas) against the computer's model
as Ferb uses it; not part of `mise run test`. bench.txt is in requests.txt's format (requests.txt,
suite.txt, or a short list for the change at hand). Without texts.txt each
request is sent as written (typed); with it, its lines ("NN | text", NN the bench line, or plain
text in order) are sent instead, e.g. what transcribe returned for each clip. Take
/tmp/ferb-validate.lock first: the model server runs one thing at a time.

    laptop.py --decide bench.txt [--limit N] [--dry-run] [--replies saved.jsonl]

Jev (TypeSafe's cloud model, through Ferb Link's decide) as Ferb's Fallback mode would use it: a
second opinion only where Needle 3 is unsure (no call, or below its tool's bar), checked against
the computer's call when it answered and Needle 3's when it didn't. Each Jev call costs money:
Ferb Link's harness keeps the ledger and its cap, and --limit and --dry-run pass through to it.
The replies are kept in tool-check/results/jev-*.jsonl, and --replies scores a kept run again
without sending anything. Needle 3's answers come from tool-check/results/needle3.jsonl (`mise
run bench needle3` keeps them; any missing one is run here, on the laptop, free).
"""
import json, os, re, shlex, subprocess, sys, tempfile, time
from score import arguments, normalise, cued, grounded

HERE = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(HERE, "..", "app", "build", "tool-check")
# Ferb Link's repo, next to this one unless FERB_LINK says otherwise.
# route.py's cache key for Needle 3 (the model file's SHA-256), as `mise run bench needle3` sets it.
NEEDLE_SALT = "c9d915eca282ed42d1a09b143b592adb4cc6744ffe2d294adf5cfc5548170c38"
FERB_LINK = os.environ.get("FERB_LINK", os.path.join(HERE, "..", "..", "ferb-link"))

def bench(path):
    out = []
    for line in open(path):
        line = line.strip()
        if not line or line.startswith("#"): continue
        spec, request = [x.strip() for x in line.split("|", 1)]
        name, *pairs = shlex.split(spec)
        out.append((request, name.split("/"), {k: normalise(name, k, v) for k, v in (p.split("=", 1) for p in pairs)}))
    return out

def scored(call, want_tools, want, text):
    """Whether [call] (or no call) is the bench line's right answer."""
    if want_tools == ["none"]: return call is None
    if call is None or call["name"] not in want_tools: return False
    args = arguments(call, text)
    return all(args.get(k) == v for k, v in want.items())

def harness(lines, extra=()):
    """Ferb Link's replies to [lines] (dicts), by id; with --dry-run, None, after printing the harness's estimate."""
    with tempfile.TemporaryDirectory() as tmp:
        src, dst = os.path.join(tmp, "in.jsonl"), os.path.join(tmp, "out.jsonl")
        with open(src, "w") as f:
            for line in lines: f.write(json.dumps(line, ensure_ascii=False) + "\n")
        dry = "--dry-run" in extra
        subprocess.run(["mise", "run", "companion:harness", src, dst, *extra], cwd=FERB_LINK, check=True,
                       stdout=None if dry else subprocess.DEVNULL, stderr=None if dry else subprocess.DEVNULL)
        if dry:  # the harness writes only its estimate
            print(open(dst).read().strip())
            return None
        return {r["id"]: r for r in (json.loads(l) for l in open(dst) if l.strip())}

def decide(argv):
    path = argv[0]
    extra, saved = [], None
    i = 1
    while i < len(argv):
        if argv[i] == "--limit": extra += ["--limit", argv[i + 1]]; i += 2
        elif argv[i] == "--dry-run": extra.append("--dry-run"); i += 1
        elif argv[i] == "--replies": saved = argv[i + 1]; i += 2
        else: sys.exit(f"Unknown option {argv[i]}")
    import route
    expected = bench(path)
    tools = json.load(open(os.path.join(BUILD, "all.json")))
    cues = json.load(open(os.path.join(BUILD, "cues.json")))
    bars = json.load(open(os.path.join(BUILD, "bars.json")))
    confirm = set(json.load(open(os.path.join(BUILD, "confirm.json"))))
    texts = [r for r, _, _ in expected]
    # Needle 3 as `mise run bench needle3` runs it (the same cache and salt), routed as the app does.
    engine, model = os.path.join(HERE, "..", "build", "tool-check", "tool_check"), os.path.join(HERE, "..", "app", "src", "main", "assets", "needle3.cact")
    ask = route.Cache(os.path.join(HERE, "results", "needle3.jsonl"), NEEDLE_SALT).wrap(lambda t, asked: route.run(engine, model, t, asked))
    needle = {r: json.loads(a) for r, a in route.answer(ask, tools, cues, texts)[0].items()}

    def checks_pass(call, text):
        """Ferb's checks that make a call ask whatever its confidence: always asks, unmentioned, ungrounded."""
        return call["name"] not in confirm and cued(call["name"], text, cues) and grounded(call, text)

    def unsure(reply):
        calls = reply.get("function_calls") or []
        return not calls or (reply.get("confidence") or 0) < max(bars.get(c["name"], 0.7) for c in calls)

    asked = [n for n, t in enumerate(texts) if unsure(needle[t])]
    lines = []
    for n in asked:
        text = texts[n]
        routed = [t for t in tools if cued(t["name"], text, cues) and t["name"] in cues] or tools
        lines.append({"id": f"c{n}", "request": text, "tools": routed, "timeout_ms": 10000})
        lines.append({"id": f"d{n}", "method": "decide", "request": text, "tools": routed, "timeout_ms": 10000})
    print(f"{len(texts)} requests; Needle 3 unsure on {len(asked)}, so {len(asked)} decide calls")
    if saved:
        replies = {r["id"]: r for r in (json.loads(l) for l in open(saved) if l.strip())}
    else:
        replies = harness(lines, extra)
        if replies is None: return
        keep = os.path.join(HERE, "results", time.strftime("jev-%Y%m%d-%H%M%S.jsonl"))
        with open(keep, "w") as f:
            for r in replies.values(): f.write(json.dumps(r, ensure_ascii=False) + "\n")
        print(f"Replies kept in {os.path.relpath(keep)}")

    # Per asked line: who answers (computer or phone), its call, whether that's right, and Jev.
    rows = []
    for n in asked:
        text, (_, want_tools, want) = texts[n], expected[n]
        gemma, jev = replies.get(f"c{n}", {}), replies.get(f"d{n}", {})
        g_call = next(iter(gemma.get("function_calls") or []), None)
        n_reply = needle[text]
        n_call = next(iter(n_reply.get("function_calls") or []), None)
        p = (jev.get("probabilities") or {}).get(jev.get("choice"), 0.0) if jev.get("success") else None
        rows.append(dict(n=n, text=text, want=want_tools, jev=jev, p=p,
                         computer=(g_call, scored(g_call, want_tools, want, text)),
                         phone=(n_call, scored(n_call, want_tools, want, text))))

    def today(source, call, text):
        """Run or ask without Jev: the computer's call runs unless the checks stop it; Needle 3's
        unsure one asks."""
        if call is None: return None
        return "run" if source == "computer" and checks_pass(call, text) else "ask"

    def with_jev(source, call, text, row, bar):
        if call is None: return None
        if not checks_pass(call, text): return "ask"
        p, choice = row["p"], row["jev"].get("choice")
        if p is None or p < bar: return today(source, call, text)
        return "run" if choice == call["name"] else "ask"

    for row in rows:
        jev = row["jev"]
        say = (f"{jev.get('choice')} {row['p']:.2f}" if row["p"] is not None
               else jev.get("skipped") or jev.get("error_kind") or "no reply")
        g_call, g_ok = row["computer"]
        n_call, n_ok = row["phone"]
        name = lambda c: c["name"] if c else "no tool"
        print(f"{'/'.join(row['want']):<16} {row['text'][:48]:<48}  computer {name(g_call)}{'' if g_ok else ' ✗'}"
              f"  phone {name(n_call)}{'' if n_ok else ' ✗'}  jev {say}  [{', '.join(jev.get('options') or [])}]")

    answered = [r for r in rows if r["p"] is not None]
    print(f"\nJev answered {len(answered)}/{len(rows)}; ms median "
          f"{sorted(r['jev'].get('ms', 0) for r in answered)[len(answered) // 2] if answered else '-'}; "
          f"cost ${sum(r['jev'].get('usd') or 0 for r in rows):.5f}")
    jev_right = lambda r: r["jev"].get("choice") in r["want"]
    print(f"Jev right {sum(map(jev_right, answered))}, wrong {sum(not jev_right(r) for r in answered)}")
    print(f"  p when right: {' '.join(f'{r['p']:.2f}' for r in sorted(answered, key=lambda r: r['p']) if jev_right(r))}")
    print(f"  p when wrong: {' '.join(f'{r['p']:.2f}' for r in sorted(answered, key=lambda r: r['p']) if not jev_right(r))}")
    # First-option bias: how often Jev picks the option it was asked first, against how often that one is right.
    firsts = [r for r in answered if r["jev"].get("options")]
    if firsts:
        print(f"  picked the first option {sum(r['jev']['choice'] == r['jev']['options'][0] for r in firsts)}/{len(firsts)}; "
              f"the first option was right {sum(r['jev']['options'][0] in r['want'] for r in firsts)}/{len(firsts)}")

    print(f"\n{'':<22}{'right runs':>11}{'wrong runs':>11}{'right asks':>11}")
    for source in ("computer", "phone"):
        def tally(decide_row):
            rr = wr = ra = 0
            for r in rows:
                call, ok = r[source][0], r[source][1]
                d = decide_row(r, call)
                rr += d == "run" and ok; wr += d == "run" and not ok; ra += d == "ask" and ok
            return rr, wr, ra
        label = "computer answered" if source == "computer" else "computer off"
        print(f"{label + ', today':<22}" + "".join(f"{x:>11}" for x in tally(lambda r, c: today(source, c, r["text"]))))
        for bar in (0.5, 0.6, 0.7, 0.8, 0.9, 0.95):
            print(f"{f'  Jev bar {bar:.2f}':<22}" + "".join(f"{x:>11}" for x in tally(lambda r, c, b=bar: with_jev(source, c, r["text"], r, b))))
    # What still runs wrongly with Jev (at JEV_BAR, 0.7 unless set): a wrong tool Jev agreed
    # with, or the right tool with wrong values, which Jev can't see (it never sees arguments).
    bar = float(os.environ.get("JEV_BAR", "0.7"))
    for source in ("computer", "phone"):
        for r in rows:
            call, ok = r[source]
            if with_jev(source, call, r["text"], r, bar) == "run" and not ok:
                kind = "wrong values" if call["name"] in r["want"] else "wrong tool"
                print(f"wrong run at {bar:.2f} ({'computer answered' if source == 'computer' else 'computer off'}, {kind}): "
                      f"{r['text']} -> {call['name']}({', '.join(f'{k}={v}' for k, v in (call.get('arguments') or {}).items())}), "
                      f"jev {r['jev'].get('choice')} {r['p']:.2f}")

def main():
    if sys.argv[1:2] == ["--decide"]: return decide(sys.argv[2:])
    expected = bench(sys.argv[1])
    if len(sys.argv) > 2:
        sends = []
        for i, l in enumerate(x.strip() for x in open(sys.argv[2]) if x.strip() and not x.startswith("#")):
            m = re.match(r"^(\d+)\s*\|\s*(.*)$", l)
            sends.append((int(m[1]) - 1, m[2]) if m else (i, l))
    else:
        sends = [(i, r) for i, (r, _, _) in enumerate(expected)]
    tools = json.load(open(os.path.join(BUILD, "all.json")))
    cues = json.load(open(os.path.join(BUILD, "cues.json")))
    with tempfile.TemporaryDirectory() as tmp:
        src, dst = os.path.join(tmp, "in.jsonl"), os.path.join(tmp, "out.jsonl")
        with open(src, "w") as f:
            for n, (i, text) in enumerate(sends):
                routed = [t for t in tools if cued(t["name"], text, cues) and t["name"] in cues]
                f.write(json.dumps({"id": str(n), "request": text, "tools": routed or tools, "timeout_ms": 10000}) + "\n")
        subprocess.run(["mise", "run", "companion:harness", src, dst], cwd=FERB_LINK, check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        replies = {r["id"]: r for r in (json.loads(l) for l in open(dst) if l.strip())}
    right = 0
    for n, (i, text) in enumerate(sends):
        request, want_tools, want = expected[i]
        reply = replies.get(str(n), {})
        calls = reply.get("function_calls") or []
        if want_tools == ["none"]:
            ok = not calls
        else:
            match = next((c for c in calls if c["name"] in want_tools), None)
            args = arguments(match, text) if match else {}
            ok = match is not None and all(args.get(k) == v for k, v in want.items())
        right += ok
        got = "; ".join(f"{c['name']}({', '.join(f'{k}={v}' for k, v in (c.get('arguments') or {}).items())})" for c in calls) or reply.get("harness_error") or reply.get("error") or "no tool"
        read = ""
        if calls and calls[0]["name"] in ("set_alarm", "set_timer"):
            read = "  -> " + json.dumps(arguments(calls[0], text))
        sent = reply.get("sent")
        print(f"{'OK  ' if ok else 'MISS'} {i + 1:02d} {text}" + (f"  [sent: {sent}]" if sent and sent != text else "") + f"\n     {got}{read}  ({reply.get('ms', '-')} ms)")
    print(f"\n{right}/{len(sends)} right")

main()
