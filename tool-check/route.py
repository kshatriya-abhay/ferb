"""Runs tool_check the way the app routes requests: each request goes to Needle 3 with only the
tools whose cue words it contains (the app's ToolRouter); one that mentions none gets every tool,
and Needle 3's own retrieval of the best five.

    route.py tool_check model.cact tools.json requests.txt cues.json [fallback]

With "fallback", a request no mentioned tool answered (or that mentions none) is tried against
every tool, five at a time, keeping the most confident answer (the app's "Check all tools as a
fallback").
Prints "request<TAB>engine JSON" lines, like tool_check with TOOL_CHECK_RAW=1, for score.py.
"""
import json, os, re, subprocess, sys, tempfile

MAX_SEEN = 5  # the app's ToolRouter.MAX_SEEN: Needle 3 considers every tool up to five
NO_CALL = json.dumps({"success": True, "function_calls": [], "confidence": 0.0, "routed": []})

def run(tool_check, model, tools, requests):
    """Needle 3's answers to [requests] with [tools] (schema objects), by request."""
    with tempfile.TemporaryDirectory() as tmp:
        tools_path, requests_path = os.path.join(tmp, "tools.json"), os.path.join(tmp, "requests.txt")
        json.dump(tools, open(tools_path, "w"))
        open(requests_path, "w").write("".join(f"none | {r}\n" for r in requests))
        out = subprocess.run([tool_check, model, tools_path, requests_path], env={**os.environ, "TOOL_CHECK_RAW": "1"},
                             capture_output=True, text=True, check=True).stdout
    return dict(line.split("\t", 1) for line in out.splitlines() if "\t" in line)

def main():
    tool_check, model, tools_path, requests_path, cues_path = sys.argv[1:6]
    fallback = len(sys.argv) > 6 and sys.argv[6] == "fallback"
    tools = json.load(open(tools_path))
    cues = json.load(open(cues_path))
    requests = [line.split("|", 1)[1].strip() for line in open(requests_path)
                if line.strip() and not line.startswith("#")]

    def mentioned(request):
        return tuple(t["name"] for t in tools
                     if t["name"] not in cues or re.search(cues[t["name"]], request, re.IGNORECASE))

    # One engine run per distinct set of tools, like the app re-initialising when the set changes.
    routes = {r: mentioned(r) for r in requests}
    answers = {r: NO_CALL for r in requests}
    if not fallback:
        routes = {r: v or tuple(t["name"] for t in tools) for r, v in routes.items()}
    for route in sorted(set(routes.values())):
        if not route: continue
        asked = [r for r in requests if routes[r] == route]
        for r, a in run(tool_check, model, [t for t in tools if t["name"] in route], asked).items():
            answers[r] = a
    if fallback:
        unanswered = [r for r in requests if not json.loads(answers[r]).get("function_calls")]
        groups = [tools[i:i + MAX_SEEN] for i in range(0, len(tools), MAX_SEEN)]
        for group in groups:
            for r, a in run(tool_check, model, group, unanswered).items():
                got, best = json.loads(a), json.loads(answers[r])
                if got.get("function_calls") and (got.get("confidence") or 0) > (best.get("confidence") or 0):
                    answers[r] = a
    for r in requests:
        print(f"{r}\t{answers[r]}")
    print(f"routes: {len(set(routes.values()))} tool sets; no tool mentioned: {sum(not mentioned(r) for r in requests)}; "
          f"more than {MAX_SEEN} mentioned: {sum(len(v) > MAX_SEEN for v in routes.values())}", file=sys.stderr)

main()
