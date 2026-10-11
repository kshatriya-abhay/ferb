"""Puts `mise run bench` results side by side: one column per model, from the scorer's reports.

    compare.py build/bench/results/*-all-routed.txt
"""
import os, re, sys

def read(path):
    text = open(path).read()
    report = {}
    m = re.search(r"(\d+)/(\d+) right \(tool and arguments\); wrong arguments: (\d+)", text)
    report["right"] = f"{m[1]}/{m[2]}" if m else "?"
    report["wrong arguments"] = m[3] if m else "?"
    for name, ok, n, bad in re.findall(r"^(\w[\w.]*)\s+(\d+)/(\d+)\s+(\d+)$", text, re.M):
        report[f"  {name}"] = f"{ok}/{n} ({bad})"
    for measure, block in re.findall(r"^one bar for all tools, on '(\w+)'.*\n((?:  .*\n)+)", text, re.M):
        report[f"{measure}: no wrong runs from"] = "no bar"
        for bar, star, ok, bad in re.findall(r"^  (\d\.\d\d)(\*?)\s+(\d+)\s+(\d+)$", block, re.M):
            if star: report[f"{measure}: no wrong runs from"] = f"{bar}: {ok} right"
            if bar == "1.00": report[f"{measure}: at 1.00, right / wrong"] = f"{ok} / {bad}"
            if bar == "0.70": report[f"{measure}: at 0.70, right / wrong"] = f"{ok} / {bad}"
    m = re.search(r"ms per request: median (\d+), 90th percentile (\d+)", text)
    report["ms (desktop CPU)"] = f"{m[1]} / {m[2]}" if m else "-"
    return report

def main():
    paths = sys.argv[1:]
    names = [re.sub(r"-(all|default)-(routed|unrouted|fallback)\.txt$", "", os.path.basename(p)) for p in paths]
    reports = [read(p) for p in paths]
    rows = list(dict.fromkeys(k for r in reports for k in r))
    rows.sort(key=lambda k: k.startswith("ms"))
    width = max(len(r) for r in rows) + 2
    columns = [max(len(n), *(len(r.get(k, "")) for k in rows)) + 2 for n, r in zip(names, reports)]
    print("".ljust(width) + "".join(n.rjust(c) for n, c in zip(names, columns)))
    for row in rows:
        print(row.ljust(width) + "".join(r.get(row, "").rjust(c) for r, c in zip(reports, columns)))
    print("\nCategories: right/requests (wrong calls). Needle 3's confidence is its own score; the others' is the "
          "probability of their weakest token ('confidence'), of their first ('first', whether to call at all) or the "
          "mean ('mean'), so compare bars within a column, not across.")

if __name__ == "__main__":
    main()
