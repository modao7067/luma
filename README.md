# Luma

Use Codex, Claude Code, OpenCode and other coding agents on an Android phone.

English | [简体中文](README.zh-CN.md)

[![License: GPL-3.0-only](https://img.shields.io/badge/license-GPL--3.0--only-blue.svg)](LICENSE)
![Android 12+](https://img.shields.io/badge/Android-12%2B%20(API%2031)-3ddc84)
![ABI](https://img.shields.io/badge/ABI-arm64--v8a-lightgrey)

## Why

These agents were written for a desktop terminal. Run one directly on a phone and the keyboard covers half the screen, the output scrolls faster than you can read it, and when the agent stops to ask `y/N` you probably won't notice.

Luma doesn't change the agents. They're still ordinary command-line programs, running in a Debian system on the phone. Luma is the interface in front of them: it turns the output into a conversation, shows approval requests as dialogs, and sends a notification when an agent is waiting for you.

Luma doesn't include any agent or account. You install the CLIs and sign in yourself, and they keep using their own config.

## What it does

It supports 10 CLIs today: Codex, OpenCode, Claude Code, pi, DeepSeek Harness, Kimi Code, Antigravity, Qoder CLI, Grok Build and CodeBuddy Code, all in the same session UI.

- Tables, code, math, images and video render properly in the conversation.
- If Luma can't confirm a message was delivered, it keeps showing "sending" instead of quietly sending it again.
- There's a built-in browser that agents can drive too. Agents can also open new Luma sessions and look at other sessions.
- Usage stats and a file browser.

**Linux environment**

- Ships with Debian (ARM64) with Python, Git, Node.js and pnpm installed. It runs under PRoot by default, so no root is needed.
- On a rooted phone you can switch to Root Chroot in Environment settings. Terminals then no longer depend on a foreground service or Shizuku, and if Android kills the app, the terminals keep running and you can reconnect. The switch is one-way: going back to PRoot means clearing the app's data.

**Terminal**

- Terminal parsing is done by Ghostty's libghostty-vt, with support for Kitty graphics and iTerm2 inline images.
- Fonts are bundled with the app, so CJK text, Nerd Font icons, math symbols and emoji all display regardless of the phone's system fonts.

## Install

Download the APK from [Releases](../../releases/latest) and install it. You need an ARM64 phone on Android 12 or later.

## Building it yourself

You need JDK 21, the Android SDK (platform 37.2, build-tools 37.0.0) and Python 3.11+. Rebuilding the native libraries also needs NDK r27c and Zig 0.16.0.

A fresh clone compiles and runs the tests. Building the APK also needs a few large files that aren't in Git (fonts, pnpm, PRoot sources, and the Debian rootfs); `python3 tools/bootstrap.py` downloads or rebuilds everything except the Debian image, which you can grab (`debian-arm64.zip.xz` and `rootfs-manifest.json`) from the [`rootfs-v1`](../../releases/tag/rootfs-v1) release and place under `runtime/noroot/src/main/assets/`. Release builds are signed only when `apps/gui/signing/luma-release.properties` exists (git-ignored).

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # or set ANDROID_HOME
export JAVA_HOME=/path/to/jdk-21

# Works on a fresh clone
./gradlew :apps:gui:testDebugUnitTest :apps:gui:lintDebug

# To build the APK, place the Debian rootfs files above first, then fetch the rest and build
python3 tools/bootstrap.py
LUMA_ONLINE=1 ./luma build    # produces an unsigned release APK if no signing config is set up
```

## Code layout

```
Compose UI ──► SessionController ──┬─► Codex / OpenCode clients ─────► the CLI's own server
                                   └─► NativeCliSession ─► cli_bridge (Python) ─► other CLIs
Terminal view ─► libghostty-vt (JNI)        PTY ◄─ Debian under PRoot, or Debian in Root Chroot + keeper daemon
```

```
apps/gui         The app: UI, sessions, cli_bridge, browser
runtime/noroot   Debian/PRoot environment, installer, Shizuku bridge
runtime/root     Terminal runtime for Root Chroot mode
backend/root     Keeper daemon that keeps terminals alive in Root Chroot mode
shared/          Terminal emulator, terminal view, libghostty JNI, shared UI
tools/           Build scripts, asset checks, tests
```

## Contributing

Issues and PRs are welcome; please read [`CONTRIBUTING.md`](CONTRIBUTING.md) first. Report security problems privately as described in [`SECURITY.md`](SECURITY.md), not in a public issue.

## License

GNU GPL v3.0 only — see [`LICENSE`](LICENSE), [`NOTICE`](NOTICE), and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

Luma is an independent project, not affiliated with or endorsed by any CLI vendor; all product names and icons belong to their respective owners.

## Thanks

Built on the shoulders of [Termux](https://github.com/termux/termux-app) (via [Termix](https://github.com/jeffusion/Termix)), [Ghostty](https://github.com/ghostty-org/ghostty), [PRoot](https://proot-me.github.io/), [Debian](https://www.debian.org/), [Maple Mono](https://github.com/subframe7536/maple-font), and [compose-miuix-ui](https://github.com/compose-miuix-ui/miuix), along with the Android and Kotlin communities. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for the full list of third-party components.
