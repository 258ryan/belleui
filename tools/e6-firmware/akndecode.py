"""AknLayout2 scalable layout decoder (Python port of AknLayoutScalableDecodeSupport, the code in the
E6's aknlayout2scalable.dll, checked against its disassembly with REA).

Usage as a library:
    blob = ByteData(bytes)            # the KByteCodedData array of an instance
    line = decode(blob, offset, variety, col, row)
"""
import re

P = 0x4000
EMPTY = 0xC004  # -0x3FFC as uint16
K_LONG, K_P2, K_P1, K_EMPTY, K_WORD = 0xFB, 0xFC, 0xFD, 0xFE, 0xFF

WIN = ['C', 'l', 't', 'r', 'b', 'W', 'H']
TXT = ['C', 'l', 't', 'r', 'b', 'W', 'H', 'J', 'Font']
TYPES = {0: 'unknown', 1: 'screen', 2: 'container', 3: 'pane', 4: 'graphic', 5: 'text'}
IS_COL = [0, 0, 0, 1, 0, 1, 0, 1, 0, 0, 0]  # numCols numRows C l t r b W H J Font


class Cur:
    def __init__(self, d, i):
        self.d, self.i = d, i

    def byte(self):
        b = self.d[self.i]
        self.i += 1
        return b

    def value(self):
        """DecodeBytes: returns a list of 1 (or 2 for KByteLong) uint16 values."""
        ch = self.byte()
        if ch == K_WORD:
            h = self.byte(); l = self.byte()
            return [(h << 8) | l]
        if ch == K_EMPTY:
            return [EMPTY]
        if ch == K_P1:
            o = self.byte()
            if o > 127:
                o -= 256
            return [(P + o) & 0xFFFF]
        if ch == K_P2:
            h = self.byte(); l = self.byte()
            if h > 127:
                h -= 256
            return [(P + ((h << 8) | l)) & 0xFFFF]
        if ch == K_LONG:
            a, b, c, d = self.byte(), self.byte(), self.byte(), self.byte()
            return [(c << 8) | d, (a << 8) | b]
        return [ch]

    def int32(self):
        v = self.value()
        return v[0] | ((v[1] << 16) if len(v) > 1 else 0)

    def skip(self, n):
        for _ in range(n):
            self.value()


def sval(v):
    """uint16 cell -> python value: int, 'p+/-n' for parent-relative, None for empty."""
    if v == EMPTY:
        return None
    s = v - 0x10000 if v >= 0x8000 else v
    if s >= P - 0x2000:  # parent relative: p + offset, p - n
        return 'p%+d' % (s - P)
    return s


def decode_param(cell, params, nparams, multi, variety, voff):
    c = Cur(params.d, params.i)
    bit = 1 << cell
    for _ in range(nparams):
        bits = c.int32()
        if bits & bit:
            break
        c.int32()
    else:
        return 1, 0
    bf = c.int32()
    optv = bf & 1
    optc = bf & 2
    mx = bf >> 2
    actual = variety if optc else voff
    prm = 0 if optv else actual
    prm += 0 if optc else multi
    return mx, prm


def decode(d, off, variety=0, col=0, row=0):
    c = Cur(d, off)
    tb = c.byte()
    has_header = tb & 1
    typ = tb >> 1
    names = TXT if typ == 5 else WIN
    out = {'type': TYPES.get(typ, typ)}
    if not has_header:
        vals = []
        for _ in names:
            vals += c.value()
        out.update(zip(names, (sval(v) for v in vals)))
        if typ == 5 and len(vals) > 9:
            out['Font'] = vals[8] | (vals[9] << 16)
        out['varieties'] = 1
        return out
    nvar = c.byte()
    if not (0 <= variety < nvar):
        raise IndexError('variety %d of %d' % (variety, nvar))
    nmulti = c.byte()
    params = Cur(d, c.i)
    c.skip(nmulti * 2)
    voffs = []
    limits = []
    for ii in range(2):
        mx, prm = decode_param(ii, params, nmulti, 0, variety, variety)
        # DecodeParamLimitsCell
        vo = 0
        if prm:
            t = 0
            for _ in range(prm):
                t += c.int32()
            vo = t
        val = c.int32()
        if mx == 1:
            vo = variety * val
        post = mx - (prm + 1)
        c.skip(post)
        voffs.append(vo)
        limits.append(val)
    out['cols'], out['rows'] = limits
    out['varieties'] = nvar
    vals = []
    for ii in range(2, 2 + len(names)):
        multi = col if IS_COL[ii] else row
        mx, prm = decode_param(ii, params, nmulti, multi, variety, voffs[0 if IS_COL[ii] else 1])
        if not (0 <= prm < mx):
            raise IndexError('cell %d param %d max %d' % (ii, prm, mx))
        c.skip(prm)
        v = c.value()
        vals += v
        c.skip(mx - (prm + 1))
    out.update(zip(names, (sval(v) for v in vals)))
    if typ == 5 and len(vals) > 9:
        out['Font'] = vals[8] | (vals[9] << 16)
    return out


# ---- SF generated-source parsing ---------------------------------------------------------------

def parse_bytes(path):
    """KByteCodedData_* array from a generated instance .cpp."""
    txt = open(path, encoding='latin-1').read()
    m = re.search(r'KByteCodedData_\w+\[\]\s*=\s*\{(.*?)\n\};', txt, re.S)
    body = re.sub(r'//[^\n]*', '', m.group(1))
    return bytes(int(x, 16) for x in re.findall(r'0x([0-9a-fA-F]{2})\b', body))


def parse_lookup(path):
    """(base offset, [(name, absolute offset)]) from a generated instance .cpp."""
    txt = open(path, encoding='latin-1').read()
    base = int(re.search(r'KImplData = \{ KDataLookup, [\w:]+ \+ 0x([0-9a-fA-F]+)', txt).group(1), 16)
    m = re.search(r'const TUint16 KDataLookup\[[^\]]*\] =\s*\{(.*?)\n\s*\};', txt, re.S)
    rows = re.findall(r'0x([0-9a-fA-F]+),\s*// \(0x([0-9a-fA-F]+)\) (\w+)', m.group(1))
    return base, [(name, int(rel, 16), int(ab, 16)) for rel, ab, name in rows]
