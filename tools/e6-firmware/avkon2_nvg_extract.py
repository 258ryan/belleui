#!/usr/bin/env python3
"""Cuts the NVG icons out of a Symbian .mif (e.g. resource/apps/avkon2.mif of the E6 dump) into numbered .nvg files.

usage: avkon2_nvg_extract.py avkon2.mif outdir

A .mif is a series of 'C##4' icon blocks; the Symbian^3 ones carry NVG ('nvg' + version). Convert them to SVG with
https://github.com/WunderWungiel/NVG-Decoder   (node web/cli.js outdir/ -d svg/), then render the SVGs as you like.
The 642 icons of the E6's avkon2.mif, already converted, are in avkon2-svg.zip (index = order in the file; no names, the
Avkon .mbg that names them is not in the dump): 80 bluetooth, 159 usb, 115 headset, 360 wlan, 493 mute, 75 alarm, 94 mail,
488 offline, 191 gps, 230 3G, 414 3.5G, 415 E ... see tools/e6-firmware/README for the contact sheets' reading.
"""
import re, struct, sys, pathlib
src, out = sys.argv[1:3]
b = open(src, 'rb').read()
pathlib.Path(out).mkdir(parents=True, exist_ok=True)
n = 0
for m in re.finditer(rb'C##4', b):
    h = m.start()
    ver, hl, dl, typ = struct.unpack_from('<IIII', b, h + 4)
    data = b[h + hl:h + hl + dl]
    if data[:3] == b'nvg':
        open(f'{out}/i{n:04d}.nvg', 'wb').write(data)
        n += 1
print(n, 'icons')
