"""Runs tool_check the way the app routes requests: each request goes to Needle 3 with only the
tools whose keywords it contains (the app's ToolRouter, with each tool's default keywords); one that mentions none gets every tool,
and Needle 3's own retrieval of the best five.

    route.py tool_check model.cact tools.json requests.txt cues.json [fallback]

With "fallback", a request no mentioned tool answered (or that mentions none) is tried against
every tool, five at a time, keeping the most confident answer (the app's "Check all tools as a
fallback").
Prints "request<TAB>engine JSON" lines, like tool_check with TOOL_CHECK_RAW=1, for score.py.
"""
import hashlib, json, os, re, subprocess, sys, tempfile

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

class Cache:
    """
    Answers kept in a JSON-lines file ([path]), by model ([salt]: what makes its answers its own,
    e.g. the model file's SHA-256 and the prompt), tool set (the routed tools' full schemas) and
    request: a model already run isn't run again, added requests run alone, and a tool whose
    schema changed reruns only the requests routed to it. Saved after each tool set, so a run
    that stops keeps what it got. Delete the file to run a model afresh (e.g. after changing
    how its replies are read).
    """
    def __init__(self, path, salt):
        self.path, self.salt = path, salt
        self.entries = {}
        if os.path.exists(path):
            for line in open(path):
                if line.strip():
                    e = json.loads(line)
                    self.entries[e["key"]] = e

    def key(self, tools, request):
        return hashlib.sha256(json.dumps([self.salt, tools, request], sort_keys=True).encode()).hexdigest()[:20]

    def wrap(self, ask):
        """[ask], asking only for the answers not kept yet."""
        def cached(tools, requests):
            missing = [r for r in requests if self.key(tools, r) not in self.entries]
            if missing:
                for r, a in ask(tools, missing).items():
                    self.entries[self.key(tools, r)] = {"key": self.key(tools, r), "request": r, "tools": [t["name"] for t in tools], "answer": a}
                self.save()
            return {r: self.entries[self.key(tools, r)]["answer"] for r in requests}
        return cached

    def save(self):
        os.makedirs(os.path.dirname(self.path) or ".", exist_ok=True)
        with open(self.path + ".part", "w") as f:
            for e in sorted(self.entries.values(), key=lambda e: (e["request"], e["key"])):
                f.write(json.dumps(e, ensure_ascii=False) + "\n")
        os.replace(self.path + ".part", self.path)

def cache_from_env(ask):
    """[ask] kept in BENCH_CACHE, under BENCH_SALT, when they're set (by `mise run bench`)."""
    if not os.environ.get("BENCH_CACHE"): return ask
    return Cache(os.environ["BENCH_CACHE"], os.environ.get("BENCH_SALT", "")).wrap(ask)

def answer(ask, tools, cues, requests, mode="routed"):
    """Answers to [requests], routed as the app does: [ask](tools, requests) runs one engine call
    per request with those tools and returns engine JSON by request. Modes as in the module doc,
    plus "unrouted": every tool for every request. Returns (answers by request, a summary line)."""
    def mentioned(request):
        return tuple(t["name"] for t in tools
                     if t["name"] not in cues or re.search(cues[t["name"]], request, re.IGNORECASE))

    everything = tuple(t["name"] for t in tools)
    # One engine run per distinct set of tools, like the app re-initialising when the set changes.
    routes = {r: everything if mode == "unrouted" else mentioned(r) for r in requests}
    answers = {r: NO_CALL for r in requests}
    if mode != "fallback":
        routes = {r: v or everything for r, v in routes.items()}
    for route in sorted(set(routes.values())):
        if not route: continue
        asked = [r for r in requests if routes[r] == route]
        for r, a in ask([t for t in tools if t["name"] in route], asked).items():
            answers[r] = a
    if mode == "fallback":
        unanswered = [r for r in requests if not json.loads(answers[r]).get("function_calls")]
        groups = [tools[i:i + MAX_SEEN] for i in range(0, len(tools), MAX_SEEN)]
        for group in groups:
            for r, a in ask(group, unanswered).items():
                got, best = json.loads(a), json.loads(answers[r])
                if got.get("function_calls") and (got.get("confidence") or 0) > (best.get("confidence") or 0):
                    answers[r] = a
    summary = (f"routes: {len(set(routes.values()))} tool sets; no tool mentioned: {sum(not mentioned(r) for r in requests)}; "
               f"more than {MAX_SEEN} mentioned: {sum(len(v) > MAX_SEEN for v in routes.values())}")
    return answers, summary

def requests_in(path):
    return [line.split("|", 1)[1].strip() for line in open(path) if line.strip() and not line.startswith("#")]

def main():
    tool_check, model, tools_path, requests_path, cues_path = sys.argv[1:6]
    mode = sys.argv[6] if len(sys.argv) > 6 else "routed"
    requests = requests_in(requests_path)
    answers, summary = answer(cache_from_env(lambda tools, asked: run(tool_check, model, tools, asked)),
                              json.load(open(tools_path)), json.load(open(cues_path)), requests, mode)
    for r in requests:
        print(f"{r}\t{answers[r]}")
    print(summary, file=sys.stderr)

if __name__ == "__main__":
    main()
