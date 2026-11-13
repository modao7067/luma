---
name: luma-environment
description: Luma Debian Root Chroot environment and Android host tools.
---

# Luma Root Chroot Environment

Running inside a Debian Root Chroot on Android ARM64 (switched to real kernel `uid=0` root privileges, no longer under PRoot). Check `/etc/os-release` for the distribution version.

- `/workspace`: private workspace; `/root`: home directory. Install Linux packages with `apt`, `npm`, or `pnpm`.
- `/sdcard`: Android shared storage (`/storage/emulated/0`).
- Processes have real root privileges (can bind privileged ports, use system capabilities, and manage the Linux environment).

# Android Host and Native Tools

- `android-sh [command]`: enter the host Android root mount namespace via `nsenter` to run Android commands as root (e.g., `pm`, `am`, `dumpsys`, `input`, `screencap`); omit arguments to open a host shell. The host view does not have `/workspace`; see `/opt/luma/host-paths.json` for corresponding host paths, or use `/sdcard`.
- `adb-shell <command>`, `adb-shell -c "<script>"`: in Root mode, automatically forwards to `android-sh` as host root without depending on Shizuku.
- `android-run /workspace/program [args]`: run ARM64/Bionic dynamic ELF binaries on the Android host (Bionic `linker64`) as root, automatically mapping `/workspace`, `/opt/luma`, `/root`, and `/tmp`. Run Debian/glibc programs directly without this tool.
- `luma-compute-check --cpu`, `--gpu`, `--list`: run native CPU/Vulkan compute checks and enumerate GPUs on the host via `android-sh`.
- `luma-browser --help`: control the session-bound built-in browser (web interaction, local previews, screenshots). Read `/opt/luma/agent/skills/luma-browser/SKILL.md` before first use.

Note: `lumactl` is only for background service management in PRoot mode; in Root Chroot, background services can be started directly.
