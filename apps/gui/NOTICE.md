# GUI third-party notices

Luma's first-party application code is licensed under GPL-3.0-only. The complete component inventory and build-time obligations are in [`../../THIRD_PARTY_NOTICES.md`](../../THIRD_PARTY_NOTICES.md); full license texts remain beside their sources or in the packaged asset directories.

## Provider and menu icons

GUI VectorDrawables use Lobe Icons, official pi artwork and the CodeBuddy mark under MIT, plus Google Material Design Icons under Apache-2.0. Exact revisions, resource mappings and bundled licenses are listed in [`PROVIDER-ICONS.md`](PROVIDER-ICONS.md). Brand trademarks remain with their respective owners.

## Liquid glass, press and menu motion

`PrismGlass.kt`, `LiquidChrome.kt`, `ElasticPress.kt`, `MorphMenu.kt`, `PrismMotion.kt` and the opening/dismissal motion in `OriginMenu.kt` adapt the liquid glass shader/optics, downscaled backdrop, dock/menu shadows, elastic press, popup morph, droplet return, carried popup press and glyph pulse from chen08209/prism_ui, revision `6c47901cc80b5325bdb9650579ca2e000a60b39e`.

Source: https://github.com/chen08209/prism_ui/tree/6c47901cc80b5325bdb9650579ca2e000a60b39e

prism_ui is licensed under GPL-3.0; its license text is retained in `third-party/prism-ui/LICENSE-GPL-3.0.txt`.

## Native CLI PTY dependencies

`cli_bridge/native/tmux-debian-arm64.zip` contains Debian ARM64 tmux 3.5a-3, libevent-core 2.1.12-stable-10+b1 and jemalloc 5.3.0-3. Debian copyright and license files remain in the archive. Package pins and the reproducible builder are in `tools/native/tmux/packages.json` and `tools/build-native-tmux.py`.

## Browser automation helpers

`app/src/main/assets/browser/actions.js` adapts composed-tree traversal helpers from CitroLabs ego-lite, `package/ego-browser/src/driver/action-target.ts`, revision `12407167f84a58af24044f072868051952131491`.

Source: https://github.com/citrolabs/ego-lite/tree/12407167f84a58af24044f072868051952131491

Copyright (c) 2026 CitroLabs. The MIT license is retained in `app/src/main/assets/browser/ego-lite-MIT.txt`. Luma uses Android WebView and does not bundle the ego browser application.

## Claude Agent SDK transcript projection

`cli_bridge/luma_cli/claude_history.py` adapts transcript parsing, project-path encoding and conversation-chain recovery from Anthropic's `claude-agent-sdk-python`, revision `d38e51e`, `src/claude_agent_sdk/_internal/sessions.py`.

Source: https://github.com/anthropics/claude-agent-sdk-python/tree/d38e51e

Copyright Anthropic. The MIT license is retained in `app/src/main/assets/cli_bridge/CLAUDE-SDK-LICENSE.txt`. Claude Code and Kimi Code executables are not bundled; Luma uses the user's installed CLIs and configuration.

## Global usage and quota probes

`cli_bridge/luma_cli/global_usage.py` adapts usage aggregation, rate-limit window semantics, and provider quota probing (including OpenCode Go `packages/provider-opencode/src/server/usageLimits.ts`, Codex, Claude, Antigravity, and Grok) from T3 Code (`pingdotgg/t3code`), revision `6577c6b6185167c0e502fa01f9e8e8f2dee6ff25`, along with usage-scanning references from `junhoyeo/tokscale` (CLI/core, MIT) and `MoonshotAI/kimi-cli` (Apache-2.0).

Source: https://github.com/pingdotgg/t3code/tree/6577c6b6185167c0e502fa01f9e8e8f2dee6ff25

Copyright (c) 2026 T3 Tools Inc. The MIT license is retained in `app/src/main/assets/cli_bridge/T3CODE-LICENSE.txt`.

## Termux and terminal provenance

Terminal emulator and view sources originate from Termux through jeffusion/Termix commit `4bcbac08fcd15c91c65d50df90159b45544433c3`. Original copyrights and license exceptions are retained in `COPYING`, `GPL-3.0.txt`, `UPSTREAM-LICENSE` and the shared source files. Parts derived from jackpal's Android-Terminal-Emulator retain their Apache-2.0 terms.

`libtermux.so` is built from `shared/terminal-emulator/upstream-native-source/termux.c` using `shared/terminal-emulator/build-native.sh`.

