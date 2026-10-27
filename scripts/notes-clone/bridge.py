#!/usr/bin/env python3
"""Wires the Claude bridge (notesbridge.dex) into the clone.

  bridge.py queries <decoded_dir> <overlay_package>
      Lets the clone see the overlay app (Android 11+ package visibility), so the
      bridge can bind to it: adds <package> to the manifest's <queries>.

  bridge.py add-dex <apk> <dex>
      Adds the bridge to the built APK as the next classesN.dex (Android loads
      classes.dex, classes2.dex, … in order, so the number must follow the last one), or
      replaces the bridge an earlier run added.

Usage as above; both steps are idempotent.
"""
import re
import shutil
import sys
import tempfile
import zipfile


def queries(work: str, overlay: str) -> int:
    path = work + "/AndroidManifest.xml"
    with open(path, encoding="utf-8") as fh:
        m = fh.read()
    entry = '<package android:name="%s"/>' % overlay
    if entry in m:
        print("queries: already there")
        return 0
    if re.search(r"<queries>", m):
        m = m.replace("<queries>", "<queries>\n        " + entry, 1)
    else:
        m = m.replace("<application", "<queries>\n        %s\n    </queries>\n    <application" % entry, 1)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(m)
    print("queries: added", overlay)
    return 0


BRIDGE_CLASS = b"Lio/github/gaiser147/notesbridge/Bootstrap;"


def add_dex(apk: str, dex: str) -> int:
    with open(dex, "rb") as fh:
        payload = fh.read()
    target = None
    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        for n in names:
            if not re.fullmatch(r"classes\d*\.dex", n):
                continue
            data = z.read(n)
            if data == payload:
                print("add-dex: already in as", n)
                return 0
            # An older bridge from an earlier run: replace it, never add a second copy (Android
            # would load the first one it finds).
            if len(data) < 1_000_000 and BRIDGE_CLASS in data:
                target = n
    if target is None:
        numbers = [int(m.group(1) or 1) for n in names for m in [re.fullmatch(r"classes(\d*)\.dex", n)] if m]
        target = "classes%d.dex" % (max(numbers) + 1)
    # Rewrite instead of appending: an APK with an entry added after the signing block or with
    # a stale central directory is rejected by the installer.
    tmp = tempfile.NamedTemporaryFile(delete=False, suffix=".apk").name
    with zipfile.ZipFile(apk) as src, zipfile.ZipFile(tmp, "w") as out:
        for info in src.infolist():
            if info.filename != target:
                out.writestr(info, src.read(info.filename))
        out.writestr(zipfile.ZipInfo(target), payload, compress_type=zipfile.ZIP_DEFLATED)
    shutil.move(tmp, apk)
    print("add-dex: bridge is", target)
    return 0


def main() -> int:
    if len(sys.argv) == 4 and sys.argv[1] == "queries":
        return queries(sys.argv[2], sys.argv[3])
    if len(sys.argv) == 4 and sys.argv[1] == "add-dex":
        return add_dex(sys.argv[2], sys.argv[3])
    print(__doc__)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
