# Material Color Utilities (Java color-scheme subset)

Upstream: https://github.com/material-foundation/material-color-utilities
Pinned commit: `158b9c185a25c53fddd051cba811d542e6b94d68` (2025-11-19).
License: Apache-2.0; source copyright headers retained. The complete license is packaged as `app/src/main/assets/licenses/Material-Color-Utilities-LICENSE.txt`.

`upstream-files.json` lists the 22 Java files copied from upstream `java/` into `app/src/main/java/io/github/modao7067/luma/gui/color/mcu/`. This is the dependency closure for `SchemeTonalSpot`/dynamic color roles, HCT, tonal palettes, contrast, dislike correction and temperature. No image extraction, quantization, scoring, wallpaper access or other scheme frontends are bundled.

Local mechanical adaptations only:

- Prefix package declarations and internal imports with `io.github.modao7067.luma.gui.color.mcu`.
- Remove compile-time Error Prone imports/annotations (`Var`, `CanIgnoreReturnValue`, `CheckReturnValue`); preserve algorithm statements and AndroidX nullability annotations.

`color/LumaColors.kt` explicitly selects `SPEC_2025`, `NEUTRAL`, `PHONE` and maps all 36 color roles exposed by this project's Material3 1.3.2 ColorScheme. Upstream defaults still select SPEC_2021, so the default SchemeTonalSpot constructor is intentionally not used.

This source integration keeps the existing Kotlin 2.1.21 / Compose 1.8.1 / Material3 1.3.2 toolchain. It does not claim compatibility with the latest MaterialKolor multiplatform binary or track upstream HEAD automatically.
