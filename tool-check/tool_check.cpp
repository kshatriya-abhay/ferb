// Desktop check of the assistant's tools: runs Needle 3 (Cactus's Linux x86-64 build of the same
// engine) with the app's own tool JSON against requests whose right tool is known, and prints each
// call with its confidence. Run with `mise run tool-check`.
//
// Usage: tool_check <model.cact> <tools.json> <requests.txt>
// requests.txt: one "expected_tool | request" per line ("none" when no tool should be called).
// "a/b" accepts either tool: the first that's in tools.json is expected. A request none of whose
// tools are in tools.json (turned off) expects no call.

#include <cstdio>
#include <fstream>
#include <iterator>
#include <sstream>
#include <string>
#include <vector>

#include "needle.h"

static std::string read(const char* path) {
    std::ifstream f(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(f), {}};
}

static std::string trim(const std::string& s) {
    auto a = s.find_first_not_of(" \t");
    auto b = s.find_last_not_of(" \t\r\n");
    return a == std::string::npos ? "" : s.substr(a, b - a + 1);
}

// The value after "key": in the engine's JSON, crudely, for printing.
static std::string field(const std::string& json, const std::string& key) {
    auto at = json.find("\"" + key + "\":");
    if (at == std::string::npos) return "";
    at += key.size() + 3;
    if (json[at] == '[') {
        int depth = 0;
        for (size_t i = at; i < json.size(); ++i) {
            if (json[i] == '[') depth++;
            if (json[i] == ']' && --depth == 0) return json.substr(at, i - at + 1);
        }
    }
    auto end = json.find_first_of(",}", at);
    return json.substr(at, end - at);
}

int main(int argc, char** argv) {
    if (argc != 4) { fprintf(stderr, "usage: %s model.cact tools.json requests.txt\n", argv[0]); return 2; }
    std::string model = read(argv[1]);
    if (needle_load(reinterpret_cast<const unsigned char*>(model.data()), model.size()) < 0) { fprintf(stderr, "load: %s\n", needle_last_error()); return 1; }
    std::string tools = read(argv[2]);
    auto has = [&](const std::string& name) { return tools.find("\"name\": \"" + name + "\"") != std::string::npos; };
    int prefix = needle_init("date: 2026-10-06 (Tuesday)\nlocale: en-IN\ndevice: Android phone", tools.c_str(), nullptr);
    if (prefix < 0) { fprintf(stderr, "init: %s\n", needle_last_error()); return 1; }
    printf("prefix %d tokens\n\n", prefix);

    std::istringstream lines(read(argv[3]));
    std::string line;
    int total = 0, right = 0, sure = 0;
    std::vector<char> out(1 << 18);
    while (std::getline(lines, line)) {
        line = trim(line);
        if (line.empty() || line[0] == '#') continue;
        auto bar = line.find('|');
        std::string expected = trim(line.substr(0, bar)), request = trim(line.substr(bar + 1));
        {
            std::string pick = "none", rest = expected;
            while (!rest.empty() && rest != "none") {
                auto slash = rest.find('/');
                std::string name = trim(rest.substr(0, slash));
                if (has(name)) { pick = name; break; }
                rest = slash == std::string::npos ? "" : rest.substr(slash + 1);
            }
            expected = pick;
        }
        needle_reset();
        if (needle_complete(request.c_str(), nullptr, 0, 256, out.data(), out.size()) < 0) { printf("ERROR %s\n", needle_last_error()); continue; }
        std::string json(out.data());
        std::string calls = field(json, "function_calls");
        double confidence = atof(field(json, "confidence").c_str());
        bool ok = expected == "none" ? calls == "[]" : calls.find("\"name\":\"" + expected + "\"") != std::string::npos;
        total++;
        right += ok;
        sure += ok && (expected == "none" || confidence >= 0.7);
        printf("%s %.2f  %-14s %-45s %s\n", ok ? (confidence >= 0.7 || expected == "none" ? "OK  " : "ok? ") : "MISS", confidence, expected.c_str(), request.c_str(), calls.c_str());
    }
    printf("\n%d/%d right, %d of them sure enough to run without asking (>= 0.7)\n", right, total, sure);
    return 0;
}
