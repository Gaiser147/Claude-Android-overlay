#!/usr/bin/env python3
"""Fails if a component class named in the manifest has no smali file.

Catches the ClassNotFoundException-at-startup class of mistakes (a rename that
touched a class name) before the APK is built and installed. For an
<activity-alias>, android:name is only the alias, so only targetActivity is
checked.

Usage: check_components.py <decoded_dir>
"""
import os
import re
import sys


def main() -> int:
    work = sys.argv[1]
    with open(os.path.join(work, "AndroidManifest.xml"), encoding="utf-8") as fh:
        m = fh.read()
    pkg = re.search(r'package="([^"]*)"', m).group(1)
    dirs = [d for d in os.listdir(work) if d.startswith("smali")]
    missing, checked = [], 0
    tags = r'<(activity-alias|application|activity|service|receiver|provider)\b[^>]*>'
    for tag in re.finditer(tags, m):
        attrs = ("targetActivity",) if tag.group(1) == "activity-alias" else ("name",)
        for attr in attrs:
            a = re.search(r'android:%s="([^"]*)"' % attr, tag.group(0))
            if not a:
                continue
            cls = a.group(1)
            if cls.startswith("."):
                cls = pkg + cls
            checked += 1
            rel = cls.replace(".", "/") + ".smali"
            if not any(os.path.exists(os.path.join(work, d, rel)) for d in dirs):
                missing.append("%s %s" % (tag.group(1), cls))
    print("components checked=%d missing=%d" % (checked, len(missing)))
    for x in missing:
        print("  MISSING:", x)
    return 1 if missing else 0


if __name__ == "__main__":
    raise SystemExit(main())
