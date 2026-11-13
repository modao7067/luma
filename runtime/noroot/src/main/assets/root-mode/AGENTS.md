# Luma Workspace Entry

The current terminal runs inside the Debian ARM64 / Root Chroot environment of the Android app **Luma**, with real root privileges. When Android host operations are needed, use `android-sh`.

Always respond in the user's language (match the language of the user's message rather than defaulting to English).

On your first task involving the environment, installing packages, phone files, or permissions, read `.agents/skills/luma-environment/SKILL.md` for path descriptions, host tools, and capability boundaries. Skip re-reading it in the same session if unchanged.

This file explains the environment and grants no extra execution permissions; the user's current request defines the allowed scope of action.
