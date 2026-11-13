# Third-party notices

Luma itself is licensed under **GPL-3.0-only** (see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE)).
This file lists the third-party code, binaries, fonts and trademarks that the source tree
contains, builds on, or that end up inside a Luma APK. It is maintained by hand; if you find
an omission, please open an issue.

Legend: **in repo** = source or license text is committed here. **at build time** = fetched or
generated while packaging and *not* committed, but
shipped inside release APKs, so the obligations below apply to every release.

## 1. Code vendored or adapted in this repository

| Component | Where | License | Notes |
|---|---|---|---|
| Termux `terminal-emulator` / `terminal-view` (via jeffusion/Termix commit `4bcbac08fcd15c91c65d50df90159b45544433c3`) | `shared/terminal-emulator`, `shared/terminal-view`, `shared/ui` | GPL-3.0-only; parts derived from jackpal's Android-Terminal-Emulator are Apache-2.0 | Original Termux statement: `apps/gui/COPYING`; the license file that came with the Termix source: `apps/gui/UPSTREAM-LICENSE` (MIT, Copyright (c) 2024 Rohit Kushvaha). Local changes are described in `apps/gui/NOTICE.md`. Image-protocol model adapted from Termux PR #2973 (commit `d8d6b02aceb70e80588cadd7b79507676f1a95fc`). |
| Termux native `libtermux` (PTY JNI) source | `shared/terminal-emulator/upstream-native-source` | GPL-3.0-only | Built directly from `termux.c` via `shared/terminal-emulator/build-native.sh`. |
| Ghostty (`libghostty-vt`, pinned `0e6afb2600d4`) + patch backporting upstream `e257bb4` (GHSA-w9q4-5jvv-mcv4) | `shared/terminal-ghostty` (patch, JNI glue, Zig glue; Ghostty itself is fetched at build time) | MIT, Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors | License text: `shared/terminal-ghostty/assets/Ghostty-LICENSE.txt`. |
| Material Color Utilities (Java subset, commit `158b9c185a25c53fddd051cba811d542e6b94d68`) | `apps/gui/app/src/main/java/io/github/modao7067/luma/gui/color/mcu` | Apache-2.0 | Headers retained; license in `apps/gui/app/src/main/assets/licenses/`. |
| compose-miuix-ui v0.9.4 (`39c40f99844227b853f0049a0933b1f3ae6c00ba`): squircle path, overscroll, spring helpers, blur/highlight/lens math | `…/gui/ui/shapes`, `…/gui/ui/thirdparty/miuix`, glass helpers | Apache-2.0 | Only small, adapted extracts; no Miuix UI library is linked. Details: `apps/gui/third-party/`. |
| SukiSU-Ultra bottom-bar glass parameters (`e67b14e6bbbbd3e67f24df658a01d3e73a64159c`) | `apps/gui/third-party/sukisu-glass`, `LiquidChrome.kt`, `SukiGlassDefaults.kt` | Apache-2.0 (only the Apache-licensed files/algorithms were adapted) | The SukiSU-Ultra repository as a whole is GPL; no GPL business/navigation code or icons were copied. |
| AndroidX Material3 1.4.0 dropdown-menu shell and motion tokens | `apps/gui/third-party/androidx-material-menu` | Apache-2.0 | |
| Material Design Icons (commit `10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7`), selected outlined icons as VectorDrawables | `apps/gui/app/src/main/res/drawable/ic_menu_*` | Apache-2.0 | License: `apps/gui/app/src/main/res/raw/material_icons_license.txt`. |
| ego-lite `action-target.ts` helpers (CitroLabs, revision `12407167f84a58af24044f072868051952131491`) | `apps/gui/app/src/main/assets/browser/actions.js` | MIT | License: `…/assets/browser/ego-lite-MIT.txt`. |
| claude-agent-sdk-python `sessions.py` transcript projection (revision `d38e51e`) | `…/cli_bridge/luma_cli/claude_history.py` | MIT, Copyright Anthropic | License: `…/assets/cli_bridge/CLAUDE-SDK-LICENSE.txt`. |
| Provider / CLI icons: Lobe Icons (`@lobehub/icons-static-svg` 1.102.0), pi artwork, CodeBuddy mark | `apps/gui/app/src/main/res/drawable/ic_provider_*` | MIT (the SVG files); brand marks remain trademarks of their owners | Inventory and revisions: `apps/gui/PROVIDER-ICONS.md`; licenses in `apps/gui/app/src/main/res/raw/provider_*_license.txt`. See "Trademarks". |
| Shizuku-compatible provider / binder-container stubs | `runtime/noroot/src/main/java/moe/shizuku`, `…/rikka/shizuku` | Apache-2.0 | Minimal clean-room implementations of the public Shizuku Binder IPC contract (`SPDX-License-Identifier: Apache-2.0`). |
| Gradle wrapper | `gradle/wrapper` | Apache-2.0 | |
| Android NDK notice text | `runtime/noroot/src/main/assets/licenses/Android-NDK-NOTICE.txt` | NDK licenses | |

