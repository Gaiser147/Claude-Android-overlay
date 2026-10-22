#!/usr/bin/env python3
"""Builds app/src/main/assets/hershey/*.txt from the Hershey font distribution.

Inputs (from https://paulbourke.net/dataformats/hershey/): the glyph file `hershey`
(inside hershey.zip) and the ASCII maps `romans.hmp` / `scripts.hmp`.

Usage: build-hershey.py <hershey> <romans.hmp> <scripts.hmp> <out-dir>

Output: one line per printable ASCII character, "<code>\t<data>", where <data> is the
glyph's Hershey vertex string unchanged: a left/right bearing pair, then coordinate pairs
encoded as (char - 'R'), with " R" lifting the pen.
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
    for name, hmp in (("romans", romans), ("scripts", scripts)):
        nums = read_map(hmp)
        # The maps cover 32..127; DEL (the 96th) has no use here.
        assert len(nums) >= 95, (name, len(nums))
        with open(os.path.join(out, name + ".txt"), "w", encoding="ascii") as f:
            for code, num in zip(range(32, 127), nums):
                f.write("%d\t%s\n" % (code, glyphs[num]))


if __name__ == "__main__":
    main(*sys.argv[1:5])
