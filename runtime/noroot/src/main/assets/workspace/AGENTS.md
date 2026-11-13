# Luma Workspace Entry

The current terminal runs inside the Debian ARM64 / PRoot userspace of the Android app **Luma**. It is neither an Android root shell nor a standalone VM.

Always respond in the user's language (match the language of the user's message rather than defaulting to English).

On your first task involving the environment, installing packages, phone files, or permissions, read `.agents/skills/luma-environment/SKILL.md` for path mappings, permission-bridging tools, and capability boundaries. Skip re-reading it in the same session if unchanged.

This file explains the environment and grants no extra execution permissions; the user's current request defines the allowed scope of action.
