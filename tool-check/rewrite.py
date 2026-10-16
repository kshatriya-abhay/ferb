"""Gemma for comprehension, Needle 3 for the tool call: the computer's model rewrites a request into
plain English commands (Ferb Link's harness, `rewrite`), and each command goes to Needle 3 on its own.

    rewrite.py --variant v1 [--tuning] [--jev] [--bjev] [--bench audio.txt] [--dry-run] [--limit N] [--replies kept.jsonl ...]

--comprehend: the variant is ferb-link's "comprehend" (Gemma decides: {"direct": true} when the
request is already a clear English command, else its commands, [] for none), scored as U1 (direct
-> B+Jev; commands -> Jev -> Needle 3 each) and U2 (direct -> Jev -> Needle 3 on the request);
a failed comprehend or Jev falls back to B. Needs --bjev and --jev.
--bjev adds B+Jev, Computer with "Use Jev to select the tool" as the app has it: Jev on the request,
then Gemma with Jev's tool and its next ones at 0.25 or more (three in all); "none" no tool; below
0.5 as B. --bench scores another file in requests.txt's format (audio.txt: the heard text of the
audio bench), by its "# @" categories, with October's result from its "october …: OK" comments.

--tuning: requests.txt only (its @hi lines too), so suite.txt stays unseen until the final
variant. Variants are ferb-link's, combined with "+": v1, +shots, +tools, +think, +free.

Arms, on requests.txt (for tuning) and suite.txt (held out), with @hi (Hindi and Hinglish) apart:
  A  Needle 3 alone (routed as the app does);
  B  Gemma calling the tools (Computer, from the kept complete replies);
  C  Gemma's rewrite -> Ferb's keyword routing on each command -> Needle 3;
  E  C, but no tool when Gemma calling the tools (B) calls none: Gemma's tool call as the gate;
  D  (with --jev) Gemma's rewrite -> Jev on each command -> Needle 3 with Jev's tool from 0.5,
     "none" no tool, otherwise as C. Jev costs money: the harness keeps the ledger.
For C and D the usual checks (always asks, the tool's words, values the request says) look at
the rewritten command; an empty list of commands means no tool. Rewrites are kept by request and
variant (results/rewrite-*.jsonl, kind "rewrite"), Jev's picks by command ("decide_command"), and
only what's missing is sent. Take /tmp/ferb-validate.lock: Gemma runs on the laptop's server.
"""
import json, os, re, statistics, sys, time
import route
from jev import bench_lines
from laptop import BUILD, HERE, NEEDLE_SALT, harness, scored
from score import cued, grounded

# Words a command may use that the request needn't say: function words, command verbs, time
# words; the tools' own cue words are added from cues.json.
COMMON = set("""a an the to for of in on at by with and or my me i it this that is be please now
set turn switch open show start stop play pause resume skip next previous call text send message
make create add take get give tell put remind reminder reminders alarm alarms timer timers note notes
list lists volume media music song flashlight torch camera photo settings app apps directions navigate
route agenda calendar event events schedule check tick off up down out from what whats is are do does
am pm minute minutes hour hours second seconds today tomorrow tonight morning afternoon evening night
monday tuesday wednesday thursday friday saturday sunday percent o clock oclock""".split())

def words(text):
    return re.findall(r"[a-z]+|\d+", text.lower().replace("'", ""))

def invented(command, said, vocabulary, numbers_only=False):
    """The command's content words and numbers that neither the request (as said, and as Ferb Link
    sent it, amounts resolved) nor the command vocabulary has: "...in Delhi" for the weather."""
    have = set(words(said))
    return [w for w in words(command) if w not in have and (w.isdigit() or not numbers_only and w not in vocabulary)
            and not (w.isdigit() and any(w in h or h in w for h in have if h.isdigit()))]

# A fixed "now", so runs compare: relative times and dates resolve against it.
NOW = "2026-10-16T09:00 (Friday)"

