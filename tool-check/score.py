"""Scores Needle 3's answers to tool-check/requests.txt: the tool and the arguments.

Reads "request<TAB>engine JSON" lines (tool_check with TOOL_CHECK_RAW=1) on stdin. Each request
line in requests.txt is "tool[ key=value ...] | request": "none" expects no call, "a/b" either tool
(the first one loaded), and key=value pairs the arguments that matter (quoted if they have spaces). Values are compared the way
the app reads them: times of day as HH:MM, durations as seconds (from a "seconds" number or a
"duration" text), app and page names case-insensitively.
"""
import json, os, re, shlex, sys

def time_of_day(text):
    t = str(text).strip().lower()
    t = {"noon": "12", "midnight": "0"}.get(t, t)
    m = re.fullmatch(r"(\d{1,2})(?:\s*[:.h]\s*(\d{2}))?\s*(am|a\.m\.|pm|p\.m\.)?", t)
    if not m: return None
    h, mi, p = int(m[1]), int(m[2] or 0), (m[3] or "").replace(".", "")
    if p == "am" and h == 12: h = 0
    if p == "pm" and h != 12: h += 12
    return f"{h:02d}:{mi:02d}" if h < 24 and mi < 60 else None

UNITS = {"h": 3600, "hr": 3600, "hrs": 3600, "hour": 3600, "hours": 3600, "m": 60, "min": 60, "mins": 60,
         "minute": 60, "minutes": 60, "s": 1, "sec": 1, "secs": 1, "second": 1, "seconds": 1}

# As in the app's SaidNumbers.
WORDS = {"a": 1, "an": 1, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8,
         "nine": 9, "ten": 10, "eleven": 11, "twelve": 12, "fifteen": 15, "twenty": 20, "thirty": 30, "forty": 40,
         "fifty": 50, "sixty": 60, "ninety": 90}
CLOCK_WORDS = {"half": 30, "quarter": 15, "noon": 12, "midnight": 12}

def said_numbers(text):
    return {int(w) if w.isdigit() else WORDS.get(w, CLOCK_WORDS.get(w)) for w in re.findall(r"\d+|[a-z]+", str(text).lower())} - {None}

# The tools whose argument must use numbers the request says (the app's Tool.grounded), else they ask first.
GROUNDED = {"set_alarm": "time", "set_timer": "duration"}

# Requests naming a volume the media-only volume tool doesn't change (the app's SetVolume.grounded).
OTHER_VOLUMES = r"\b(ring(er|tone)?|alarms?|notifications?|calls?)\b"

def note_words(text):
    return set(re.findall(r"\w+", str(text).lower()))

# A day as the app's When.day reads one, roughly: show_agenda's day must be one, else it asks first.
DAY = (r"\b(today|tonight|tomorrow|yesterday|mon|monday|tue|tues|tuesday|wednesday|thu|thurs|thursday|fri|friday|saturday|sunday|"
       r"jan|january|feb|february|mar|march|apr|april|may|jun|june|jul|july|aug|august|sep|sept|september|oct|october|nov|november|dec|december)\b|\b\d{1,2}(st|nd|rd|th)?\b|\d/\d")

def grounded(call, request):
    if call["name"] == "show_agenda":
        day = (call.get("arguments") or {}).get("day")
        return day is None or re.search(DAY, str(day).lower()) is not None
    if call["name"] == "set_volume": return re.search(OTHER_VOLUMES, request, re.IGNORECASE) is None
    said = {"create_note": ["text"], "get_directions": ["destination"], "make_call": ["name"], "send_text": ["name", "message"],
            # add_event's "when" is only checked when the request says no time; the app reads it from the request.
            "add_event": ["title"]}.get(call["name"])
    if said:  # the app's Tools.mostlySaid: at least half each argument's words are the request's
        for key in said:
            words = note_words((call.get("arguments") or {}).get(key, ""))
            if words and 2 * len(words & note_words(request)) < len(words): return False
        return True
    key = GROUNDED.get(call["name"])
    value = (call.get("arguments") or {}).get(key) if key else None
    if value is None: return True
    said = said_numbers(request)
    return all(n == 0 or n in said or (13 <= n <= 23 and n - 12 in said) for n in said_numbers(value))

