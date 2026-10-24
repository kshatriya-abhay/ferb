"""Dates and times (tool-check/datetime.txt): Needle 3's answers on the desktop, for the Kotlin scorer.

    [DATES_SET=datetime-v2.txt] dates.py needle [typed|FILE.jsonl]

DATES_SET picks the set in tool-check/ (datetime.txt by default); another set's answers are named
with its suffix (needle-typed-v2.jsonl).

Each line goes to Needle 3 with the date facts of its own "now" (as the app gives the engine) and
the tools its words route to (route.py's routing). "typed": the request as written; FILE.jsonl:
rewritten text from the phone ({"i", "command"} lines; NONE gives no call). The answers go to
build/datetime/needle-<name>.jsonl, which DateTimeEval (FERB_EVAL=1) scores with Ferb's resolvers.
"""
import datetime, json, os, re, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
BUILD = os.path.join(REPO, "app", "build", "tool-check")
OUT = os.path.join(REPO, "build", "datetime")
TOOL_CHECK = os.path.join(REPO, "build", "tool-check", "tool_check")
SET = os.environ.get("DATES_SET", "datetime.txt")
SUFFIX = os.path.splitext(SET)[0].removeprefix("datetime").replace("-", "", 1)
SUFFIX = f"-{SUFFIX}" if SUFFIX else ""
MODEL = os.path.join(REPO, "app", "src", "main", "assets", "needle3.cact")

def lines():
    rows = []
    for raw in open(os.path.join(HERE, SET)):
        if not raw.strip() or raw.startswith("#"): continue
        now, tool, expected, request = [x.strip() for x in raw.split("|", 3)]
        rows.append({"i": len(rows), "now": now.split()[0], "tool": tool, "expected": expected, "request": request})
    return rows

def facts(now):
    d = datetime.datetime.fromisoformat(now)
    return f"date: {d.date().isoformat()} ({d.strftime('%A')})\nlocale: en-IN\ndevice: Android phone"

def needle(source):
    tools = json.load(open(os.path.join(BUILD, "all.json")))
    cues = json.load(open(os.path.join(BUILD, "cues.json")))
    def routed(text):
        names = [t["name"] for t in tools if t["name"] not in cues or re.search(cues[t["name"]], text, re.IGNORECASE)]
        return tuple(names or [t["name"] for t in tools])
    rows = lines()
    if source == "typed":
        name, texts = "typed", {r["i"]: r["request"] for r in rows}
    else:
        name = os.path.splitext(os.path.basename(source))[0]
        texts = {}
        for l in open(source):
            if l.strip():
                o = json.loads(l)
                texts[o["i"]] = o.get("command")
    groups = {}
    for r in rows:
        text = texts.get(r["i"])
        if text: groups.setdefault((facts(r["now"]), routed(text)), []).append((r["i"], text))
    answers = {}
    with tempfile.TemporaryDirectory() as tmp:
        for (f, names), items in groups.items():
            tp, rp = os.path.join(tmp, "tools.json"), os.path.join(tmp, "requests.txt")
            json.dump([t for t in tools if t["name"] in names], open(tp, "w"))
            open(rp, "w").write("".join(f"none | {text}\n" for _, text in items))
            out = subprocess.run([TOOL_CHECK, MODEL, tp, rp, f], env={**os.environ, "TOOL_CHECK_RAW": "1"}, capture_output=True, text=True, check=True).stdout
            got = dict(l.split("\t", 1) for l in out.splitlines() if "\t" in l)
            for i, text in items: answers[i] = (text, got[text])
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, f"needle-{name}{SUFFIX}.jsonl")
    with open(path, "w") as f:
        for r in rows:
            text, raw = answers.get(r["i"], (texts.get(r["i"]), None))
            f.write(json.dumps({"i": r["i"], "now": r["now"], "request": r["request"], "text": text, "raw": raw}, ensure_ascii=False) + "\n")
    print(f"{len(answers)} of {len(rows)} lines answered by Needle 3 -> {os.path.relpath(path, REPO)}")

if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] != "needle": sys.exit(__doc__)
    needle(sys.argv[2] if len(sys.argv) > 2 else "typed")
