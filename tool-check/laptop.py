"""Runs requests the way Ferb does with Computer picked, on the laptop, with no phone: Ferb's
keyword routing (tools the request mentions, else every tool), then Ferb Link's own code against
the laptop's model server (ferb-link's `mise run companion:harness`), then the calls read as
Ferb reads them (score.py's copies of its time and length readers), scored against a bench file.

    laptop.py bench.txt [texts.txt]

bench.txt is in requests.txt's format (audio.txt, audio-alarms.txt…). Without texts.txt each
request is sent as written (typed); with it, its lines ("NN | text", NN the bench line, or plain
text in order) are sent instead, e.g. what transcribe returned for each clip. Take
/tmp/ferb-validate.lock first: the model server runs one thing at a time.
"""
import json, os, re, shlex, subprocess, sys, tempfile
from score import arguments, normalise, cued, grounded

HERE = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(HERE, "..", "app", "build", "tool-check")
FERB_LINK = "/data/Projects/ferb-link"

def bench(path):
    out = []
    for line in open(path):
        line = line.strip()
        if not line or line.startswith("#"): continue
        spec, request = [x.strip() for x in line.split("|", 1)]
        name, *pairs = shlex.split(spec)
        out.append((request, name.split("/"), {k: normalise(name, k, v) for k, v in (p.split("=", 1) for p in pairs)}))
    return out

def main():
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
