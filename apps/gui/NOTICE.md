# CLI provider and plugin icons

Shared GUI VectorDrawables use Lobe Icons (MIT), official pi artwork
(MIT), CodeBuddy mark (MIT), and Google Material Design Icons (Apache-2.0). Exact revisions, mappings,
modifications, source links, and bundled license locations: `PROVIDER-ICONS.md`.
Brand trademarks remain with their respective owners.

# Native CLI PTY dependencies

`cli_bridge/native/tmux-debian-arm64.zip` contains Debian ARM64 tmux 3.5a-3,
libevent-core 2.1.12-stable-10+b1 and jemalloc 5.3.0-3, for private extraction
when the guest has no tmux. Debian copyright/license files and package
provenance are retained in that archive. Pinned package sources and builder:
`../../tools/native/tmux/packages.json`, `../../tools/build-native-tmux.py`.
These components retain their upstream licenses and are not installed globally.

# ego-lite browser automation helpers

The shared GUI browser's `app/src/main/assets/browser/actions.js` adapts
composed-tree traversal helpers from CitroLabs ego-lite,
`package/ego-browser/src/driver/action-target.ts`, revision
`12407167f84a58af24044f072868051952131491`.
Source: https://github.com/citrolabs/ego-lite/tree/12407167f84a58af24044f072868051952131491 .
Copyright (c) 2026 CitroLabs. Distributed under the MIT License, retained in
`app/src/main/assets/browser/ego-lite-MIT.txt`.
Luma continues to use Android WebView; the ego browser application and native
snapshot implementation are not bundled.

# Claude Agent SDK transcript projection

The GUI native CLI bridge includes transcript parsing, project-path encoding,
and conversation-chain/parallel-tool recovery code adapted from Anthropic's
`claude-agent-sdk-python`, revision `d38e51e` (2026-11-02),
`src/claude_agent_sdk/_internal/sessions.py`.
Source: https://github.com/anthropics/claude-agent-sdk-python/tree/d38e51e .
Copyright Anthropic. Distributed under the MIT License, retained in
`app/src/main/assets/cli_bridge/CLAUDE-SDK-LICENSE.txt`.
The adapted implementation is `cli_bridge/luma_cli/claude_history.py`.
The Claude Code and Kimi Code executables are not bundled by these adapters;
the runtime uses the user's installed native CLI and configuration.

---

# Luma (derived from Ubuntu Glass 1.7)

Luma 0.3.3 uses JetBrainsMonoNLNerdFontMono-Regular.ttf as the terminal primary
font, from Nerd Fonts v3.5.1 (JetBrains Mono, Nerd Font patched Mono variant).
It is distributed under SIL OFL 1.1; see assets/JetBrainsMono-NerdFont-OFL.txt.
Source: https://github.com/ryanoasis/nerd-fonts/releases/tag/v3.5.1 .
The release archive was verified against its official SHA-256 digest.

Version 0.2 bundles pnpm 12.15.0 and its official Linux ARM64 glibc executable
(MIT and bundled third-party notices), retained with the module in assets/pnpm.
The two npm distribution archives were checked against their registry SHA-512
integrity metadata. The Android NDK notice is in assets/licenses. The native
compute checker, process launcher, same-UID socket bridge, runtime wrappers
and performance adaptations are Luma application code under GPL-3.0-only.
The compute checker source and shader are in ../../runtime/noroot/src/main/cpp; their small
build script is scripts/build-compute-check.sh.


Luma is a separate rootless Android terminal, application ID
`io.github.modao7067.luma`, under GPL-3.0-only for its application adaptations.
It adds a privately owned Debian/PRoot environment, first-run installation,
storage permission handling, background lifecycle controls, and workspace
environment guidance. Ubuntu Glass provenance and notices follow below.

PRoot 5.1.107.92, talloc 2.4.3 and libandroid-shmem 0.7 sources, build recipes,
patches and licenses are bundled in assets/native-sources and assets/licenses.
The standalone combined PRoot executable is distributed under GPL-3.0-or-later;
individual component licenses remain GPL-2.0-or-later, LGPL-3.0-or-later and BSD.

