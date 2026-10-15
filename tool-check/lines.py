"""Prints bench files (requests.txt's format) without the sections named with --skip: a "# @name"
line starts a section, which runs to the next one. For the models that don't take a section's
lines, e.g. Needle 3 and Jev, which don't understand Hindi ("# @hi").

    lines.py [--skip name ...] file ...
"""
import sys

def kept(paths, skip):
    """The files' lines, in order, without the skipped sections."""
    for path in paths:
        section = None
        for line in open(path):
            if line.startswith("# @"): section = line[3:].split(":")[0].strip()
            if section not in skip: yield line

def main(argv):
    skip, paths = set(), []
    i = 0
    while i < len(argv):
        if argv[i] == "--skip": skip.add(argv[i + 1]); i += 2
        else: paths.append(argv[i]); i += 1
    sys.stdout.writelines(kept(paths, skip))

if __name__ == "__main__":
    main(sys.argv[1:])
