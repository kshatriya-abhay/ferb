"""Scores Needle 3's answers to tool-check/requests.txt: the tool and the arguments.

Reads "request<TAB>engine JSON" lines (tool_check with TOOL_CHECK_RAW=1) on stdin. Each request
line in requests.txt is "tool[ key=value ...] | request": "none" expects no call, "a/b" either tool
(the first one loaded), and key=value pairs the arguments that matter. Values are compared the way
the app reads them: times of day as HH:MM, durations as seconds (from a "seconds" number or a
"duration" text), app and page names case-insensitively.
"""
import json, re, sys

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

def duration(text):
    """'2 hours', '1 hour 15 minutes', '90 seconds', '45 min', '1:30' -> seconds."""
    t = str(text).strip().lower().replace("and", " ").replace("half an hour", "30 minutes")
    m = re.fullmatch(r"(\d+):(\d{2})", t)
    if m: return int(m[1]) * 60 + int(m[2])
    parts = re.findall(r"(\d+(?:\.\d+)?)\s*([a-z]+)", t)
    if not parts or any(u not in UNITS for _, u in parts): return None
    return round(sum(float(n) * UNITS[u] for n, u in parts))

def normalise(tool, key, value):
    if key == "time": return time_of_day(value)
    if key == "seconds": return int(value) if str(value).lstrip("-").isdigit() else duration(value)
    return str(value).strip().lower()

def arguments(call):
    """The call's arguments as the app reads them; a 'duration' text counts as 'seconds'."""
    args = dict(call.get("arguments") or {})
    if "duration" in args: args["seconds"] = duration(args.pop("duration"))
    return {k: normalise(call["name"], k, v) for k, v in args.items() if v is not None}

def cued(tool, request, cues):
    """Whether the request mentions what the tool does (its cue words); tools without cues pass."""
    pattern = cues.get(tool)
    return pattern is None or re.search(pattern, request, re.IGNORECASE) is not None

def main():
    tools_json = open(sys.argv[1]).read()
    cues = json.load(open(sys.argv[4])) if len(sys.argv) > 4 else {}
    loaded = {t["name"] for t in json.loads(tools_json)}
    run_at = float(sys.argv[3]) if len(sys.argv) > 3 else 0.4
    expectations = []
    for line in open(sys.argv[2]):
        line = line.strip()
        if not line or line.startswith("#"): continue
        spec, request = [x.strip() for x in line.split("|", 1)]
        name, *pairs = spec.split()
        tool = next((t for t in name.split("/") if t in loaded), "none")
        want = dict(p.split("=", 1) for p in pairs) if tool != "none" else {}
        expectations.append((request, tool, {k: normalise(tool, k, v) for k, v in want.items()}))
    answers = dict(line.rstrip("\n").split("\t", 1) for line in sys.stdin if "\t" in line)

    right = wrong_args = calls = runs = wrong_runs = 0
    confidence_sum = 0.0
    for request, tool, want in expectations:
        reply = json.loads(answers[request])
        got = reply.get("function_calls") or []
        c = reply.get("confidence") or 0.0
        if tool == "none":
            ok = not got
            mark = "OK  " if ok else "MISS"
        else:
            calls += 1
            match = next((g for g in got if g["name"] == tool), None)
            args = arguments(match) if match else {}
            args_ok = match is not None and all(args.get(k) == v for k, v in want.items())
            ok = args_ok
            if match and not args_ok: wrong_args += 1
            if ok:
                confidence_sum += c
                if c >= run_at: runs += 1
            mark = ("OK  " if c >= run_at else "ok? ") if ok else ("ARGS" if match else "MISS")
        right += ok
        # With cues, a call whose tool isn't mentioned asks first, whatever its confidence.
        would_run = got and c >= 0.3 and all(cued(g["name"], request, cues) for g in got)
        if not ok and would_run: wrong_runs += 1
        if ok and tool != "none" and c >= run_at and not would_run: runs -= 1  # a right call the cues held back
        print(f"{mark} {c:.2f}  {tool:<14} {request:<44} {json.dumps(got)[:110]}")
    print(f"\n{right}/{len(expectations)} right (tool and arguments); wrong arguments: {wrong_args}")
    print(f"calls: mean confidence when right (misses as 0) {confidence_sum / max(calls, 1):.3f}; "
          f"{runs} of {calls} right and >= {run_at}; wrong calls >= 0.3 (would run at the lowest bar): {wrong_runs}")

main()
