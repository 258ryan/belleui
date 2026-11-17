"""Query helper over e6_layout.json: resolve AknLayout lines against a parent rect.
python3 e6q.py <scratch> grep <regex> [iface]     list names
python3 e6q.py <scratch> show <name> [iface]      every variety, raw + resolved in 640x480 / parent
python3 e6q.py <scratch> chain iface:name:var ... resolve a parent chain from the 640x480 screen"""
import json
import re
import sys

S = sys.argv[1]
L = json.load(open(S + '/e6_layout.json'))


def pv(v, p):
    if v is None:
        return None
    if isinstance(v, str):
        return p + int(v[1:])
    return v


def font(f):
    if not isinstance(f, int) or not (f & 0x80000000):
        return f
    cat = {1: 'primary', 2: 'secondary', 3: 'title', 4: 'primary_small', 5: 'digital'}.get(f & 0xF, f & 0xF)
    return '%s%s h=%d' % (cat, ' bold' if f & 0x10 else '', (f & 0x7FE00000) >> 21)


def resolve(line, parent):
    """parent = (x, y, w, h) -> (x, y, w, h), using the AknLayoutUtils rules."""
    px, py, pw, ph = parent
    l, r, W = pv(line.get('l'), pw), pv(line.get('r'), pw), pv(line.get('W'), pw)
    t, b, H = pv(line.get('t'), ph), pv(line.get('b'), ph), pv(line.get('H'), ph)
    if W is None:
        W = pw - (l or 0) - (r or 0)
    if l is None:
        l = pw - (r or 0) - W
    if H is None:
        H = ph - (t or 0) - (b or 0)
    if t is None:
        t = ph - (b or 0) - H
    return (px + l, py + t, W, H)


def get(spec):
    iface, name, var = spec.split(':')
    return L[iface][name][int(var)]


cmd = sys.argv[2]
if cmd == 'grep':
    rx = re.compile(sys.argv[3])
    for iface in ([sys.argv[4]] if len(sys.argv) > 4 else L):
        names = [n for n in L[iface] if rx.search(n)]
        print(iface, len(names), ' '.join(names[:400]))
elif cmd == 'show':
    iface = sys.argv[4] if len(sys.argv) > 4 else 'avkon'
    for i, ln in enumerate(L[iface][sys.argv[3]]):
        raw = {k: ln[k] for k in ('l', 't', 'r', 'b', 'W', 'H', 'J') if k in ln}
        extra = ' font=%s' % font(ln['Font']) if 'Font' in ln else ''
        print('%s[%d] %s %s%s' % (sys.argv[3], i, ln['type'], raw, extra))
elif cmd == 'chain':
    rect = (0, 0, 640, 480)
    for spec in sys.argv[3:]:
        ln = get(spec)
        rect = resolve(ln, rect)
        extra = ' font=%s J=%s' % (font(ln['Font']), ln.get('J')) if 'Font' in ln else ''
        print('%-60s -> x=%d y=%d w=%d h=%d%s' % (spec, rect[0], rect[1], rect[2], rect[3], extra))
