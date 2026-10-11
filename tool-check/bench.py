"""Runs another local model on tool-check's requests, the way route.py runs Needle 3, so the same
scorer compares them: each model gets the app's tool JSON, routed as the app routes it.

    bench.py llama-server model.gguf tools.json requests.txt cues.json [routed|unrouted|fallback]

Starts llama.cpp's server on the model and asks it each request through its OpenAI-style chat
API with the tools. Prints "request<TAB>JSON" lines like tool_check's, for score.py:
{"function_calls": [...], "confidence": ..., "ms": ...}. Other models have no confidence score
like Needle 3's; "confidence" here is the probability of the least likely token the model wrote
(its weakest choice), "first" that of its first token (whether to call a tool at all), "mean"
their mean, and "probs" every token's. llama.cpp reports probabilities before the grammar that
keeps tool calls well-formed, so a token the grammar forced can show as nearly impossible:
LFM2.5-1.2B's right calls had a weakest token near 0.
"""
import ast, json, math, os, re, subprocess, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(__file__))
import route

PORT = 8091
# One request at a time, and one model at a time (`mise run bench` holds a lock): requests or
# models run side by side share the CPU, so their timings mean nothing (2026-10-11: a 2B model
# with 4 requests at once and Needle 3 run alongside each slowed the other several times over).
PARALLEL = 1

# The same short instruction for every model; Needle 3 gets none (it's made for this alone).
SYSTEM = ("You are Ferb, an assistant on an Android phone. When the user asks for something one of the tools does, "
          "call that tool with the arguments in the user's words. If no tool fits, or the user isn't asking for an "
          "action, reply in a few words without calling a tool.")

def post(path, body):
    request = urllib.request.Request(f"http://127.0.0.1:{PORT}{path}", json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=300) as response:
        return json.load(response)

def ask_one(tools, request):
    started = time.monotonic()
    try:
        reply = post("/v1/chat/completions", {
            "messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": request}],
            "tools": [{"type": "function", "function": t} for t in tools],
            "tool_choice": "auto", "temperature": 0, "max_tokens": 256, "logprobs": True, "stop": STOP,
            # Thinking models (Qwen3.5) answer straight away, as on a phone.
            "chat_template_kwargs": {"enable_thinking": False},
        })
    except Exception as e:
        return json.dumps({"success": False, "function_calls": [], "confidence": 0.0, "error": str(e)})
    ms = round((time.monotonic() - started) * 1000)
    message = reply["choices"][0]["message"]
    calls = []
    for call in message.get("tool_calls") or []:
        arguments = call["function"].get("arguments") or "{}"
        try:
            arguments = json.loads(arguments) if isinstance(arguments, str) else arguments
        except json.JSONDecodeError:
            arguments = {"_unparsed": arguments}
        calls.append({"name": call["function"]["name"], "arguments": arguments})
    if not calls and "<start_function_call>" in (message.get("content") or ""):
        calls = function_gemma_calls(message["content"])
    if not calls:
        calls = listed_calls(message.get("content") or "")
    tokens = (reply["choices"][0].get("logprobs") or {}).get("content") or []
    probabilities = [math.exp(t["logprob"]) for t in tokens if t.get("logprob") is not None]
    return json.dumps({
        "success": True, "function_calls": calls,
        "confidence": round(min(probabilities), 4) if probabilities else 0.0,
        "first": round(probabilities[0], 4) if probabilities else 0.0,
        "mean": round(sum(probabilities) / len(probabilities), 4) if probabilities else 0.0,
        # Every token's, for trying other stand-ins for a confidence without running again.
        "probs": [round(p, 3) for p in probabilities],
        "text": (message.get("content") or "")[:400], "ms": ms,
    })

# FunctionGemma writes "<start_function_call>call:name{key:<escape>text<escape>,n:5}<end_function_call>"
# and then waits for the function's result; llama.cpp (b11541) neither parses that nor stops there,
# so it would ramble on with more calls ("call:show_alarms{}…"). The phone runtimes (LiteRT-LM,
# Cactus) parse it themselves.
STOP = ["<start_function_response>", "<end_function_call>call:"]

def function_gemma_calls(text):
    calls = []
    for name, body in re.findall(r"<start_function_call>call:(\w+)\{(.*?)\}(?:<end_function_call>|$)", text, re.S):
        arguments = {}
        for key, quoted, bare in re.findall(r"(\w+):(?:<escape>(.*?)<escape>|([^,}]*))", body, re.S):
            value = quoted if quoted or not bare else bare.strip()
            if not quoted and bare:
                value = {"true": True, "false": False}.get(value, int(value) if value.lstrip("-").isdigit() else value)
            arguments[key] = value
        calls.append({"name": name, "arguments": arguments})
    return calls

def listed_calls(text):
    """Hammer answers with its calls as a list in a code block, "[{'name': ..., 'arguments': {...}}]",
    which llama.cpp doesn't read as tool calls; an empty list is no call."""
    found = re.search(r"\[\s*(\{.*\})?\s*\]", text, re.S)
    if not found: return []
    try:
        listed = ast.literal_eval(found.group(0))
    except (ValueError, SyntaxError):
        return []
    return [{"name": c["name"], "arguments": c.get("arguments") or {}} for c in listed
            if isinstance(c, dict) and isinstance(c.get("name"), str)]

def ask(tools, requests):
    with ThreadPoolExecutor(PARALLEL) as pool:
        return dict(zip(requests, pool.map(lambda r: ask_one(tools, r), requests)))

class Server:
    """llama-server on [model], started at the first request it has to answer (none if every
    answer is cached)."""
    def __init__(self, binary, model):
        self.binary, self.model, self.process = binary, model, None

    def ask(self, tools, requests):
        if self.process is None: self.start()
        return ask(tools, requests)

    def start(self):
        self.process = subprocess.Popen([self.binary, "-m", self.model, "--port", str(PORT), "--jinja", "-np", str(PARALLEL),
                                         "-c", str(4096 * PARALLEL), "-t", "12", "--no-webui", "--log-disable"],
                                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(300):
            try:
                if post_health(): return
            except Exception:
                pass
            if self.process.poll() is not None: sys.exit(f"llama-server exited ({self.process.returncode})")
            time.sleep(1)

    def stop(self):
        if self.process:
            self.process.terminate()
            self.process.wait()

def main():
    binary, model, tools_path, requests_path, cues_path = sys.argv[1:6]
    mode = sys.argv[6] if len(sys.argv) > 6 else "routed"
    # The model file's SHA-256 (from mise), plus how this script asks: change either, and the cache misses.
    os.environ["BENCH_SALT"] = json.dumps([os.environ.get("BENCH_SALT", ""), SYSTEM, STOP, "temperature 0, max_tokens 256, one at a time"])
    server = Server(binary, model)
    try:
        requests = route.requests_in(requests_path)
        answers, summary = route.answer(route.cache_from_env(server.ask), json.load(open(tools_path)), json.load(open(cues_path)), requests, mode)
        for r in requests:
            print(f"{r}\t{answers[r]}")
        times = sorted(json.loads(a).get("ms", 0) for a in answers.values() if json.loads(a).get("ms"))
        if times:
            summary += f"; ms per request (desktop CPU, one at a time): median {times[len(times) // 2]}, 90th {times[int(len(times) * 0.9)]}"
        print(summary, file=sys.stderr)
    finally:
        server.stop()

def post_health():
    with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/health", timeout=2) as response:
        return json.load(response).get("status") == "ok"

if __name__ == "__main__":
    main()
