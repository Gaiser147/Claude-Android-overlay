# Third-party components

## Bundled in the APK (`app/src/main/jniLibs/arm64-v8a/`)

These are unmodified binaries from Alpine Linux 3.22 (aarch64), except that `patchelf`
renamed the library files and their `NEEDED`/`SONAME` entries so Android packages them
(AGP only ships files named `lib*.so`). `scripts/fetch-native-deps.sh` rebuilds them
byte-for-byte from the pinned, sha256-checked Alpine packages.

| File | Package | License | Corresponding source |
|---|---|---|---|
| `libmuslld.so` | musl 1.2.5-r13 | MIT | https://musl.libc.org/releases/musl-1.2.5.tar.gz, Alpine build recipe: https://gitlab.alpinelinux.org/alpine/aports/-/tree/3.22-stable/main/musl |
| `libbashreal.so` | bash 5.2.37-r0 | GPL-3.0-or-later | https://ftp.gnu.org/gnu/bash/bash-5.2.tar.gz (+ patches 1-37), Alpine build recipe: https://gitlab.alpinelinux.org/alpine/aports/-/tree/3.22-stable/main/bash |
| `libreadline8.so` | readline 8.2.13-r1 | GPL-3.0-or-later | https://ftp.gnu.org/gnu/readline/readline-8.2.tar.gz (+ patches 1-13), Alpine build recipe: https://gitlab.alpinelinux.org/alpine/aports/-/tree/3.22-stable/main/readline |
| `libncursesw6.so` | ncurses 6.5_p20250503-r0 (libncursesw) | X11/MIT-style | https://invisible-island.net/ncurses/, Alpine build recipe: https://gitlab.alpinelinux.org/alpine/aports/-/tree/3.22-stable/main/ncurses |

`libbash.so` is a small shell script written for this project (MIT, see `LICENSE`).

## Downloaded at run time, not bundled

The **Claude Code CLI** (`@anthropic-ai/claude-code-linux-arm64-musl` from registry.npmjs.org)
is © Anthropic PBC, all rights reserved, and subject to Anthropic's terms. The app downloads
it on the user's request, the same way Claude Code's own npm installer does, and verifies the
registry's sha512 integrity hash. It is never redistributed with this project.

## Port

Based on [Claude Overlay](https://github.com/shengyanlin/claude-overlay) by shengyanlin (MIT).
