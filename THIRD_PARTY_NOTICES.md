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
| PRoot 5.1.107.92 (`termux/proot` commit `7266fb3e8516535682f5a9c8f3a7e70f6506eddb`; upstream reference `b27d81a68d765f3462e807f4a14e01a03f8e89ea`) + Luma Bionic, `fchmodat2`, `fake_id0` `SO_PEERCRED` (`getsockopt`) and native hard-link adaptations | `runtime/noroot/third-party/proot` | GPL-2.0-or-later (combined standalone executable GPL-3.0-or-later) | Built as separate standalone executables (`libproot_exec.so`, `libproot_loader.so`) via `tools/build-native-proot.py`; not linked into Luma's own code. License texts: `runtime/noroot/third-party/proot/COPYING` and `runtime/noroot/src/main/assets/licenses/`. |
| Termux `libandroid-shmem` (`v0.7`) + Luma `PROOT_TMP_DIR` and lazy `libandroid.so` adaptations | `runtime/noroot/third-party/libandroid-shmem` | BSD | Statically linked into `libproot_exec.so` by `tools/build-native-proot.py`. License texts: `runtime/noroot/third-party/libandroid-shmem/LICENSE` and `runtime/noroot/src/main/assets/licenses/`. |
| Ghostty (`libghostty-vt`, pinned `0e6afb2600d4`) + patch backporting upstream `e257bb4` (GHSA-w9q4-5jvv-mcv4) | `shared/terminal-ghostty` (patch, JNI glue, Zig glue; Ghostty itself is fetched at build time) | MIT, Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors | License text: `shared/terminal-ghostty/assets/Ghostty-LICENSE.txt`. |
| Material Color Utilities (Java subset, commit `158b9c185a25c53fddd051cba811d542e6b94d68`) | `apps/gui/app/src/main/java/io/github/modao7067/luma/gui/color/mcu` | Apache-2.0 | Headers retained; license in `apps/gui/app/src/main/assets/licenses/`. The 22-file dependency closure is inventoried in `apps/gui/third-party/material-color-utilities/upstream-files.json`. Updates must preserve the algorithms, AndroidX nullability annotations and local namespace adaptation. |
| compose-miuix-ui v0.9.4 (`39c40f99844227b853f0049a0933b1f3ae6c00ba`): squircle path, overscroll, spring helpers | `…/gui/ui/shapes`, `…/gui/ui/thirdparty/miuix` | Apache-2.0 | Only package names and unused pull-to-refresh integration are changed in the squircle and overscroll extracts; no Miuix UI library is linked. Update `Overscroll.kt` and `SpringUtils.kt` together and keep their damping, spring, gesture and layer-translation behavior unchanged. |
| prism_ui (`chen08209/prism_ui` commit `6c47901cc80b5325bdb9650579ca2e000a60b39e`): Liquid Glass shader/optics, outside-only dock/menu shadows, `ElasticPress`, `PopupMorph` / droplet return, carried popup press and `BarItemGlyph` pulse motion | `ui/PrismGlass.kt`, `ui/LiquidChrome.kt`, `ui/ElasticPress.kt`, `ui/MorphMenu.kt`, `ui/PrismMotion.kt`, opening/dismissal motion in `ui/OriginMenu.kt` | GPL-3.0 | Dart/GLSL behavior re-implemented in Kotlin, Compose `graphicsLayer` and AGSL; superellipse SDF, refraction, specular rim, hairline, downscaled frosted backdrop, press glow, dock/menu shadows, spring constants, rubber band, morph geometry and droplet smooth-min follow `shaders/liquid_glass.frag`, `lib/liquid_glass.dart`, `lib/liquid_glass_capture.dart`, `lib/navigation_dock.dart`, `lib/popup.dart`, `lib/goo.dart`, `lib/motion.dart` and `lib/bar_item_presence.dart`. License text: `apps/gui/third-party/prism-ui/LICENSE-GPL-3.0.txt`. |
| AndroidX Material3 1.4.0 dropdown-menu shell and motion tokens (`material3-android-1.4.0-sources.jar`) | `ui/OriginMenu.kt` | Apache-2.0 | Adapts `AndroidMenu.android.kt`, `Menu.kt` and `tokens/StandardMotionTokens.kt` without reflection or suppressed visibility. Preserve native Popup ownership, reversible animation, actual viewport bounds, natural menu height and explicit width during upstream updates. |
| Material Design Icons (commit `10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7`), selected outlined and rounded icons as VectorDrawables | `apps/gui/app/src/main/res/drawable/ic_menu_*` | Apache-2.0 | Source SVGs use `src/<category>/<name>/materialiconsoutlined/24px.svg`, `materialiconsround/24px.svg` (and `symbols/android/edit_document/materialsymbolsoutlined/edit_document_24px.xml` for `ic_menu_edit_document`); transparent paths are omitted and visible paths retain their upstream geometry. License: `apps/gui/app/src/main/res/raw/material_icons_license.txt`. |
| ego-lite `action-target.ts` helpers (CitroLabs, revision `12407167f84a58af24044f072868051952131491`) | `apps/gui/app/src/main/assets/browser/actions.js` | MIT | License: `…/assets/browser/ego-lite-MIT.txt`. |
| claude-agent-sdk-python `sessions.py` transcript projection (revision `d38e51e`) | `…/cli_bridge/luma_cli/claude_history.py` | MIT, Copyright Anthropic | License: `…/assets/cli_bridge/CLAUDE-SDK-LICENSE.txt`. |
| Provider / CLI icons: Lobe Icons (`@lobehub/icons-static-svg` 1.102.0), pi artwork, CodeBuddy mark | `apps/gui/app/src/main/res/drawable/ic_provider_*` | MIT (the SVG files); brand marks remain trademarks of their owners | Inventory and revisions: `apps/gui/PROVIDER-ICONS.md`; licenses in `apps/gui/app/src/main/res/raw/provider_*_license.txt`. See "Trademarks". |
| Shizuku-compatible provider / binder-container stubs | `runtime/noroot/src/main/java/moe/shizuku`, `…/rikka/shizuku` | Apache-2.0 | Minimal clean-room implementations of the public Shizuku Binder IPC contract (`SPDX-License-Identifier: Apache-2.0`). |
| Gradle wrapper | `gradle/wrapper` | Apache-2.0 | |
| Android NDK notice text | `runtime/noroot/src/main/assets/licenses/Android-NDK-NOTICE.txt` | NDK licenses | |

