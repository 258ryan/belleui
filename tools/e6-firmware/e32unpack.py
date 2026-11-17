"""Unpacks a Symbian E32 image (byte-pair compressed) into a flat file: header + code + data.
Ported from EKA2L1 (src/emu/common/src/bytepair.cpp, src/emu/loader/src/e32img.cpp).
Writes <out> and prints the section layout as JSON."""
import json
import struct
import sys

BYTEPAIR = 0x102822AA
DEFLATE = 0x101F7AFC


def bp_page(buf):
    first = list(range(256))
    second = [0] * 256
    i = 0
    total = buf[i]; i += 1
    marker = -1
    if total:
        marker = buf[i]; i += 1
        first[marker] = (~marker) & 0xFF
        if total < 32:
            for _ in range(total):
                b, p1, p2 = buf[i], buf[i + 1], buf[i + 2]
                i += 3
                first[b] = p1
                second[b] = p2
        else:
            mask = buf[i:i + 32]
            i += 32
            for b in range(256):
                if mask[b >> 3] & (1 << (b & 7)):
                    first[b] = buf[i]
                    second[b] = buf[i + 1]
                    i += 2
    out = bytearray()

    def expand(b):
        st = [b]
        while st:
            x = st.pop()
            if first[x] == x:
                out.append(x)
            else:
                st.append(second[x])
                st.append(first[x])

    while i < len(buf):
        b = buf[i]; i += 1
        if b == marker:
            out.append(buf[i]); i += 1
        else:
            expand(b)
    return bytes(out)


def bp_stream(data, off):
    size_of_data, decomp, npages = struct.unpack_from('<iiH', data, off)
    off += 10
    sizes = struct.unpack_from('<%dH' % npages, data, off)
    off += 2 * npages
    out = bytearray()
    for s in sizes:
        out += bp_page(data[off:off + s])
        off += s
    return bytes(out), off


def main():
    src, dst = sys.argv[1], sys.argv[2]
    d = open(src, 'rb').read()
    (uid1, uid2, uid3, chk, sig, cpu, ver, comp) = struct.unpack_from('<8I', d, 0)
    flags, code_size, data_size = struct.unpack_from('<3I', d, 0x2C)
    bss, entry, code_base, data_base, dllref, expoff, expcount, text_size, code_off, data_off, imp_off, crel, drel = \
        struct.unpack_from('<13I', d, 0x44)
    info = dict(uid3=hex(uid3), compression=hex(comp), code_size=code_size, data_size=data_size, text_size=text_size,
                code_base=hex(code_base), data_base=hex(data_base), entry=hex(entry), code_offset=code_off,
                data_offset=data_off, export_dir_offset=expoff, export_count=expcount, import_offset=imp_off)
    if comp == BYTEPAIR:
        code, o = bp_stream(d, code_off)
        rest, _ = bp_stream(d, o)
        flat = d[:code_off] + code + rest
    elif comp == 0:
        flat = d
    else:
        raise SystemExit('unsupported compression %x' % comp)
    open(dst, 'wb').write(flat)
    info['flat_size'] = len(flat)
    print(json.dumps(info))


main()
