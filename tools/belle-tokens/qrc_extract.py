#!/usr/bin/env python3
"""Pair qrc tree entries with blobs carved from the image by data order. usage: qrc_extract.py image outdir"""
import sys, struct, zlib, pathlib, re
img = open(sys.argv[1], 'rb').read(); out = pathlib.Path(sys.argv[2])
# ---- names + tree (same logic as qrc_extract.py)
def name_at(o):
    ln, = struct.unpack_from('>H', img, o); return img[o+6:o+6+2*ln].decode('utf-16-be'), 6+2*ln
start = img.find('params'.encode('utf-16-be')) - 6
names = {}; o = start
while True:
    try:
        n, sz = name_at(o)
        if not n or not re.fullmatch(r'[\x20-\x7e]+', n): break
    except Exception: break
    names[o - start] = n; o += sz
def find_tree():
    for p in range(0, len(img) - 14, 2):
        no, fl = struct.unpack_from('>IH', img, p)
        if no == 0 and fl == 2:
            cnt, first = struct.unpack_from('>II', img, p + 6)
            if 0 < cnt < 8 and first == 1 and all(struct.unpack_from('>I', img, p + 14*(first+k))[0] in names for k in range(cnt)): return p
tree = find_tree(); assert tree is not None
files = []
def walk(idx, path):
    p = tree + 14*idx; no, fl = struct.unpack_from('>IH', img, p); a, b = struct.unpack_from('>II', img, p + 6)
    nm = names.get(no, '')
    if fl & 2:
        for k in range(a): walk(b + k, path + [nm] if nm else path)
    else: files.append((b, path + [nm]))
walk(0, [])
files.sort()
# ---- carve blobs in memory order: u32 BE length + payload (raw text/svg, or u32 usize + zlib)
blobs = []
for m in re.finditer(rb'<\?xml', img):
    p = m.start(); ln, = struct.unpack_from('>I', img, p - 4)
    if 0 < ln < 400000 and p + ln <= len(img): blobs.append((p, img[p:p+ln]))
for m in re.finditer(rb'graphic-size-large:[\x09\x0a\x0d\x20-\x7e]*', img):
    blobs.append((m.start(), m.group()))
for m in re.finditer(rb'\x78[\x01\x5e\x9c\xda]', img):
    p = m.start()
    try: r = zlib.decompressobj().decompress(img[p:p+400000])
    except Exception: continue
    if len(r) > 20 and all(c in b'\t\r\n' or 32 <= c < 127 for c in r[:200]): blobs.append((p, r))
blobs.sort(key=lambda x: x[0])
print(len(files), 'entries', len(blobs), 'blobs')
n = 0
if len(files) == len(blobs):
    for (off, path), (pos, data) in zip(files, blobs):
        p = out.joinpath(*path); p.parent.mkdir(parents=True, exist_ok=True); p.write_bytes(data); n += 1
print(n, 'written')