Debian package versions are listed in assets/debian-packages.tsv. Individual
copyright and license notices remain in the guest /usr/share/doc directories.
Debian package/source repositories: https://deb.debian.org/debian/ and
https://deb.debian.org/debian-security/ . Node.js 24.22.1 / npm 11.19.1 come
from https://nodejs.org/dist/v24.22.1/node-v24.22.1-linux-arm64.tar.xz ;
SHA-256 dbabf7a1cbf947e6059ffad2c27b80041db97dea1e553b62afdee7f13fca336e.
Luma 0.3.2 replaces npm with the official npm 12.2.1 distribution from
https://registry.npmjs.org/npm/-/npm-12.2.1.tgz, checked against registry
SHA-512 integrity metadata. Its Artistic-2.0 license and dependency notices are
retained in the guest package. Only fresh environment installations receive
this bundled npm version; existing guest package installations are not upgraded.
Node's LICENSE is retained in the guest /usr/local/LICENSE, along with npm
package notices. The rootfs is built from minbase plus the requested CLI tools,
not copied from the developer's live Linux environment; it contains no user
configuration or credentials.

Luma 0.3.1 defaults to a pure black terminal background (user-selectable custom
background image at runtime) and bundles the verified open-source terminal font chain.

---

# Ubuntu Glass provenance

An Android terminal frontend for an ARM64 Ubuntu chroot.

Application sources are supplied under GPL-3.0-only. Terminal emulator and view
sources originate from Termux, through jeffusion/Termix commit
`4bcbac08fcd15c91c65d50df90159b45544433c3`. Retain their original copyrights
and license exceptions. See COPYING, GPL-3.0.txt, and UPSTREAM-LICENSE.

The ARM64 `libtermux.so` is built directly from the in-tree
`../../shared/terminal-emulator/upstream-native-source/termux.c` source via
`../../shared/terminal-emulator/build-native.sh`.

Local adaptations: public ParcelFileDescriptor APIs replace reflective
FileDescriptor access; default terminal backgrounds are left transparent by
the upstream renderer; an optional user-chosen background and tinted layer can be
composited behind the terminal. The Termux-derived extra-key widget from the
same Termix source is included, with transparent button surfaces and an
independent Shift+Tab key. Version 1.2 replaces font-based block elements with
cell geometry and fits fallback glyphs while preserving their aspect ratio;
common circular status glyphs are drawn as circles. Version 1.3 increases Han-only
glyph occupancy within its existing cells, uniformly and within cell bounds,
to reduce visual whitespace without changing symbols or emoji.

The terminal fallback fonts are Noto Sans Mono, Noto Sans Symbols, Noto Sans Math,
Noto Music, Noto Sans Symbols 2 (Copyright 2018 The Noto Project Authors) and Noto
Color Emoji, all under SIL Open Font License 1.1 and fetched from pinned commits of
https://github.com/notofonts/noto-fonts and https://github.com/googlefonts/noto-emoji
(licenses: Noto-OFL.txt, NotoColorEmoji-OFL.txt in the app assets).
See ../../runtime/noroot/src/main/assets/terminal-fonts-manifest.json for provenance and hashes.
Build requirements are documented in ../../README.md.
Terminal rendering now uses the bundled font chain and rejects system glyph
fallbacks; unsupported clusters use a bundled replacement glyph.

Version 1.7 fixes source identification for variable fonts whose default axes
are normalized by Android text shaping. Terminal font provenance is checked
against the asset bytes and TTC face, and drawing uses the asset Font.
Keyboard and window toolbar icons use local Canvas geometry with shared sizing
and stroke width, replacing font characters.

## Luma Connector Symbols

`LumaConnectorSymbols-Regular.ttf` is a renamed, two-character subset (U+23BE/U+23BF) of Sarasa Term SC Nerd Regular. Original copyright: Renzhi Li and the Inter, Adobe and Google contributors. Distributed under SIL Open Font License 1.1; see `../../runtime/noroot/src/main/assets/LumaConnectorSymbols-OFL.txt`. The primary Latin, CJK and Nerd Font assets remain separate.

## Shared terminal image implementation

Both Luma variants now compile the common terminal sources under `shared/`.
Sixel / iTerm2 image protocol support derives from Termux PR #2973, fixed
commit `d8d6b02aceb70e80588cadd7b79507676f1a95fc`, with local bounded image
caches, decoder limits and row-renderer integration. Original source notices
and the Termux license exception remain in the shared files.
Source: https://github.com/termux/termux-app/pull/2973 .