## 2. Libraries resolved by Gradle (not committed)

OkHttp 5.5.1, Okio and kotlinx-coroutines 1.11.0, kotlinx-serialization-json 1.8.1, AndroidX / Jetpack Compose (BOM 2026.09.00, runtime 1.12.1, activity-compose 1.13.0, Material 3, material-icons-core 1.7.8), AndroidSVG 1.4, `dev.snipme:highlights` 1.0.0: Apache-2.0.
CommonMark Java 0.32.0 (+ GFM tables/strikethrough extensions): BSD-2-Clause.
`ru.noties:jlatexmath-android` 0.2.0: GPL-2.0-or-later **with linking exception** (text: `apps/gui/app/src/main/assets/licenses/JLatexMath-Android-LICENSE.txt`); its bundled fonts keep their own licenses.
XZ for Java 1.12: 0BSD (`runtime/noroot/src/main/assets/XZ-Java-LICENSE.txt`).
Exact versions are in `gradle/gui-dependencies.gradle`, `runtime/*/build.gradle` and `tools/toolchain.json`.

## 3. Binaries and assets produced at build time (shipped in APKs)

| Component | License | Obligation for releases |
|---|---|---|
| PRoot 5.1.107.92 (+ Luma patches), talloc 2.4.3, libandroid-shmem 0.7 (`libproot_exec.so`, `libproot_loader.so`) | PRoot: GPL-2.0-or-later (combined executable GPL-3.0-or-later); talloc: LGPL-3.0-or-later; libandroid-shmem: BSD | Provide corresponding source, patches and build recipe (`tools/native/proot-r5`, `proot-r6`); keep license texts (`runtime/noroot/src/main/assets/licenses/`). PRoot is executed as a separate program; it is not linked into Luma's own code. |
| Debian ARM64 minimal root filesystem (`debian-arm64.zip.xz`) incl. bat, Glow, fzf, gawk, ripgrep, fd, Python, git, curl and others | Per package (GPL, LGPL, BSD, MIT, …) | Package versions: `runtime/noroot/src/main/assets/debian-packages.tsv`. Per-package notices stay inside the image under `/usr/share/doc`. For every release, link to the Debian source packages (https://deb.debian.org/debian/, https://deb.debian.org/debian-security/) for these exact versions. |
| Node.js 24.22.1, npm 12.2.1, pnpm 12.15.0 (inside the rootfs / `assets/pnpm`) | Node: MIT; npm: Artistic-2.0; pnpm: MIT | Keep license files (`/usr/local/LICENSE` in the image, `THIRD-PARTY-NOTICES.md` in pnpm). |
| tmux 3.5a-3, libevent-core 2.1.12, jemalloc 5.3.0-3 (`cli_bridge/native/tmux-debian-arm64.zip`) | ISC / BSD-3-Clause / BSD-2-Clause | Debian copyright files are inside the archive; recipe `tools/build-native-tmux.py`, package pins `tools/native/tmux/packages.json`. |
| `libluma_vt_jni.so` (Ghostty + Luma JNI) | MIT (Ghostty) + GPL-3.0-only (Luma glue) | Built with `shared/terminal-ghostty/build-native.py`. |
| `libtermux.so` | GPL-3.0-only | Built from `shared/terminal-emulator/upstream-native-source/termux.c` via `shared/terminal-emulator/build-native.sh`. |

### Fonts (all fetched / generated at build time, none committed)

| Font | License |
|---|---|
| Maple Mono NL NF CN 7.9 (Copyright 2022 The Maple Mono Project Authors; CN glyphs based on Resource Han Rounded) | SIL OFL 1.1 (`MapleMono-OFL.txt`) |
| JetBrains Mono NL Nerd Font Mono (Nerd Fonts v3.5.1) | SIL OFL 1.1 (`JetBrainsMono-NerdFont-OFL.txt`) |
| Noto Sans Mono, Noto Sans Symbols, Noto Sans Math, Noto Music, Noto Sans Symbols 2 (`notofonts/noto-fonts` @ `ffebf8c1ee449e544955a7e813c54f9b73848eac`) | SIL OFL 1.1 (`Noto-OFL.txt`) |
| Noto Color Emoji (`googlefonts/noto-emoji` @ `99701e07d3d1fe1bdbcdb14964547f0fd592966f`) | SIL OFL 1.1 (`NotoColorEmoji-OFL.txt`) |
| Luma CJK Forms (renamed subset of Noto Sans CJK SC 2.004) | SIL OFL 1.1 (`LumaCjkForms-OFL.txt`) |
| Luma GUI Han (`LumaGuiHan-Regular.ttf`, derived from Maple Mono, generated by `tools/build-gui-font.py`) | SIL OFL 1.1 (`LumaGuiHan-OFL.txt`) |
| Luma Connector Symbols (subset of Sarasa Term SC Nerd; no longer packaged) | SIL OFL 1.1 (`LumaConnectorSymbols-OFL.txt`) |

All Noto fonts are fetched by `tools/fetch-fonts.py` from the pinned upstream commits above and verified
against the SHA-256 values in `terminal-fonts-manifest.json`.
OFL fonts must not be sold on their own, and derived fonts must not use a Reserved Font Name
unless the license allows it; check the OFL file next to each font before changing a font.

### PRoot source distribution

Release APKs of Luma (NoRoot) bundle the unmodified upstream archives (`proot.tgz`, `talloc.tgz`, `shmem.tgz`) together with `proot-recipes.tgz` (deterministically generated from `tools/native/proot-r5` and `tools/native/proot-r6` via `tools/native/proot-r6/package-sources.py`) under `assets/native-sources/`, and retain component license texts in `assets/licenses/`.

## 4. Trademarks

Luma is an independent project. It is **not affiliated with, endorsed by or sponsored by** OpenAI, Anthropic, Google, GitHub, Microsoft, Moonshot AI, Alibaba, DeepSeek, xAI, Canonical or any other company.
"Codex", "OpenAI", "Claude", "Claude Code", "Anthropic", "Gemini", "Antigravity", "Kimi", "DeepSeek", "Grok", "OpenCode", "Qoder", "CodeBuddy", "Ubuntu" and other product names, logos and icons are trademarks or registered trademarks of their respective owners. They are used here only to name the third-party command-line tools that Luma can drive and, in the case of icons, to identify them in the UI (nominative use). Icon sources and revisions are listed in `apps/gui/PROVIDER-ICONS.md`. If an owner objects to an icon, remove the matching `ic_provider_*` drawable and its mapping in `ProviderIcon.kt`. Luma does not bundle any of these CLIs.