def duration(text):
    """'2 hours', '1 hour 15 minutes', '90 seconds', '45 min', '1:30', 'one minute' -> seconds."""
    t = str(text).strip().lower().replace(" and ", " ").replace("half an hour", "30 minutes")
    t = re.sub(r"\b(" + "|".join(WORDS) + r")\b", lambda m: str(WORDS[m[1]]), t)
    m = re.fullmatch(r"(\d+):(\d{2})", t)
    if m: return int(m[1]) * 60 + int(m[2])
    parts = re.findall(r"(\d+(?:\.\d+)?)\s*([a-z]+)", t)
    if not parts or any(u not in UNITS for _, u in parts): return None
    return round(sum(float(n) * UNITS[u] for n, u in parts))

def normalise(tool, key, value):
    if key == "time": return time_of_day(value)
    if key == "seconds": return int(value) if str(value).lstrip("-").isdigit() else duration(value)
    if key == "destination": return re.sub(r"^the\s+", "", str(value).strip().lower())
    return str(value).strip().lower()

# As in the app's GetDirections.modes: the app reads the travel mode from the request, not the model.
MODES = {"walking": r"\b(walk|walking|on foot|by foot)\b", "bicycling": r"\b(cycle|cycling|bicycle|by cycle)\b",
         "two-wheeler": r"\b(scooter|scooty|bike|motorbike|motorcycle|two[- ]wheeler)\b",
         "transit": r"\b(bus|train|metro|transit|public transport)\b", "driving": r"\b(drive|driving|car)\b"}

def arguments(call, request):
    """The call's arguments as the app reads them; a 'duration' text counts as 'seconds'."""
    args = dict(call.get("arguments") or {})
    if call["name"] == "get_directions":
        args.pop("mode", None)
        mode = next((m for m, pattern in MODES.items() if re.search(pattern, request.lower())), None)
        if mode: args["mode"] = mode
    if "duration" in args: args["seconds"] = duration(args.pop("duration"))
    return {k: normalise(call["name"], k, v) for k, v in args.items() if v is not None}

def cued(tool, request, cues):
    """Whether the request mentions what the tool does (its cue words); tools without cues pass."""
    pattern = cues.get(tool)
    return pattern is None or re.search(pattern, request, re.IGNORECASE) is not None

def suggest(right, wrong):
    """The lowest bar that holds back every wrong call that would otherwise run, and how many right ones still run."""
    bar = max([0.3] + [c + 0.01 for c in wrong])
    return round(bar, 2), sum(c >= bar for c in right)

