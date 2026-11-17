# Luma

Use Codex, Claude Code, OpenCode and other coding agents on an Android phone.

[![License: GPL-3.0-only](https://img.shields.io/badge/license-GPL--3.0--only-blue.svg)](LICENSE)
![Android 12+](https://img.shields.io/badge/Android-12%2B%20(API%2031)-3ddc84)
![ABI](https://img.shields.io/badge/ABI-arm64--v8a-lightgrey)

Luma runs unmodified coding-agent CLIs in a Debian environment on the phone and presents their output as conversations, approval dialogs and notifications. It does not include agents or accounts: install each CLI and sign in with your own account; the CLI keeps using its normal configuration.

## Features

Luma supports Codex, OpenCode, Claude Code, pi, DeepSeek Harness, Kimi Code, Antigravity, Qoder CLI, Grok Build and CodeBuddy Code in one session UI.

- Tables, code, math, images and video are rendered in conversations.
- Uncertain submissions remain visibly pending instead of being silently resent.
- Agents can drive the built-in browser, create sessions and inspect other sessions.
- Usage statistics and a file browser are included.
- The default ARM64 Debian environment includes Python, Git, Node.js and pnpm and runs under PRoot without Root.
- Rooted devices can switch to Root Chroot in Environment settings. Terminals survive app process death and can be reconnected without Shizuku or a foreground service. Returning to PRoot requires clearing app data.
- Ghostty's libghostty-vt provides terminal parsing, Kitty graphics and iTerm2 inline images. Bundled fonts cover CJK, Nerd Font icons, math symbols and emoji.

## Install

Download the APK from [Releases](../../releases/latest). Luma requires an ARM64 device running Android 12 or later.

## Build and test

Prerequisites: JDK 21, Android SDK platform 37.2, build-tools 37.0.0 and Python 3.11+. Rebuilding native libraries also requires NDK r27c and Zig 0.16.0.

A fresh clone can compile and run JVM tests. APK builds additionally need large inputs that are not stored in Git. `tools/bootstrap.py` restores fonts, pnpm and native sources. Download `debian-arm64.zip.xz` and `rootfs-manifest.json` from the [`rootfs-v1`](../../releases/tag/rootfs-v1) release and place them under `runtime/noroot/src/main/assets/` before bootstrapping and building the APK.

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
export JAVA_HOME=/path/to/jdk-21

./tools/luma verify --static
./gradlew :apps:gui:compileDebugKotlin :apps:gui:testDebugUnitTest
./tools/luma lint gui

python3 tools/bootstrap.py
LUMA_ONLINE=1 ./luma build gui
```

To rebuild only the PRoot executables with NDK r27c, run `python3 tools/build-native-proot.py`; `--ndk`, `--out-dir` and `--build-dir` override its defaults. Use `--package-sources-only` to regenerate the corresponding source archive without compiling.

The static gate checks tracked and untracked, non-ignored sources without requiring the Android SDK or downloaded artifacts. For UI interaction changes, also run `./tools/luma test-ui 'TEST_PATTERN'` and compare the relevant screens.

Before a release, run the full verification and delivery checks:

```sh
./tools/luma verify
./tools/luma build gui
python3 tools/delivery.py --target gui
```

Release APKs are signed only when the ignored `apps/gui/signing/luma-release.properties` file exists; otherwise the release build is unsigned. Do not commit signing material, credentials, APKs, build outputs or large binaries. External inputs belong in `artifacts/` and must be pinned in `tools/artifacts.lock.json`.

## Project map

```text
Compose UI ──► SessionController ──┬─► Codex / OpenCode clients ─────► CLI server
                                   └─► NativeCliSession ─► cli_bridge ─► other CLIs
Terminal view ─► libghostty-vt (JNI)       PTY ◄─ Debian under PRoot or Root Chroot
```

| Path | Responsibility |
| --- | --- |
| `apps/gui` | Android app, Compose UI, sessions, CLI bridge, browser and JVM tests |
| `runtime/noroot` | Debian/PRoot installer and runtime, managed services and Shizuku integration |
| `runtime/root` | Root Chroot terminal runtime |
| `backend/root` | Root terminal keeper daemon |
| `shared` | Terminal emulator, terminal view, Ghostty JNI and shared UI |
| `gradle` | Version catalog and shared build conventions |
| `tools` | Bootstrap, native builds, verification, tests and release validation |

The main session entry point is `apps/gui/app/src/main/java/io/github/modao7067/luma/gui/session/SessionController.kt`. CLI adapters live under `apps/gui/app/src/main/assets/cli_bridge/luma_cli/`; their focused tests are in `tools/tests/cli/`. Compose interaction tests are in `tools/tests/ui/compose/`.

## Contribute

Issues and focused pull requests are welcome in English or Chinese. Discuss large changes in an issue first, avoid unrelated reformatting and use [Conventional Commits](https://www.conventionalcommits.org/). Run the checks above before submitting.

New third-party code, fonts and icons must have a GPL-3.0-compatible license and be recorded in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md). Preserve upstream copyright and license notices. Contributions are licensed under GPL-3.0-only; no CLA or sign-off is required.

## Security

Do not open a public issue for a vulnerability. Email **modao7067@gmail.com** with the affected component and version, device and Android version, Root-mode status, reproduction steps and expected impact. Security reports should receive an acknowledgement within seven days; allow reasonable time for a fix before public disclosure.

Only the latest release and the latest `main` commit receive security fixes. Vulnerabilities in third-party CLIs, Debian packages, Android or the Root solution should be reported to their respective maintainers.

## License

GNU GPL v3.0 only — see [`LICENSE`](LICENSE), [`NOTICE`](NOTICE) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md). Luma is independent and is not affiliated with or endorsed by any CLI vendor; product names and icons belong to their respective owners.

## Thanks

Built on [Termux](https://github.com/termux/termux-app) (via [Termix](https://github.com/jeffusion/Termix)), [Ghostty](https://github.com/ghostty-org/ghostty), [PRoot](https://proot-me.github.io/), [Debian](https://www.debian.org/), [Maple Mono](https://github.com/subframe7536/maple-font) and [compose-miuix-ui](https://github.com/compose-miuix-ui/miuix), along with the Android and Kotlin communities. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for component versions, provenance and maintenance notes.