## CJK forms fallback

Luma CJK Forms Regular is a renamed, reproducible Unicode-block subset of
Noto Sans CJK SC 2.004 (Adobe 2014-2021 / Google 2010-2012), licensed under
SIL OFL 1.1. It covers CJK punctuation, kana, halfwidth/fullwidth forms and
related forms. Existing font precedence is preserved; the subset is a fallback.
Source: https://github.com/notofonts/noto-cjk .
See assets/LumaCjkForms-OFL.txt and the source/font hashes and ranges in
assets/terminal-fonts-manifest.json. Build recipe: tools/build-terminal-fonts.py
at the repository root.

## Maple Mono terminal primary

Current builds use the unmodified Maple Mono NL NF CN Regular 7.9 static TTF
as the primary terminal font, including supported Latin, Chinese/Japanese and
Nerd Font symbols. The NL release retains the previous no-ligature behavior.
Copyright 2022 The Maple Mono Project Authors, under SIL OFL 1.1; the complete
license is bundled as assets/MapleMono-OFL.txt. The CN glyphs are based on
Resource Han Rounded; the NF glyphs are embedded by the upstream release.
Source: https://github.com/subframe7536/maple-font/releases/tag/v7.9 .
Archive and font hashes are in assets/terminal-fonts-manifest.json.

The former fonts remain only as fallbacks for unsupported characters, including
rare Han, additional symbols and color emoji; the historical OEM asset retains
the personal-use redistribution limitation above. Luma Connector Symbols is
no longer packaged because the new primary covers both connector characters.

## Shell tools

bat, Glow, fzf, gawk, ripgrep, fd, Python and other Debian packages retain their package copyright and license notices in the bundled Debian filesystem under /usr/share/doc. Luma Morandi theme files are project configuration. No authenticated CLI installation, credentials, or shell history is bundled.

XZ for Java 1.12 (Tukaani Project) decodes the offline environment archive only during fresh installation. License: 0BSD; complete upstream notice in assets/XZ-Java-LICENSE.txt. Source: https://github.com/tukaani-project/xz-java/tree/v1.12 .

Luma 0.3.16 ships the audited R2 L2 ARM64 PRoot executor and its paired loader.
The libandroid-shmem adaptation lazily resolves the real platform APIs via
pthread_once/dlopen and retains the original ashmem fallback. No ioctl/prctl
allowlist or observer is enabled. Patch, upstream sources, recipe and exact
host-clang-21/NDK-r27c build provenance are in assets/native-sources. Original
component licenses remain unchanged.

Luma 0.3.17+ includes the audited R5 fchmodat2 path-translation, empty-path
guest-cwd, and R6 native hard-link fixes on the L2 executor. The patches,
host-clang-21 / NDK-r27c recipes, compiler wrappers and provenance manifests are
bundled in `assets/native-sources/proot-recipes.tgz` and tracked at
`tools/native/proot-r5/` and `tools/native/proot-r6/` in the repository. The
upstream source archives and component licenses are unchanged. USERLAND remains
disabled.

## Shared native terminal engine

libghostty-vt is pinned to 0e6afb2600d4 under the MIT license. The full license
is packaged as assets/Ghostty-LICENSE.txt from shared/terminal-ghostty/assets.
JNI, Android Bitmap/Canvas integration and the private checkpoint/animation
bridge are Luma adaptations. Both apps compile the same shared Java/C/Zig
implementation. Android supplies the PNG decoder; native dependencies are statically linked.

## Native-only terminal (2026-11-01)

Current production builds use libghostty-vt exclusively. The former Java VT and
image protocol implementations are retired; their historical provenance above
remains for attribution and isolated test references. Android host, rendering and
read-only frame structures retain their Termux-derived licensing. The pinned
libghostty source receives a local functional backport of upstream e257bb4 for
GHSA-w9q4-5jvv-mcv4; the patch is in shared/terminal-ghostty/patches.

## Compose 合并入口（0.4.0）

新增 Kotlin 2.1.21、AndroidX Activity Compose 1.10.1、Compose 1.8.1、Material 3 1.3.2 及传递依赖，遵循各自 Apache-2.0 许可。GUI APK 同时包含 AndroidX Graphics Path 1.0.1 的 ARM64 原生库；发布检查将其与本地 Maven AAR 中的同名成员逐字节核对。Root 独立包不引入 Compose 依赖。

