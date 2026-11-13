# Security Policy

Luma runs coding-agent CLIs, shells and (optionally) a Root backend on Android devices, so security bugs can have real impact. Thank you for reporting them responsibly.

## Reporting a vulnerability

**Do not open a public issue for security problems.**

Email **modao7067@gmail.com** with:

- affected component (`apps/gui`, `runtime/*`, `shared/*`, `backend/root`, …) and version / commit;
- device, Android version and whether Root mode is involved;
- steps to reproduce or a proof of concept;
- the impact you expect (e.g. privilege escalation, sandbox escape, token or credential exposure, unauthorised command execution).

You should get an acknowledgement within 7 days. Please give us a reasonable time to ship a fix before disclosing publicly; we will credit you in the release notes unless you prefer otherwise.

## Scope

In scope: code in this repository and the APKs built from it.

Out of scope: vulnerabilities in third-party CLIs that Luma can drive (Codex, Claude Code, Gemini CLI, etc.), in upstream Debian packages, or in the Android OS / Root solution itself — please report those to their respective maintainers.

## Supported versions

Only the latest release and the latest commit on `main` receive security fixes.
