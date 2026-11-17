#!/usr/bin/env python3
"""Extract design tokens from the Qt Components QML in a Symbian Belle ROM dump.
usage: extract.py <rom-root> <out.json>"""
import sys, re, json, pathlib
root = pathlib.Path(sys.argv[1]); out = sys.argv[2]
base = root / "resource/qt/imports/com/nokia"
tokens = {"constants": {}, "components": {}}
# 1. constants.js (var NAME = value;)
for m in re.finditer(r'^var\s+(\w+)\s*=\s*([^;\n]+?)\s*;?\s*(?://.*)?$', (base/"extras.1.1/constants.js").read_text(errors="ignore"), re.M):
    v = m.group(2).strip().strip('"')
    try: v = float(v) if '.' in v else int(v)
    except ValueError:
        if v in ("true", "false"): v = v == "true"
    tokens["constants"][m.group(1)] = v
# 2. per-component literals
pat = {
  "colors":    re.compile(r'"(#[0-9a-fA-F]{6,8})"'),
  "durations": re.compile(r'\b(?:duration|interval|pressAndHoldDelay)\s*:\s*(\d+)'),
  "easing":    re.compile(r'easing\.type\s*:\s*(Easing\.\w+)'),
  "opacity":   re.compile(r'\bopacity\s*:\s*(0?\.\d+|1)\b'),
  "sizes":     re.compile(r'\b((?:implicit)?(?:width|height)|font\.pixelSize|(?:left|right|top|bottom)Margin|spacing|radius)\s*:\s*(\d+)\b'),
  "images":    re.compile(r'"(qtg_[a-z0-9_]+)"'),
}
for f in sorted(list((base/"symbian.1.1").glob("*.qml")) + list((base/"extras.1.1").glob("*.qml"))):
    t = f.read_text(errors="ignore"); d = {}
    for k, p in pat.items():
        r = sorted({"|".join(x) if isinstance(x, tuple) else x for x in p.findall(t)})
        if r: d[k] = r
    if d: tokens["components"][f.stem] = d
json.dump(tokens, open(out, "w"), indent=1)
print(len(tokens["constants"]), "constants,", len(tokens["components"]), "components")