Sixel and iTerm2 image-protocol support derives from Termux PR #2973, commit `d8d6b02aceb70e80588cadd7b79507676f1a95fc`, with bounded image caches, decoder limits and row-renderer integration.

Source: https://github.com/termux/termux-app/pull/2973

## Debian and NoRoot runtime

The Debian root filesystem retains each package's copyright and license under `/usr/share/doc`; package versions are listed in `runtime/noroot/src/main/assets/debian-packages.tsv`.

PRoot 5.1.107.92, talloc 2.4.3 and libandroid-shmem 0.7 retain their GPL-2.0-or-later, LGPL-3.0-or-later and BSD licenses. The standalone combined PRoot executable is distributed under GPL-3.0-or-later. Corresponding sources, Luma adaptations and the portable build recipe are tracked under `runtime/noroot/third-party/` and `tools/build-native-proot.py`, with source archives and licenses packaged under `assets/native-sources/` and `assets/licenses/`.

Node.js 24.22.1, npm 12.2.1 and pnpm 12.15.0 retain their MIT or Artistic-2.0 licenses and bundled dependency notices in the guest filesystem and assets. The Android NDK notice is packaged under `assets/licenses/`.

XZ for Java 1.12 (Tukaani Project) is used under 0BSD; its complete notice is in `runtime/noroot/src/main/assets/XZ-Java-LICENSE.txt`.

## Fonts

The terminal and GUI font chain uses Maple Mono NL NF CN 7.9, JetBrains Mono NL Nerd Font Mono from Nerd Fonts 3.5.1, Noto Sans Mono, Noto Sans Symbols, Noto Sans Math, Noto Music, Noto Sans Symbols 2, Noto Sans Arabic, Noto Sans Thai, Noto Sans Kannada, Noto Sans Canadian Aboriginal, Noto Sans Georgian, Noto Color Emoji and derived Luma subsets under SIL OFL 1.1.

- Maple Mono: Copyright 2022 The Maple Mono Project Authors; CN glyphs are based on Resource Han Rounded. Source: https://github.com/subframe7536/maple-font
- JetBrains Mono and Nerd Fonts: https://github.com/ryanoasis/nerd-fonts
- Noto fonts: Copyright The Noto Project Authors. Sources: https://github.com/notofonts/noto-fonts and https://github.com/googlefonts/noto-emoji
- Luma CJK Forms is a renamed Unicode-block subset of Noto Sans CJK SC 2.004. Source: https://github.com/notofonts/noto-cjk

Full OFL texts, pinned revisions, hashes and subset ranges are retained in the runtime asset directories and `terminal-fonts-manifest.json`.

## Shared native terminal engine

libghostty-vt is pinned to `0e6afb2600d4` under MIT, Copyright (c) 2024 Mitchell Hashimoto and Ghostty contributors. The full license is in `shared/terminal-ghostty/assets/Ghostty-LICENSE.txt`. Upstream security fix `e257bb4` (GHSA-w9q4-5jvv-mcv4) is backported as a local patch. Luma's JNI, Android rendering and lifecycle glue are GPL-3.0-only.

## GUI libraries and adapted sources

AndroidX, Jetpack Compose, OkHttp, Okio, Kotlin Coroutines, AndroidSVG and Material Color Utilities are Apache-2.0. CommonMark Java is BSD-2-Clause. JLaTeXMath Android is GPL-2.0-or-later with its linking exception; its fonts retain the licenses bundled in the upstream AAR. Exact versions and license locations are in `THIRD_PARTY_NOTICES.md` and the Gradle version catalog.

`ui/shapes/MiuixSquirclePath.kt`, `ui/thirdparty/miuix/Overscroll.kt` and `SpringUtils.kt` adapt compose-miuix-ui 0.9.4, revision `39c40f99844227b853f0049a0933b1f3ae6c00ba`, under Apache-2.0. Their original copyright notices and packaged licenses are retained.

`ui/OriginMenu.kt` adapts AndroidX Material3 1.4.0's DropdownMenu shell, MenuContent and standard motion tokens under Apache-2.0, Copyright 2021 The Android Open Source Project. The source artifact and maintenance constraints are recorded in `THIRD_PARTY_NOTICES.md`; the full license is in `third-party/androidx-material-menu/LICENSE-Apache-2.0.txt`.

`ui/CodeHighlighting.kt` adapts syntax highlighting color palettes from Vitesse Light (`antfu/vscode-theme-vitesse`, MIT, Copyright (c) Anthony Fu, contrast-calibrated for Luma's light code surface) and Tokyo Night (`enkia/tokyo-night-vscode-theme`, MIT, Copyright (c) 2018 Enkia).