def main(argv):
    extra, kept_paths, variant, use_jev, tuning, use_bjev, bench_file, comprehend = [], [], None, False, False, False, None, False
    i = 0
    while i < len(argv):
        if argv[i] == "--limit": extra += ["--limit", argv[i + 1]]; i += 2
        elif argv[i] == "--dry-run": extra.append("--dry-run"); i += 1
        elif argv[i] == "--replies": kept_paths.append(argv[i + 1]); i += 2
        elif argv[i] == "--variant": variant = argv[i + 1]; i += 2
        elif argv[i] == "--jev": use_jev = True; i += 1
        elif argv[i] == "--tuning": tuning = True; i += 1
        elif argv[i] == "--bjev": use_bjev = True; i += 1
        elif argv[i] == "--comprehend": comprehend = True; i += 1
        elif argv[i] == "--bench": bench_file = argv[i + 1]; i += 2
        else: sys.exit(f"Unknown option {argv[i]}")
    if not variant: sys.exit("--variant is needed")
    lines = bench_lines()
    october = {}
    if bench_file:
        # Another bench: its lines, categories, and October's result per heard text.
        from score import normalise
        import shlex
        lines, category, mark = [], None, None
        for l in open(os.path.join(HERE, bench_file)):
            l = l.strip()
            if l.startswith("# @"): category = l[3:].strip()
            m = re.search(r"october \([^)]*\): (OK|MISS)", l)
            if m: mark = m[1]
            if not l or l.startswith("#"): continue
            spec, request = [x.strip() for x in l.split("|", 1)]
            tool, *pairs = shlex.split(spec)
            lines.append((request, tool.split("/"), {k: normalise(tool, k, v) for k, v in (p.split("=", 1) for p in pairs)}, category))
            october[(request, len(lines))] = mark
    sources = {}
    for name in ("requests.txt", "suite.txt"):
        from lines import kept as kept_lines
        for l in kept_lines([os.path.join(HERE, name)], set()):
            if "|" in l and not l.startswith("#"): sources[l.split("|", 1)[1].strip()] = name
    if tuning: lines = [l for l in lines if sources.get(l[0]) == "requests.txt"]
    texts = list(dict.fromkeys(l[0] for l in lines))
    tools = json.load(open(os.path.join(BUILD, "all.json")))
    by_name = {t["name"]: t for t in tools}
    cues = json.load(open(os.path.join(BUILD, "cues.json")))
    bars = json.load(open(os.path.join(BUILD, "bars.json")))
    confirm = set(json.load(open(os.path.join(BUILD, "confirm.json"))))

    kept, rewrites, command_picks = {}, {}, {}
    for path in kept_paths:
        for r in (json.loads(l) for l in open(path) if l.strip()):
            if r.get("kind") == "rewrite": rewrites[(r["request"], r["variant"])] = r
            elif r.get("kind") == "decide_command": command_picks[r["request"]] = r
            elif "request" in r: kept.setdefault(r["request"], {})[r["kind"]] = r

    def routed(text):
        return [t for t in tools if cued(t["name"], text, cues) and t["name"] in cues] or tools

    def save(replies, ids):
        path = os.path.join(HERE, "results", time.strftime("rewrite-%Y%m%d-%H%M%S.jsonl"))
        with open(path, "a") as f:
            for rid, r in replies.items():
                kind, key = ids[rid]
                r = {**r, "request": key, "kind": kind, **({"variant": variant} if kind == "rewrite" else {})}
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
                if kind == "rewrite": rewrites[(key, variant)] = r
                elif kind == "decide_command": command_picks[key] = r
                else: kept.setdefault(key, {})[kind] = r
        print(f"Replies kept in {os.path.relpath(path)}")

    def jev_pick(text):
        """B+Jev: Jev's pick on the request at 0.5: [tool, next tools…], ["none"], or None (as B)."""
        d = kept.get(text, {}).get("decide", {})
        p = (d.get("probabilities") or {}).get(d.get("choice"), 0.0) if d.get("success") else 0.0
        if p < 0.5: return None
        if d.get("choice") not in by_name: return ["none"]
        probabilities = d.get("probabilities") or {}
        others = sorted((n for n, q in probabilities.items() if n in by_name and n != d["choice"] and q >= 0.25), key=lambda n: -probabilities[n])
        return [d["choice"]] + others[:2]

    # 0. Gemma calling the tools (B, free), and Jev on each request (B+Jev, costs money).
    ids, send = {}, []
    for n, text in enumerate(texts):
        if "complete" not in kept.get(text, {}):
            ids[f"c{n}"] = ("complete", text)
            send.append({"id": f"c{n}", "request": text, "tools": routed(text), "timeout_ms": 10000})
        if use_bjev and "decide" not in kept.get(text, {}):
            ids[f"d{n}"] = ("decide", text)
            send.append({"id": f"d{n}", "method": "decide", "request": text, "tools": routed(text), "timeout_ms": 10000})
    if send:
        print(f"{sum(1 for x in send if x.get('method'))} decide and {sum(1 for x in send if not x.get('method'))} complete lines to send")
        replies = harness(send, extra)
        if replies is None: return
        save(replies, ids)
    if use_bjev:
        ids, send = {}, []
        for n, text in enumerate(texts):
            names = jev_pick(text)
            if not names or names == ["none"]: continue
            kind = "complete_jev3" if len(names) > 1 else "complete_jev"
            if kind not in kept.get(text, {}):
                ids[f"g{n}"] = (kind, text)
                send.append({"id": f"g{n}", "request": text, "tools": [by_name[x] for x in names], "timeout_ms": 10000})
        if send:
            print(f"{len(send)} complete lines with Jev's tools to send")
            replies = harness(send, extra)
            if replies is None: return
            save(replies, ids)

    # 1. Gemma's rewrites (free, under the lock).
    ids, send = {}, []
    for n, text in enumerate(texts):
        if (text, variant) not in rewrites:
            ids[f"r{n}"] = ("rewrite", text)
            send.append({"id": f"r{n}", "method": "comprehend" if comprehend else "rewrite", "request": text, "now": NOW,
                         "variant": variant, "tools": [t["name"] for t in tools]})
    print(f"{len(texts)} requests; {len(send)} rewrites to send (variant {variant})")
    if send:
        replies = harness(send, extra)
        if replies is None: return
        save(replies, ids)

    def direct(text):
        """Comprehend: Gemma says the request is already a clear English command."""
        return bool(rewrites.get((text, variant), {}).get("direct"))

    def commands(text):
        r = rewrites.get((text, variant), {})
        if not r.get("success") or direct(text): return None
        return [c.strip() for c in (r.get("commands") or []) if c and c.strip()]

    # 2. Jev on each command (arm D; costs money).
    all_commands = sorted({c for t in texts for c in (commands(t) or [])})
    if use_jev:
        ids, send = {}, []
        for n, c in enumerate(all_commands):
            if c not in command_picks:
                ids[f"j{n}"] = ("decide_command", c)
                send.append({"id": f"j{n}", "method": "decide", "request": c, "tools": routed(c), "timeout_ms": 10000})
        print(f"{len(all_commands)} commands; {len(send)} decide calls to send")
        if send:
            if "--dry-run" in extra and not send: return
            replies = harness(send, extra)
            if replies is None: return
            save(replies, ids)

    # 3. Needle 3: the requests routed (A), each command routed (C), each command with Jev's tool (D).
    engine = os.path.join(HERE, "..", "build", "tool-check", "tool_check")
    model = os.path.join(HERE, "..", "app", "src", "main", "assets", "needle3.cact")
    ask = route.Cache(os.path.join(HERE, "results", "needle3.jsonl"), NEEDLE_SALT).wrap(lambda t, asked: route.run(engine, model, t, asked))
    phone = {r: json.loads(a) for r, a in route.answer(ask, tools, cues, texts)[0].items()}
    on_commands = {r: json.loads(a) for r, a in route.answer(ask, tools, cues, all_commands)[0].items()} if all_commands else {}
    def jev_tool(c):
        d = command_picks.get(c, {})
        p = (d.get("probabilities") or {}).get(d.get("choice"), 0.0) if d.get("success") else 0.0
        if p < 0.5: return None
        return d["choice"] if d.get("choice") in by_name else "none"
    alone = {}
    if use_jev:
        groups = {}
        for c in all_commands:
            t = jev_tool(c)
            if t and t != "none": groups.setdefault(t, []).append(c)
        for name, asked in groups.items():
            for r, a in ask([by_name[name]], asked).items(): alone[r] = json.loads(a)

    def first(reply):
        return next(iter(reply.get("function_calls") or []), None)

    # U2: Needle 3 with Jev's tool on the request itself, for the requests comprehend calls direct.
    alone_request = {}
    if comprehend:
        groups = {}
        for t in texts:
            names = jev_pick(t)
            if direct(t) and names and names != ["none"]: groups.setdefault(names[0], []).append(t)
        for name, asked in groups.items():
            for r, a in ask([by_name[name]], asked).items(): alone_request[r] = json.loads(a)

    def needle_how(reply, said):
        """A Needle 3 reply's call and whether it runs, with the checks against [said]."""
        call = first(reply)
        if call is None: return None
        runs = (call["name"] not in confirm and cued(call["name"], said, cues) and grounded(call, said)
                and (reply.get("confidence") or 0) >= max(bars.get(c["name"], 0.7) for c in reply["function_calls"]))
        return call, said, "run" if runs else "ask"

    # Each arm: a request -> its calls, as (call, the text its values are read from, run or ask).
    def arm_a(text): return [x for x in [needle_how(phone[text], text)] if x]
    def arm_b(text):
        call = first(kept.get(text, {}).get("complete", {}))
        if call is None: return []
        return [(call, text, "run" if call["name"] not in confirm and cued(call["name"], text, cues) and grounded(call, text) else "ask")]
    def arm_c(text):
        cs = commands(text)
        if cs is None: return arm_a(text)  # the rewrite failed: as Needle 3 alone
        return [x for c in cs for x in [needle_how(on_commands[c], c)] if x]
    def arm_bj(text):
        names = jev_pick(text)
        if names is None: return arm_b(text)
        if names == ["none"]: return []
        reply = kept.get(text, {}).get("complete_jev3" if len(names) > 1 else "complete_jev", {})
        call = first(reply)
        if call is None: return []
        return [(call, text, "run" if all(c["name"] not in confirm and cued(c["name"], text, cues) and grounded(c, text)
                                          for c in reply["function_calls"]) else "ask")]
    def arm_e(text):
        """C, gated by Gemma's own tool call (B): when Gemma, given the tools, calls none, no tool."""
        return arm_c(text) if arm_b(text) else []
    def commands_with_jev(text):
        """Each command to Jev and Needle 3 with Jev's tool; None when Jev fails on one (the app then goes to B)."""
        out = []
        for c in commands(text):
            t = jev_tool(c)
            if t is None: return None
            if t != "none":
                got = needle_how(alone.get(c, {}), c)
                if got: out.append(got)
        return out
    def arm_u1(text):
        if direct(text): return arm_bj(text)
        if commands(text) is None: return arm_b(text)
        got = commands_with_jev(text)
        return arm_b(text) if got is None else got
    def arm_u2(text):
        if direct(text):
            names = jev_pick(text)
            if names is None: return arm_b(text)
            if names == ["none"]: return []
            got = needle_how(alone_request.get(text, {}), text)
            return [got] if got else []
        return arm_u1(text)
    def arm_d(text):
        cs = commands(text)
        if cs is None: return arm_a(text)
        out = []
        for c in cs:
            t = jev_tool(c)
            got = needle_how(on_commands[c], c) if t is None else (None if t == "none" else needle_how(alone.get(c, {}), c))
            if got: out.append(got)
        return out

    def tally(arm, subset):
        t = dict(right=0, right_runs=0, wrong_runs=0, right_asks=0, wrong_asks=0, missed=0)
        for request, want_tools, want, category in subset:
            got = arm(request)
            ok = [g for g in got if scored(g[0], want_tools, want, g[1])]
            if want_tools == ["none"]:
                t["right"] += not got
                t["wrong_runs"] += any(h == "run" for _, _, h in got)
                t["wrong_asks"] += bool(got) and not any(h == "run" for _, _, h in got)
                continue
            # A request for two things: its other action isn't wrong; elsewhere any other call that runs is.
            others = [g for g in got if g not in ok and (category != "multi" or not ok)]
            if ok:
                t["right"] += 1
                t["right_runs" if ok[0][2] == "run" else "right_asks"] += 1
            elif not got:
                t["missed"] += 1
            t["wrong_runs"] += any(h == "run" for _, _, h in others)
            t["wrong_asks"] += not ok and bool(others) and not any(h == "run" for _, _, h in others)
        return t

    if comprehend:
        arms = [("A Needle 3", arm_a), ("B Gemma calls", arm_b), ("B+Jev", arm_bj), (f"U1 {variant}", arm_u1), (f"U2 {variant}", arm_u2)]
    else:
        arms = [("A Needle 3", arm_a), ("B Gemma calls", arm_b)] + ([("B+Jev", arm_bj)] if use_bjev else []) + [(f"C rewrite {variant}", arm_c), (f"E C gated by B", arm_e)] + ([(f"D rewrite {variant} + Jev", arm_d)] if use_jev else [])
    if bench_file:
        def oct_tally(subset):
            marks = [october.get((l[0], i + 1)) for i, l in enumerate(lines) if l in subset]
            return f"{marks.count('OK')}/{len(marks)}"
        print(f"\n== {bench_file}: {len(lines)} lines; October's voice path (transcribe's text, then Computer) {oct_tally(lines)} right")
        print(f"{'':<24}{'right':>7}{'runs ok':>9}{'runs ✗':>8}{'asks ok':>9}{'asks ✗':>8}{'missed':>8}")
        for label, arm in arms:
            t = tally(arm, lines)
            print(f"{label:<24}{t['right']:>7}{t['right_runs']:>9}{t['wrong_runs']:>8}{t['right_asks']:>9}{t['wrong_asks']:>8}{t['missed']:>8}")
        print(f"\nBy category, right (right runs / wrong runs): October, " + ", ".join(label.split()[0] for label, _ in arms))
        for category in dict.fromkeys(l[3] for l in lines):
            subset = [l for l in lines if l[3] == category]
            print(f"  {category:<9}{len(subset):>3}  oct {oct_tally(subset):>5}  " + "  ".join(f"{t['right']:>2} ({t['right_runs']}/{t['wrong_runs']})" for t in (tally(a, subset) for _, a in arms)))
        print("\nPer line (✓ right): " + " ".join(label.split()[0] for label, _ in arms))
        for i, (request, want_tools, want, category) in enumerate(lines):
            marks = []
            for _, arm in arms:
                got = arm(request)
                ok = (not got) if want_tools == ["none"] else any(scored(g[0], want_tools, want, g[1]) for g in got)
                marks.append(("✓" if ok else "✗") + ("r" if any(h == "run" for _, _, h in got) else " "))
            print(f"  {category:<9} oct {october.get((request, i + 1)) or '-':<4} {' '.join(marks)}  {request[:44]:<44} -> {commands(request)}")
        if comprehend:
            print("\nComprehend said direct, by category:")
            for category in dict.fromkeys(l[3] for l in lines):
                sub = [l[0] for l in lines if l[3] == category]
                print(f"  {category:<10} {sum(map(direct, sub)):>3}/{len(sub)}")
        return
    if tuning: lines = [l for l in lines]
    sets = [("requests.txt (tuning)", [l for l in lines if sources.get(l[0]) == "requests.txt" and l[3] != "hi"]),
            ("suite.txt (held out)", [l for l in lines if sources.get(l[0]) == "suite.txt" and l[3] != "hi"]),
            ("@hi" + (" (requests.txt)" if tuning else " (both files)"), [l for l in lines if l[3] == "hi"])]
    if tuning: sets = [sets[0], sets[2]]
    for name, subset in sets:
        print(f"\n== {name}: {len(subset)} requests")
        print(f"{'':<24}{'right':>7}{'runs ok':>9}{'runs ✗':>8}{'asks ok':>9}{'asks ✗':>8}{'missed':>8}")
        for label, arm in arms:
            if name.startswith("@hi") and arm is arm_a: continue
            t = tally(arm, subset)
            print(f"{label:<24}{t['right']:>7}{t['right_runs']:>9}{t['wrong_runs']:>8}{t['right_asks']:>9}{t['wrong_asks']:>8}{t['missed']:>8}")
    # SHOW=<arm label start>: that arm's misses on the first set, to read.
    show = os.environ.get("SHOW")
    if show:
        arm = next(a for label, a in arms if label.startswith(show))
        print(f"\n{show} misses on {sets[0][0]}:")
        for request, want_tools, want, _ in sets[0][1]:
            got = arm(request)
            ok = (not got) if want_tools == ["none"] else any(scored(g[0], want_tools, want, g[1]) for g in got)
            if not ok:
                r = rewrites.get((request, variant), {})
                how = "direct" if r.get("direct") else r.get("commands")
                print(f"  {'/'.join(want_tools):<14} {request[:46]:<46} {str(how)[:50]:<50} -> {[(g[0]['name'], g[2]) for g in got]}")
    held = [] if tuning else sets[1][1]
    if held: print(f"\nsuite.txt by category, right runs / wrong runs: " + ", ".join(label.split()[0] for label, _ in arms))
    for category in dict.fromkeys(l[3] for l in held):
        subset = [l for l in held if l[3] == category]
        print(f"  {category:<10}{len(subset):>4}  " + "   ".join(f"{t['right_runs']:>3}/{t['wrong_runs']:<2}" for t in (tally(a, subset) for _, a in arms)))

    # What the rewrite did with the requests that ask for nothing, and to the Hinglish ones.
    print("\nRequests for no tool that C's rewrite turned into commands:")
    for request, want_tools, _, category in lines:
        cs = commands(request)
        if want_tools == ["none"] and cs:
            print(f"  [{category}] {request}  ->  {cs}  ->  {[(g[0]['name'], g[2]) for g in arm_c(request)]}")
    print("\n@hi rewrites:")
    for request, want_tools, want, _ in sets[-1][1]:
        got = arm_c(request)
        print(f"  {request}  ->  {commands(request)}  ->  {[(g[0]['name'], g[0].get('arguments'), g[2]) for g in got]}  {'OK' if any(scored(g[0], want_tools, want, g[1]) for g in got) or (want_tools == ['none'] and not got) else 'MISS'}")

    ms = sorted(r["ms"] for (t, v), r in rewrites.items() if v == variant and r.get("success") and r.get("ms"))
    if ms: print(f"\nRewrite: median {statistics.median(ms):.0f} ms, 90th {ms[int(len(ms) * 0.9)]} ms; failed {sum(1 for t in texts if not rewrites.get((t, variant), {}).get('success'))}")
    needle_ms = sorted(on_commands[c]["ms"] for c in all_commands if on_commands[c].get("ms"))
    if needle_ms: print(f"Needle 3 per command, on the laptop: median {statistics.median(needle_ms):.0f} ms")
    if use_jev:
        jms = sorted(command_picks[c]["ms"] for c in all_commands if command_picks.get(c, {}).get("success"))
        if jms: print(f"Jev per command: median {statistics.median(jms):.0f} ms; cost ${sum(command_picks.get(c, {}).get('usd') or 0 for c in all_commands):.5f}")
    # Invented: commands with a word or number the request doesn't have. Hindi and Hinglish are
    # translated, so only their numbers count.
    vocabulary = COMMON | {w for pattern in cues.values() for w in re.findall(r"[a-z]+", pattern)}
    hindi = {l[0] for l in lines if l[3] == "hi"}
    found = []
    for t in texts:
        said = t + " " + (rewrites.get((t, variant), {}).get("sent") or "")
        for c in commands(t) or []:
            new = invented(c, said, vocabulary, numbers_only=t in hindi)
            if new: found.append((t, c, new))
    # The metric: Ferb Link's own "invented" field (numbers and names the request lacks), one
    # definition for the phone and the bench; the wider word check above is for reading.
    flagged = [(t, rewrites[(t, variant)]["invented"]) for t in texts if (rewrites.get((t, variant)) or {}).get("invented")]
    print(f"\nInvented (Ferb Link's field): {len(flagged)} requests")
    for t, new in flagged: print(f"  {t}  ->  {commands(t)}   [{', '.join(map(str, new))}]")
    print(f"Words the request lacks (a wider check, to read): {len(found)} commands")
    for t, c, new in found: print(f"  {t}  ->  {c}   [{', '.join(new)}]")
    if comprehend:
        print("\nComprehend said direct, by category:")
        for category in dict.fromkeys(l[3] for l in lines):
            sub = [l[0] for l in lines if l[3] == category]
            print(f"  {category:<10} {sum(map(direct, sub)):>3}/{len(sub):<3}  commands {sum(1 for t in sub if commands(t))}, none {sum(1 for t in sub if commands(t) == [])}")
    counts = [len(commands(t) or []) for t in texts]
    print(f"Commands per request: none {counts.count(0)}, one {counts.count(1)}, more {sum(1 for c in counts if c > 1)}")

if __name__ == "__main__":
    main(sys.argv[1:])