def main():
    tools_json = open(sys.argv[1]).read()
    bars = json.load(open(sys.argv[3])) if len(sys.argv) > 3 else {}
    cues = json.load(open(sys.argv[4])) if len(sys.argv) > 4 else {}
    loaded = {t["name"] for t in json.loads(tools_json)}
    expectations = []
    category = "requests.txt"  # until a "# @name" line starts a category (suite.txt)
    categories = {}
    for line in open(sys.argv[2]):
        line = line.strip()
        if line.startswith("# @"): category = line[3:].split(":")[0].strip()
        if not line or line.startswith("#"): continue
        spec, request = [x.strip() for x in line.split("|", 1)]
        name, *pairs = shlex.split(spec)
        tool = next((t for t in name.split("/") if t in loaded), "none")
        want = dict(p.split("=", 1) for p in pairs) if tool != "none" else {}
        expectations.append((request, tool, {k: normalise(tool, k, v) for k, v in want.items()}))
        categories[request] = category
    answers = dict(line.rstrip("\n").split("\t", 1) for line in sys.stdin if "\t" in line)

    right = wrong_args = calls = runs = wrong_runs = 0
    confidence_sum = 0.0
    per_tool = {t: ([], []) for t in sorted(loaded)}  # tool -> (confidence of right calls, of wrong calls that skip the cues)
    # Per category: [requests, right, wrong calls]; and every call, as (confidence, right, passes the checks), for one bar for all.
    per_category, every_call, times = {}, [], []
    for request, tool, want in expectations:
        reply = json.loads(answers[request])
        got = reply.get("function_calls") or []
        # CONFIDENCE=first|mean picks another of bench.py's stand-ins for a confidence.
        c = reply.get(os.environ.get("CONFIDENCE", "confidence")) or 0.0
        # The app's rule: a reply runs when every tool it calls is mentioned (its cues) and the
        # confidence clears the highest bar among them.
        bar = max((bars.get(g["name"], 0.7) for g in got), default=0.7)
        all_cued = all(cued(g["name"], request, cues) and grounded(g, request) for g in got)
        runs_now = bool(got) and all_cued and c >= bar
        if tool == "none":
            ok = not got
            mark = "OK  " if ok else "MISS"
        else:
            calls += 1
            match = next((g for g in got if g["name"] == tool), None)
            args = arguments(match, request) if match else {}
            ok = match is not None and all(args.get(k) == v for k, v in want.items())
            if match and not ok: wrong_args += 1
            if ok:
                confidence_sum += c
                runs += runs_now
                per_tool[tool][0].append(c)
            mark = ("OK  " if runs_now else "ok? ") if ok else ("ARGS" if match else "MISS")
        right += ok
        counts = per_category.setdefault(categories[request], [0, 0, 0])
        counts[0] += 1; counts[1] += ok; counts[2] += bool(got) and not ok
        if got: every_call.append(({k: reply.get(k) or 0.0 for k in ("confidence", "first", "mean") if k in reply}, ok, all_cued))
        if reply.get("ms"): times.append(reply["ms"])
        if not ok:
            wrong_runs += runs_now
            for g in got:
                if g["name"] in per_tool and all_cued: per_tool[g["name"]][1].append(c)
        print(f"{mark} {c:.2f}  {tool:<14} {request:<44} {json.dumps(got)[:110]}")
    print(f"\n{right}/{len(expectations)} right (tool and arguments); wrong arguments: {wrong_args}")
    print(f"calls: mean confidence when right (misses as 0) {confidence_sum / max(calls, 1):.3f}; "
          f"{runs} of {calls} right and run at their tool's bar; wrong calls that would run: {wrong_runs}")
    print(f"\n{'tool':<15}{'bar':>5}  {'runs':>7}  {'suggest':>7}  right confidences / wrong ones that pass the cues and numbers")
    for tool, (ok, bad) in per_tool.items():
        if not ok and not bad: continue
        bar = bars.get(tool, 0.7)
        at, n = suggest(ok, bad)
        print(f"{tool:<15}{bar:>5.2f}  {sum(c >= bar for c in ok):>3}/{len(ok):<3}  {at:>4.2f} {n:>2}  "
              f"{' '.join(f'{c:.2f}' for c in sorted(ok))}  /  {' '.join(f'{c:.2f}' for c in sorted(bad)) or '-'}")
    if len(per_category) > 1:
        print(f"\n{'category':<15}{'right':>11}  wrong calls")
        for name, (n, ok, bad) in per_category.items():
            print(f"{name:<15}{ok:>5}/{n:<5}  {bad}")
    # The same bar for every tool, for comparing models whose confidence means something else
    # (another model's is its weakest token's probability): calls that get past the cues and
    # checks, right ones that run and wrong ones that would.
    measures = [k for k in ("confidence", "first", "mean") if any(k in c for c, _, _ in every_call)]
    for measure in measures:
        print(f"\none bar for all tools, on {measure!r} (calls past the cues and checks): bar  right runs  wrong runs")
        checked = [(c.get(measure, 0.0), ok) for c, ok, passes in every_call if passes]
        safe = next((b / 100 for b in range(10, 101) if not any(c >= b / 100 and not ok for c, ok in checked)), 1.0)
        for b in sorted({0.3, 0.5, 0.7, 0.9, safe}):
            print(f"  {b:.2f}{'*' if b == safe else ' '}  {sum(c >= b and ok for c, ok in checked):>5}  {sum(c >= b and not ok for c, ok in checked):>5}")
    print("  (* the lowest bar at which no wrong call runs)")
    if times:
        times.sort()
        print(f"\nms per request: median {times[len(times) // 2]}, 90th percentile {times[int(len(times) * 0.9)]}")

main()
