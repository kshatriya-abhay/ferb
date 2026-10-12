"""Scores the audio bench: what Ferb did with each spoken request, against tool-check/audio.txt.

    voice_score.py audio.txt [voice-log.jsonl]   score (pulls Ferb's voice log from the phone over adb if no file)
    voice_score.py --reset                      clear the log on the phone before a run

Ferb appends one JSON line per spoken request it answered (link/VoiceLog.kt): what the computer
heard, the English command, the calls, and how Ferb read them ("Open Paytm" from "ptm"). The lines
pair with audio.txt's requests in order, so read them in order and don't redo one that answered;
one that failed (no answer) leaves no line, so it can be said again. A call is right when its tool
and arguments match, scored as score.py does; names, apps and places also count when Ferb's reading
of the call has them (its own matching found the app). Names are scored as heard, whether or not
the contact is on the phone; whether Ferb found a contact is reported apart.
"""
import json, shlex, subprocess, sys
from score import arguments, normalise

LOG = "files/voice-log.jsonl"
PACKAGE = "dev.abhay.assistant"

def adb(*args):
    return subprocess.run(["adb", "exec-out", "run-as", PACKAGE, *args], capture_output=True, text=True).stdout

def expectations(path):
    category, out = None, []
    for line in open(path):
        line = line.strip()
        if line.startswith("# @"): category = line[3:].split(":")[0].strip()
        if not line or line.startswith("#"): continue
        spec, request = [x.strip() for x in line.split("|", 1)]
        name, *pairs = shlex.split(spec)
        want = {k: normalise(name, k, v) for k, v in (p.split("=", 1) for p in pairs)}
        out.append((category, request, name.split("/"), want))
    return out

def judge(tools, want, entry, request):
    calls, read = entry["calls"], " ".join(entry["read"]).lower().replace(" ", "")
    if tools == ["none"]: return not calls
    match = next((c for c in calls if c["name"] in tools), None)
    if match is None: return False
    args = arguments(match, request)
    def ok(k, v):
        if args.get(k) == v: return True
        # A name counts as heard, spaces aside ("sharmaji"), whether or not the phone has the contact;
        # contacts are scored apart (contact()).
        if k == "name": return str(args.get(k, "")).replace(" ", "") == str(v).replace(" ", "")
        # Ferb's own matching of apps and places: "ptm" → "Open Paytm".
        return k in ("app_name", "destination") and str(v).replace(" ", "") in read
    return all(ok(k, v) for k, v in want.items())

def contact(entry):
    """For a call or text: whether Ferb found a contact for the name (its reading isn't a failure)."""
    read = [r for c, r in zip(entry["calls"], entry["read"]) if c["name"] in ("make_call", "send_text")]
    return bool(read) and not any(r.startswith("invalid") or r.startswith("No contact matches") for r in read)

def main():
    if sys.argv[1:] == ["--reset"]:
        adb("rm", "-f", LOG)
        print("voice log cleared")
        return
    bench = expectations(sys.argv[1])
    lines = open(sys.argv[2]).read() if len(sys.argv) > 2 else adb("cat", LOG)
    log = [json.loads(l) for l in lines.splitlines() if l.strip()]
    if len(log) != len(bench):
        print(f"note: {len(log)} spoken requests logged for {len(bench)} in the bench; paired in order\n")
    per, times, contacts = {}, [], []
    for (category, request, tools, want), entry in zip(bench, log):
        right = judge(tools, want, entry, request)
        counts = per.setdefault(category, [0, 0])
        counts[0] += 1; counts[1] += right
        times.append(entry["ms"])
        if any(t in ("make_call", "send_text") for t in tools): contacts.append((request, contact(entry), entry["read"]))
        calls = "; ".join(f"{c['name']}({', '.join(f'{k}={v}' for k, v in c['arguments'].items())})" for c in entry["calls"]) or "no tool"
        print(f"{'OK  ' if right else 'MISS'} {request}\n     heard: {entry['heard']}\n     text:  {entry['text']}\n"
              f"     calls: {calls}" + (f"  ->  {' / '.join(entry['read'])}" if entry["read"] else "") + "\n")
    total = sum(r for _, r in per.values())
    print(f"{total}/{sum(n for n, _ in per.values())} right")
    for category, (n, r) in per.items(): print(f"  {category:<10}{r:>3}/{n}")
    if contacts:
        print(f"contacts found on the phone: {sum(found for _, found, _ in contacts)}/{len(contacts)} calls and texts")
        for request, found, read in contacts:
            if not found: print(f"  none for {request!r}: {' / '.join(read) or 'no call'}")
    if times:
        times.sort()
        print(f"ms per request (transcribed to answered): median {times[len(times) // 2]}, slowest {times[-1]}")

main()
