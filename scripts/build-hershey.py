#!/usr/bin/env python3
"""Builds app/src/main/assets/hershey/*.txt from the Hershey font distribution.

Inputs (from https://paulbourke.net/dataformats/hershey/): the glyph file `hershey`
(inside hershey.zip) and the ASCII maps `romans.hmp` / `scripts.hmp`.

Usage: build-hershey.py <hershey> <romans.hmp> <scripts.hmp> <out-dir>

Output: one line per character, "<code>\t<data>": printable ASCII from the map, then the
Greek letters and math symbols in EXTRA (the same glyphs for both styles). <data> is the
glyph's Hershey vertex string: a left/right bearing pair, then coordinate pairs encoded as
(char - 'R'), with " R" lifting the pen. Glyphs are unchanged, except that ≈ is two ~ stacked.
"""
import os
import sys


def read_glyphs(path):
    text = open(path, encoding="ascii").read().replace("\r", "")
    glyphs, i = {}, 0
    while i < len(text):
        if text[i] == "\n":
            i += 1
            continue
        num = int(text[i:i + 5])
        count = int(text[i + 5:i + 8])
        i += 8
        data = []
        while len(data) < 2 * count:
            if text[i] != "\n":
                data.append(text[i])
            i += 1
        glyphs[num] = "".join(data)
    return glyphs


GREEK_UPPER = "ΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡΣΤΥΦΧΨΩ"
GREEK_LOWER = "αβγδεζηθικλμνξοπρστυφχψω"

# Unicode character -> Hershey glyph number (simplex weight where the set has one).
EXTRA = {
    **{c: 527 + i for i, c in enumerate(GREEK_UPPER)},
    **{c: 627 + i for i, c in enumerate(GREEK_LOWER)},
    "θ": 685, "ϑ": 634, "ϕ": 686, "ϵ": 684, "ς": 644, "µ": 638,
    "∑": 545, "∏": 542, "∆": 530, "Ω": 550,
    "−": 2231, "±": 2233, "∓": 2234, "×": 2235, "·": 2236, "⋅": 2236, "÷": 2237,
    "≠": 2239, "≡": 2240, "≤": 2243, "≥": 2244, "∝": 2245,
    "√": 2255, "∂": 683, "∇": 583, "∫": 2268, "∮": 2269, "∞": 766,
    "°": 718, "′": 2216, "″": 2217,
    "→": 2261, "↑": 2262, "←": 2263, "↓": 2264,
    "⊂": 2256, "∪": 2257, "⊃": 2258, "∩": 2259, "∈": 2260, "∃": 2279,
    "∥": 737, "⊥": 738, "∠": 739, "∴": 740, "§": 2276, "⁄": 720,
}


def shifted(data, dy):
    """The glyph moved down by dy units (bearings unchanged)."""
    out, i = [data[:2]], 2
    while i + 1 < len(data):
        pair = data[i:i + 2]
        out.append(pair if pair == " R" else pair[0] + chr(ord(pair[1]) + dy))
        i += 2
    return "".join(out)


def extra_glyphs(glyphs):
    out = {c: glyphs[n] for c, n in EXTRA.items()}
    tilde = glyphs[2246]
    out["≈"] = shifted(tilde, -3) + " R" + shifted(tilde, 3)[2:]
    return out


def read_map(path):
    nums = []
    for token in open(path, encoding="ascii").read().split():
        if "-" in token:
            a, b = token.split("-")
            nums.extend(range(int(a), int(b) + 1))
        else:
            nums.append(int(token))
    return nums


def main(hershey, romans, scripts, out):
    glyphs = read_glyphs(hershey)
    extra = sorted(extra_glyphs(glyphs).items())
    for name, hmp in (("romans", romans), ("scripts", scripts)):
        nums = read_map(hmp)
        # The maps cover 32..127; DEL (the 96th) has no use here.
        assert len(nums) >= 95, (name, len(nums))
        with open(os.path.join(out, name + ".txt"), "w", encoding="ascii") as f:
            for code, num in zip(range(32, 127), nums):
                f.write("%d\t%s\n" % (code, glyphs[num]))
            for c, data in extra:
                f.write("%d\t%s\n" % (ord(c), data))


if __name__ == "__main__":
    main(*sys.argv[1:5])