## Compose runtime 复用修复（2026-11-03）

仅 `androidx.compose.runtime:runtime`、`runtime-saveable` 及其 Android／annotation
组件升级为官方 Google Maven **1.10.6**，Apache-2.0；UI／Foundation 仍为 1.8.1，
Material 3 仍为 1.3.2。使用官方二进制，不修改或内嵌 AndroidX 源码。
该版本包含 [I474b5 根复用槽位修复](https://android.googlesource.com/platform/frameworks/support/+/420bc818bded76fc5be283bdf149117cbbb78404)：
避免根 group key 100 与嵌套 reusable group 的槽位索引碰撞。
来源元数据：[runtime Android POM](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-android/1.10.6/runtime-android-1.10.6.pom)、
[saveable Android POM](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-saveable-android/1.10.6/runtime-saveable-android-1.10.6.pom)。

## 原生会话 GUI（0.5.0）

OkHttp 4.12.0、Okio 和 Kotlin Coroutines 遵循 Apache-2.0。CommonMark Java 0.24.0 及表格／删除线扩展遵循 BSD-2-Clause。JLaTeXMath Android 0.2.0 的 Android 源码采用 GPL-2.0-or-later 并附链接例外，完整声明见 `app/src/main/assets/licenses/JLatexMath-Android-LICENSE.txt`；其打包字体保留 AAR 中所有字体许可。该库仅在 GUI 用于原生公式绘制；Root 独立包不引入上述聊天依赖。

## SPEC_2025 动态配色（0.5.3）

GUI includes a minimal Java source subset of Google's Material Color Utilities,
commit `158b9c185a25c53fddd051cba811d542e6b94d68`, under Apache-2.0.
Original copyright headers are retained; the complete license is packaged in
`app/src/main/assets/licenses/Material-Color-Utilities-LICENSE.txt`.
The source inventory and mechanical namespace/annotation adaptations are recorded
in `third-party/material-color-utilities/README.md`. Color algorithms are unchanged.
No wallpaper image or wallpaper-reading permission is included.

## Menu icons (0.6.1)

Google Material Design Icons, commit `10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7`,
Apache-2.0. Selected outlined 24 px SVG paths are mechanically converted to
Android VectorDrawable resources (`app/src/main/res/drawable/ic_menu_*`).
Source: https://github.com/google/material-design-icons . The full license is
packaged in `app/src/main/res/raw/material_icons_license.txt`; the icon inventory
is in `third-party/material-icons/README.md`. No icon font or new runtime dependency.

# SVG rendering and continuous card corners

The shared GUI uses AndroidSVG 1.4 (Paul LeBeau / Cave Rock Software Ltd.,
Apache-2.0) for static local SVG rendering. Source and integration details:
`third-party/androidsvg/`; the license is bundled as
`app/src/main/assets/licenses/androidsvg-1.4-Apache-2.0.txt`.

`ui/shapes/MiuixSquirclePath.kt` adapts the package name of the path builder
from compose-miuix-ui/miuix v0.9.4, revision
`39c40f99844227b853f0049a0933b1f3ae6c00ba` (Copyright 2026 compose-miuix-ui
contributors, Apache-2.0). This extraction contains only the small geometry
helper; no Miuix UI library or theme is bundled. See `third-party/miuix-squircle/` and
`app/src/main/assets/licenses/miuix-squircle-0.9.4-Apache-2.0.txt`.

`ui/thirdparty/miuix/Overscroll.kt` and `SpringUtils.kt` adapt the translation
overscroll modifier and its spring helpers from compose-miuix-ui/miuix
v0.9.4, revision `39c40f99844227b853f0049a0933b1f3ae6c00ba`
(Copyright 2025 compose-miuix-ui contributors, Apache-2.0). Changes are
limited to the local package and removal of unused pull-to-refresh integration.
See `third-party/miuix-overscroll/` and bundled
`licenses/miuix-overscroll-0.9.4-Apache-2.0.txt`.

# AndroidX Material3 menu seam

The floating-menu wrapper adapts AndroidX Material3 1.4.0's DropdownMenu shell,
MenuContent and standard motion tokens under Apache-2.0. Source identity,
changes and license: `third-party/androidx-material-menu/`.
