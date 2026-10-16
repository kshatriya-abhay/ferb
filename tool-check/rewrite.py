"""Gemma for comprehension, Needle 3 for the tool call: the computer's model rewrites a request into
plain English commands (Ferb Link's harness, `rewrite`), and each command goes to Needle 3 on its own.

    rewrite.py --variant v1 [--tuning] [--jev] [--dry-run] [--limit N] [--replies kept.jsonl ...]

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
    extra, kept_paths, variant, use_jev, tuning = [], [], None, False, False
    i = 0
    while i < len(argv):
        if argv[i] == "--limit": extra += ["--limit", argv[i + 1]]; i += 2
        elif argv[i] == "--dry-run": extra.append("--dry-run"); i += 1
        elif argv[i] == "--replies": kept_paths.append(argv[i + 1]); i += 2
        elif argv[i] == "--variant": variant = argv[i + 1]; i += 2
        elif argv[i] == "--jev": use_jev = True; i += 1
        elif argv[i] == "--tuning": tuning = True; i += 1
        else: sys.exit(f"Unknown option {argv[i]}")
    if not variant: sys.exit("--variant is needed")
    lines = bench_lines()
    sources = {}
    for name in ("requests.txt", "suite.txt"):
        from lines import kept as kept_lines
        for l in kept_lines([os.path.join(HERE, name)], set()):
            if "|" in l and not l.startswith("#"): sources[l.split("|", 1)[1].strip()] = name
    if tuning: lines = [l for l in lines if sources.get(l[0]) == "requests.txt"]
    texts = [l[0] for l in lines]
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
                else: command_picks[key] = r
        print(f"Replies kept in {os.path.relpath(path)}")

    # 1. Gemma's rewrites (free, under the lock).
    ids, send = {}, []
    for n, text in enumerate(texts):
        if (text, variant) not in rewrites:
            ids[f"r{n}"] = ("rewrite", text)
            send.append({"id": f"r{n}", "method": "rewrite", "request": text, "now": NOW, "variant": variant,
                         "tools": [t["name"] for t in tools]})
    print(f"{len(texts)} requests; {len(send)} rewrites to send (variant {variant})")
    if send:
        replies = harness(send, extra)
        if replies is None: return
        save(replies, ids)

    def commands(text):
        r = rewrites.get((text, variant), {})
        return [c.strip() for c in (r.get("commands") or []) if c and c.strip()] if r.get("success") else None

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
    def arm_e(text):
        """C, gated by Gemma's own tool call (B): when Gemma, given the tools, calls none, no tool."""
        return arm_c(text) if arm_b(text) else []
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

    arms = [("A Needle 3", arm_a), ("B Gemma calls", arm_b), (f"C rewrite {variant}", arm_c), (f"E C gated by B", arm_e)] + ([(f"D rewrite {variant} + Jev", arm_d)] if use_jev else [])
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
    if ms: print(f"\nRewrite: median {statistics.median(ms):.0f} ms, 90th {ms[int(len(ms) * 0.9)]} ms; failed {sum(1 for t in texts if commands(t) is None)}")
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
    counts = [len(commands(t) or []) for t in texts]
    print(f"Commands per request: none {counts.count(0)}, one {counts.count(1)}, more {sum(1 for c in counts if c > 1)}")

if __name__ == "__main__":
    main(sys.argv[1:])
