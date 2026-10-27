#!/usr/bin/env python3
"""Surgical, side-by-side rename of a decoded Samsung Notes tree.

Renames ONLY what must be unique for a second install:
  * the manifest package (applicationId)
  * every <provider android:authorities=...>
  * the app's own declared permissions (those under the old applicationId namespace)

It deliberately leaves component CLASS names (android:name / targetActivity),
intent actions, categories and meta-data untouched — those are not the
applicationId and renaming them breaks class loading or internal intents
(the earlier ClassNotFoundException on MemoApplication came from renaming them).

Replacements are exact, quoted-string matches ("X" -> "Y"), applied to the
manifest and every .smali file, so e.g. "com.samsung.android.app.notes" does
not match inside "com.samsung.android.app.notes.memolist.MemoListActivity".

Usage: rename.py <decoded_dir> <old_id> <new_id> [label]
"""
import os
import re
import sys


def main() -> int:
    work, old_id, new_id = sys.argv[1], sys.argv[2], sys.argv[3]
    label = sys.argv[4] if len(sys.argv) > 4 else "Claude Notes"
    manifest = os.path.join(work, "AndroidManifest.xml")
    with open(manifest, encoding="utf-8") as fh:
        mtext = fh.read()

    # Collect every authority declared in the manifest.
    authorities = set()
    for m in re.finditer(r'android:authorities="([^"]*)"', mtext):
        for a in m.group(1).split(";"):
            if a:
                authorities.add(a)

    # The app's own permissions (declared <permission>) under the old namespace.
    perms = set(re.findall(r'<permission[^>]*android:name="([^"]*)"', mtext))
    perms = {p for p in perms if p == old_id or p.startswith(old_id + ".")}

    def remap(s: str) -> str:
        if s == old_id or s.startswith(old_id + "."):
            return new_id + s[len(old_id):]
        # Authorities outside the app-id namespace must still be made unique,
        # or they collide with the stock app at install time.
        tail = s.rstrip(".").split(".")[-1]
        return new_id + "." + tail

    # Build the exact old->new string map. Longest first so that, where a value
    # is a prefix of another (an authority that is also a permission prefix),
    # the longer quoted string is handled on its own.
    mapping = {s: remap(s) for s in (authorities | perms)}
    ordered = sorted(mapping, key=len, reverse=True)

    def apply(text: str) -> str:
        for old in ordered:
            text = text.replace(f'"{old}"', f'"{mapping[old]}"')
        return text

    # Manifest: package attr, label, then the exact authority/permission strings.
    mtext = re.sub(r'package="%s"' % re.escape(old_id), 'package="%s"' % new_id, mtext, count=1)
    mtext = mtext.replace('android:label="@string/app_name"', 'android:label="%s"' % label)
    mtext = apply(mtext)
    with open(manifest, "w", encoding="utf-8") as fh:
        fh.write(mtext)

    # smali: the same exact authority/permission strings (class descriptors use
    # '/' and are never matched; actions/class-name string literals are left as is).
    changed = 0
    for root, _dirs, files in os.walk(work):
        if os.path.basename(root).startswith("smali") or "/smali" in root:
            pass
        for name in files:
            if not name.endswith(".smali"):
                continue
            path = os.path.join(root, name)
            with open(path, encoding="utf-8") as fh:
                t = fh.read()
            nt = apply(t)
            if nt != t:
                with open(path, "w", encoding="utf-8") as fh:
                    fh.write(nt)
                changed += 1

    print("renamed authorities=%d perms=%d smali_files=%d" % (len(authorities), len(perms), changed))
    print("package -> %s" % new_id)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
