"""Decodes the Nokia E6 (RM-609) landscape layout data straight from the firmware (2001cb90.dll,
Elaf_Vga4_touch_land_Normal_Pack) and names the lines with the Symbian Foundation CDL order.
Writes e6_layout.json: {interface: {api name: [variety 0.., each a decoded line]}} and prints a
firmware-vs-SF agreement report."""
import json
import re
import struct
import sys

import os; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from akndecode import decode, parse_bytes, parse_lookup  # noqa: E402

S = sys.argv[1]
G = S + '/src/uires/layouts/aknlayout2/generated/Vga4_touch_akn_app'
fw = open(S + '/flat/2001cb90.bin', 'rb').read()
CO, CB = 156, 0x8000


def o(a):
    return a - CB + CO


# found by walking KCdlData (customisation array) and the Elaf_Vga4_touch_land_Normal_Pack contents
INSTANCES = {
    'avkon': dict(lookup=0xEE270, data=0xC030, end=0x2F6EC, n=5870,
                  sf_lsc='aknlayoutscalable_elaf_pvl4_av_vga4_lsc_tch_normal.cpp',
                  sf_prt='aknlayoutscalable_elaf_pvp4_av_vga4_prt_tch_normal.cpp'),
    'apps': dict(lookup=0xF99E8, data=0x2F6EC, end=0x61BF0, n=8053,
                 sf_lsc='aknlayoutscalable_elaf_pvl4_apps_vga4_lsc_tch_normal.cpp',
                 sf_prt='aknlayoutscalable_elaf_pvp4_apps_vga4_prt_tch_normal.cpp'),
}


def sf_names(path):
    txt = open(path, encoding='latin-1').read()
    body = re.search(r'const TUint16 KDataLookup\[[^\]]*\] =\s*\{(.*?)\n\s*\};', txt, re.S).group(1)
    names, rows = [], []
    for line in body.splitlines():
        m = re.match(r'\s*0x([0-9a-fA-F]+),\s*(?://\s*\(0x([0-9a-fA-F]+)\)\s*(\w+))?', line)
        if not m:
            continue
        rows.append((int(m.group(1), 16), m.group(3)))
    # an unnamed row is a table's Limits entry; it sits before the table's first named row
    for i, (v, n) in enumerate(rows):
        if n is None:
            nxt = next((r[1] for r in rows[i + 1:] if r[1]), 'table')
            n = re.sub(r'_ParamLimits$', '', nxt) + '_Limits'
        names.append(n)
    return names


def all_varieties(blob, off):
    out = []
    for v in range(64):
        try:
            line = decode(blob, off, v)
        except (IndexError, KeyError):
            break
        out.append(line)
        if line.get('varieties', 1) <= v + 1:
            break
    return out


report = {}
result = {}
for key, inst in INSTANCES.items():
    blob = fw[o(inst['data']):o(inst['end'])]
    lookup = struct.unpack_from('<%dI' % inst['n'], fw, o(inst['lookup']))
    names = sf_names(G + '/' + inst['sf_lsc'])
    sf_blob = parse_bytes(G + '/' + inst['sf_prt'])
    sf_base, sf_rows = parse_lookup(G + '/' + inst['sf_lsc'])
    sf_at = {n: ab for n, rel, ab in sf_rows}
    lines = {}
    same = diff = 0
    diffs = []
    for i, off in enumerate(lookup):
        name = names[i] if i < len(names) else 'api_%d' % i
        if name.endswith('_Limits') or name.endswith('_ParamLimits'):
            continue
        try:
            fv = all_varieties(blob, off)
        except Exception:
            continue
        if not fv:
            continue
        lines[name] = fv
        if name in sf_at:
            try:
                sv = all_varieties(sf_blob, sf_at[name])
            except Exception:
                sv = None
            if sv == fv:
                same += 1
            else:
                diff += 1
                if len(diffs) < 40:
                    diffs.append((name, sv[:1] if sv else None, fv[:1]))
    result[key] = lines
    report[key] = dict(firmware_apis=inst['n'], sf_named=len(names), decoded=len(lines), same_as_sf=same,
                       changed_since_sf=diff)
    print(key, report[key])
    for d in diffs[:12]:
        print('   changed', d[0], '\n      sf', d[1], '\n      e6', d[2])

json.dump(result, open(S + '/e6_layout.json', 'w'), indent=0)
json.dump(report, open(S + '/e6_layout_report.json', 'w'), indent=1)
