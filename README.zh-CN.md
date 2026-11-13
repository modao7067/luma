# Luma

在 Android 手机上用 Codex、Claude Code、OpenCode 这类编程 Agent。

[English](README.md) | 简体中文

[![License: GPL-3.0-only](https://img.shields.io/badge/license-GPL--3.0--only-blue.svg)](LICENSE)
![Android 12+](https://img.shields.io/badge/Android-12%2B%20(API%2031)-3ddc84)
![ABI](https://img.shields.io/badge/ABI-arm64--v8a-lightgrey)

## 为什么做这个

这些 Agent 都是给电脑终端写的。直接在手机上跑，键盘占掉半个屏幕，输出刷得根本看不过来，Agent 停下来问你 `y/N` 的时候，你多半没注意到。

Luma 不改 Agent 本身。它们还是普通的命令行程序，跑在手机里的一个 Debian 系统上。Luma 做的是外面那层界面：把输出整理成对话，把确认请求做成弹窗，Agent 等你回复时发个通知。

Luma 不自带任何 Agent，也不带账号。你自己安装、自己登录，用的还是它们原来的配置。

## 能做什么

目前支持 10 个 CLI：Codex、OpenCode、Claude Code、pi、DeepSeek Harness、Kimi Code、Antigravity、Qoder CLI、Grok Build、CodeBuddy Code，都在同一个会话界面里用。

- 对话里的表格、代码、公式、图片和视频都能正常显示。
- 消息发出去后，如果确认不了对方收到没有，会一直显示“发送中”，不会悄悄重发。
- 内置一个浏览器，Agent 也能操作它。Agent 还能在 Luma 里新开会话、查看其他会话。
- 有用量统计和文件浏览器。

**运行环境**

- 自带 Debian（ARM64），装好了 Python、Git、Node.js 和 pnpm。默认用 PRoot 运行，不需要 Root。
- 手机有 Root 的话，可以在“环境设置”里切换到 Root Chroot。切换后终端不再靠前台保活，也不用 Shizuku；App 被系统杀掉，终端还在后台跑着，回来能接上。这个切换不能撤销，想回到 PRoot 只能清除应用数据。

**终端**

- 用 Ghostty 的 libghostty-vt 做终端解析，能显示 Kitty 图形协议和 iTerm2 格式的图片。
- 字体随 App 打包，中日韩文字、Nerd Font 图标、数学符号和 Emoji 都能显示，不受手机系统字体影响。

## 安装

从 [Releases](../../releases/latest) 下载 APK 直接安装。需要 ARM64 手机，Android 12 及以上。

## 自己构建

需要 JDK 21、Android SDK（platform 37.2、build-tools 37.0.0）和 Python 3.11+。要重新编译原生库，还需要 NDK r27c 和 Zig 0.16.0。

代码 clone 下来就能编译、跑测试。打包 APK 还要几样不放在 Git 里的大文件（字体、pnpm、PRoot 源码和 Debian 根文件系统），除 Debian 镜像外都能用 `python3 tools/bootstrap.py` 自动下载或重建；Debian 镜像（`debian-arm64.zip.xz` 和 `rootfs-manifest.json`）可从 [`rootfs-v1`](../../releases/tag/rootfs-v1) Release 下载并放到 `runtime/noroot/src/main/assets/` 目录下。仅当存在 `apps/gui/signing/luma-release.properties`（已被 Git 忽略）时才会签名打包 Release APK。

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # 或者设置 ANDROID_HOME
export JAVA_HOME=/path/to/jdk-21

# 刚 clone 下来就能跑
./gradlew :apps:gui:testDebugUnitTest :apps:gui:lintDebug

# 打包 APK：先放好上面的 Debian 镜像文件，再拉取其余依赖并构建
python3 tools/bootstrap.py
LUMA_ONLINE=1 ./luma build    # 没有配置签名时输出未签名的 release 包
```

## 代码结构

```
Compose UI ──► SessionController ──┬─► Codex / OpenCode 客户端 ─────► CLI 自带的服务
                                   └─► NativeCliSession ─► cli_bridge（Python）─► 其他 CLI
终端视图 ─► libghostty-vt（JNI）            PTY ◄─ PRoot 里的 Debian，或 Root Chroot 里的 Debian + keeper 守护进程
```

```
apps/gui         App 本体：界面、会话、cli_bridge、浏览器
runtime/noroot   Debian/PRoot 环境、安装器、Shizuku 桥
runtime/root     Root Chroot 模式的终端运行时
backend/root     Root Chroot 模式下保持终端不断的 keeper 守护进程
shared/          终端模拟器、终端视图、libghostty JNI、公共 UI
tools/           构建脚本、资源校验、测试
```

## 参与

欢迎提 Issue 和 PR，先看一下 [`CONTRIBUTING.md`](CONTRIBUTING.md)。安全问题请按 [`SECURITY.md`](SECURITY.md) 私下报告，别发公开 Issue。

## 许可证

GNU GPL v3.0 only，详见 [`LICENSE`](LICENSE)、[`NOTICE`](NOTICE) 与 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

Luma 是独立项目，与各 CLI 厂商无隶属或背书关系，文中产品名称与图标归各自所有者。

## 致谢

感谢 [Termux](https://github.com/termux/termux-app)（经 [Termix](https://github.com/jeffusion/Termix)）、[Ghostty](https://github.com/ghostty-org/ghostty)、[PRoot](https://proot-me.github.io/)、[Debian](https://www.debian.org/)、[Maple Mono](https://github.com/subframe7536/maple-font)、[compose-miuix-ui](https://github.com/compose-miuix-ui/miuix)，以及 Android 和 Kotlin 社区。完整第三方组件清单见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。
