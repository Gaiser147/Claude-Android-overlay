#!/usr/bin/env bash
# Rebuilds app/src/main/jniLibs/arm64-v8a from pinned Alpine Linux (aarch64) packages.
#
# Why these exist: the official Claude Code CLI for linux-arm64-musl needs musl's dynamic
# loader, and its Bash tool insists on a bash (the path must contain "bash"). Android has
# neither. Everything in nativeLibraryDir may be exec'd by the app (unlike files it
# downloads itself), so the loader, bash and bash's two libraries ship there as lib*.so:
#
#   libmuslld.so      musl ld.so / libc            (musl, MIT)
#   libbashreal.so    bash 5.2                     (GPL-3.0-or-later, source: Alpine aports)
#   libreadline8.so   readline (NEEDED renamed)    (GPL-3.0-or-later)
#   libncursesw6.so   ncursesw (NEEDED renamed)    (MIT-style ncurses license)
#   libbash.so        tiny sh wrapper, committed as source in this repo — not built here
#
# AGP only packages files named lib*.so, so the libraries are renamed and their NEEDED
# entries rewritten with patchelf to match. Requires: curl, tar, sha256sum, patchelf.
set -euo pipefail

MIRROR="https://dl-cdn.alpinelinux.org/alpine/v3.22/main/aarch64"
OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/arm64-v8a"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# name|version|sha256 of the .apk
PKGS=(
  "musl|1.2.5-r13|d616c430bcbe0bf452b7f6ea2669f2cad14e85644a7599f4f495115d021742ef"
  "bash|5.2.37-r0|411e1fec2dccd603bc9f23586f7b8df2211613ece49b20c71c17412ab2667c44"
  "readline|8.2.13-r1|334af29dbf6b5a71a87af4d6a58e2967a8f711a51d00093de0e1498daf83ceb2"
  "libncursesw|6.5_p20250503-r0|419b375e8a4345e7172b1f0f3a3c57db61374f5408cdb875d9e860bd4c243aca"
)

for entry in "${PKGS[@]}"; do
  IFS='|' read -r name ver sha <<<"$entry"
  apk="$WORK/$name.apk"
  curl -fsSL -o "$apk" "$MIRROR/$name-$ver.apk"
  echo "$sha  $apk" | sha256sum -c --quiet -
  mkdir -p "$WORK/root"
  # An .apk is concatenated gzip'd tars (signature, control, data); GNU tar reads them all.
  tar -xzf "$apk" -C "$WORK/root" --warning=no-unknown-keyword 2>/dev/null || true
done

R="$WORK/root"
mkdir -p "$OUT"
install -m 0755 "$R/lib/ld-musl-aarch64.so.1" "$OUT/libmuslld.so"
install -m 0755 "$R/bin/bash" "$OUT/libbashreal.so"
install -m 0755 "$(readlink -f "$R/usr/lib/libreadline.so.8")" "$OUT/libreadline8.so"
install -m 0755 "$(readlink -f "$R/usr/lib/libncursesw.so.6")" "$OUT/libncursesw6.so"

patchelf --replace-needed libreadline.so.8 libreadline8.so "$OUT/libbashreal.so"
patchelf --replace-needed libncursesw.so.6 libncursesw6.so "$OUT/libreadline8.so"
patchelf --set-soname libreadline8.so "$OUT/libreadline8.so"
patchelf --set-soname libncursesw6.so "$OUT/libncursesw6.so"

(cd "$OUT" && sha256sum libmuslld.so libbashreal.so libreadline8.so libncursesw6.so)
