# Contributing to Luma

Thanks for your interest! Issues and pull requests are welcome, in English or Chinese.

## Before you start

- For larger changes, open an issue first so we can agree on the direction.
- Security issues: follow [`SECURITY.md`](SECURITY.md) instead of opening a public issue.

## Pull requests

- Keep each PR focused on one change; avoid unrelated reformatting.
- Use [Conventional Commits](https://www.conventionalcommits.org/) style messages, e.g. `fix(gui): …`, `feat(runtime): …`, `docs: …`.
- Make sure the checks that CI runs still pass locally:

  ```sh
  python3 tools/tests/verify-layout.py
  python3 tools/tests/verify-build-entry.py
  python3 tools/tests/verify-production-gates.py
  ./gradlew :apps:gui:compileDebugKotlin
  ./gradlew :apps:gui:testDebugUnitTest
  ```

- UI changes: include before/after screenshots.
- Do not commit build outputs, APKs, signing material, tokens, or large binaries; large inputs belong in `artifacts/` and `tools/artifacts.lock.json`.
- New third-party code, fonts or icons must have a GPL-3.0-compatible license and be listed in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## License

Luma is licensed under GPL-3.0-only. By submitting a contribution you agree that it is licensed under the same terms. No CLA or sign-off is required.
