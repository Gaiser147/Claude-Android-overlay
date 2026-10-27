#!/usr/bin/env python3
"""Surgical, side-by-side rename of a decoded Samsung Notes tree.

Renames ONLY what must be unique for a second install:
  * the manifest package (applicationId)
  * every <provider android:authorities=...>
  * the app's own declared permissions (those under the old applicationId namespace)

Component CLASS names (android:name / targetActivity), intent actions,
categories, meta-data and intent data stay untouched. In the manifest the
replacement is attribute-aware: values are only rewritten inside
android:authorities, android:permission / readPermission / writePermission and
the android:name of <permission> / <uses-permission>. That matters because
some authorities equal a class name (SettingsAiProvider), and a plain string
replace would rename the provider class too -> ClassNotFoundException.

In smali, the same old->new values are replaced as exact quoted strings
("X" -> "Y"); values that are also component class names are skipped there,
since such a string may be used to load the class.

Usage: rename.py <decoded_dir> <old_id> <new_id> [label]
"""
import os
import re
import sys

ANDROID = "http://schemas.android.com/apk/res/android"


def main() -> int:
    work, old_id, new_id = sys.argv[1], sys.argv[2], sys.argv[3]
    label = sys.argv[4] if len(sys.argv) > 4 else "Claude Notes"
    manifest = os.path.join(work, "AndroidManifest.xml")
    with open(manifest, encoding="utf-8") as fh:
        mtext = fh.read()

    authorities = set()
    for m in re.finditer(r'android:authorities="([^"]*)"', mtext):
        authorities.update(a for a in m.group(1).split(";") if a)

    perms = set(re.findall(r'<permission\b[^>]*?android:name="([^"]*)"', mtext))
    perms = {p for p in perms if p == old_id or p.startswith(old_id + ".")}

    component_tags = r'(?:application|activity|activity-alias|service|receiver|provider)'
    class_names = set()
    for tag in re.finditer(r'<%s\b[^>]*>' % component_tags, mtext):
        for attr in ("name", "targetActivity"):
            m = re.search(r'android:%s="([^"]*)"' % attr, tag.group(0))
            if m:
                class_names.add(m.group(1))

    def remap(s: str) -> str:
        if s == old_id or s.startswith(old_id + "."):
            return new_id + s[len(old_id):]
        # Authorities outside the app-id namespace must still be unique,
        # or they collide with the stock app at install time.
        return new_id + "." + s.rstrip(".").split(".")[-1]

    mapping = {s: remap(s) for s in (authorities | perms)}

    # --- manifest, attribute-aware ---
    def sub_attr(attr: str, text: str) -> str:
        def repl(m: re.Match) -> str:
            parts = m.group(1).split(";")
            return 'android:%s="%s"' % (attr, ";".join(mapping.get(p, p) for p in parts))
        return re.sub(r'android:%s="([^"]*)"' % attr, repl, text)

    for attr in ("authorities", "permission", "readPermission", "writePermission"):
        mtext = sub_attr(attr, mtext)

    def perm_tag(m: re.Match) -> str:
        tag = m.group(0)
        return re.sub(
            r'android:name="([^"]*)"',
            lambda n: 'android:name="%s"' % mapping.get(n.group(1), n.group(1)),
            tag,
            count=1,
        )

    mtext = re.sub(r'<(?:permission|uses-permission)\b[^>]*>', perm_tag, mtext)
    mtext = re.sub(r'package="%s"' % re.escape(old_id), 'package="%s"' % new_id, mtext, count=1)
    mtext = mtext.replace('android:label="@string/app_name"', 'android:label="%s"' % label)
    with open(manifest, "w", encoding="utf-8") as fh:
        fh.write(mtext)

    # --- smali, exact quoted strings, never class names ---
    smali_map = {k: v for k, v in mapping.items() if k not in class_names}
    ordered = sorted(smali_map, key=len, reverse=True)
    changed = 0
    for root, _dirs, files in os.walk(work):
        for name in files:
            if not name.endswith(".smali"):
                continue
            path = os.path.join(root, name)
            with open(path, encoding="utf-8") as fh:
                t = fh.read()
            nt = t
            for old in ordered:
                nt = nt.replace('"%s"' % old, '"%s"' % smali_map[old])
            if nt != t:
                with open(path, "w", encoding="utf-8") as fh:
                    fh.write(nt)
                changed += 1

    print("renamed authorities=%d perms=%d smali_files=%d (skipped class-name values: %d)"
          % (len(authorities), len(perms), changed, len(mapping) - len(smali_map)))
    print("package -> %s" % new_id)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