## 2. Libraries resolved by Gradle (not committed)

OkHttp 5.5.1, Okio (transitive via OkHttp) and kotlinx-coroutines 1.11.0, AndroidX / Jetpack Compose (BOM 2026.09.00, runtime 1.12.1, activity-compose 1.13.0, Material 3), AndroidSVG 1.4: Apache-2.0.
CommonMark Java 0.32.0 (+ GFM tables/strikethrough extensions): BSD-2-Clause.
`ru.noties:jlatexmath-android` 0.2.0: GPL-2.0-or-later **with linking exception** (text: `apps/gui/app/src/main/assets/licenses/JLatexMath-Android-LICENSE.txt`); its bundled fonts keep their own licenses.
XZ for Java 1.12: 0BSD (`runtime/noroot/src/main/assets/XZ-Java-LICENSE.txt`).
Exact versions are in `gradle/libs.versions.toml`, `gradle/gui-dependencies.gradle`, `runtime/*/build.gradle` and `tools/toolchain.json`.

AndroidSVG is used only for static, local SVG rendering. Entity expansion and external resource resolution are disabled; scripts, animation, linked remote resources and environment-path access are not enabled. Source: https://github.com/BigBadaboom/androidsvg .

## 3. Binaries and assets produced at build time (shipped in APKs)

| Component | License | Obligation for releases |
|---|---|---|
| PRoot 5.1.107.92 (+ Luma adaptations), talloc 2.4.3, libandroid-shmem 0.7 (`libproot_exec.so`, `libproot_loader.so`) | PRoot: GPL-2.0-or-later (combined executable GPL-3.0-or-later); talloc: LGPL-3.0-or-later; libandroid-shmem: BSD | Provide corresponding source and build script (`runtime/noroot/third-party/`, `tools/build-native-proot.py`, and `talloc.tgz`); keep license texts (`runtime/noroot/src/main/assets/licenses/`). PRoot is executed as a separate program; it is not linked into Luma's own code. |
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
| Noto Sans Mono, Noto Sans Symbols, Noto Sans Math, Noto Music, Noto Sans Symbols 2, Noto Sans Arabic, Noto Sans Thai, Noto Sans Kannada, Noto Sans Canadian Aboriginal, Noto Sans Georgian (`notofonts/noto-fonts` @ `ffebf8c1ee449e544955a7e813c54f9b73848eac`) | SIL OFL 1.1 (`Noto-OFL.txt`) |
| Noto Color Emoji (`googlefonts/noto-emoji` @ `99701e07d3d1fe1bdbcdb14964547f0fd592966f`) | SIL OFL 1.1 (`NotoColorEmoji-OFL.txt`) |
| Luma CJK Forms (renamed subset of Noto Sans CJK SC 2.004) | SIL OFL 1.1 (`LumaCjkForms-OFL.txt`) |
| Luma GUI Han (`LumaGuiHan-Regular.ttf`, derived from Maple Mono, generated by `tools/build-gui-font.py`) | SIL OFL 1.1 (`LumaGuiHan-OFL.txt`) |

All Noto fonts are fetched by `tools/fetch-fonts.py` from the pinned upstream commits above and verified
against the SHA-256 values in `terminal-fonts-manifest.json`.
OFL fonts must not be sold on their own, and derived fonts must not use a Reserved Font Name
unless the license allows it; check the OFL file next to each font before changing a font.

### PRoot source distribution

Release APKs of Luma (NoRoot) bundle the unmodified upstream `talloc.tgz` (`talloc-2.4.3.tar.gz`) together with `proot-sources.tgz` (deterministically generated from `runtime/noroot/third-party/` and `tools/build-native-proot.py` via `python3 tools/build-native-proot.py --package-sources-only`) under `assets/native-sources/`, and retain component license texts in `assets/licenses/`.

## 4. Trademarks

Luma is an independent project. It is **not affiliated with, endorsed by or sponsored by** OpenAI, Anthropic, Google, Moonshot AI, Alibaba, Tencent, DeepSeek, xAI, Canonical or any other company.
"Codex", "OpenAI", "Claude", "Claude Code", "Anthropic", "Gemini", "Antigravity", "Kimi", "DeepSeek", "Grok", "OpenCode", "Qoder", "CodeBuddy", "Ubuntu" and other product names, logos and icons are trademarks or registered trademarks of their respective owners. They are used here only to name the third-party command-line tools that Luma can drive and, in the case of icons, to identify them in the UI (nominative use). Icon sources and revisions are listed in `apps/gui/PROVIDER-ICONS.md`. If an owner objects to an icon, remove the matching `ic_provider_*` drawable and its mapping in `ProviderIcon.kt`. Luma does not bundle any of these CLIs.
