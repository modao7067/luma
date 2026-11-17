# CLI and plugin icons

These Android VectorDrawables identify CLI groups in the session drawer and Profile settings. All marks use the Material 3 theme tint rather than fixed brand colors. A CLI without a mapped asset uses the terminal symbol. Preserve the original asset proportions and use only the independently licensed sources below when adding or updating artwork.

## Brand assets

Eight assets come from [`@lobehub/icons-static-svg` 1.102.0](https://www.npmjs.com/package/@lobehub/icons-static-svg/v/1.102.0), npm `gitHead` [`989535d6c13da2dec5a2937fe2789d4af4fdd8e0`](https://github.com/lobehub/lobe-icons/tree/989535d6c13da2dec5a2937fe2789d4af4fdd8e0). Original package `icons/*.svg` geometry is preserved without rasterization. SVG arc flags are tokenized with the installed FontTools SVG parser and emitted with explicit separators: Android VectorDrawable does not reliably parse SVG-optimized adjacent arc flags. SVG `currentColor` becomes the Android Icon tint; all source viewports are 24×24. Copyright (c) 2023 LobeHub, MIT; full license in `app/src/main/res/raw/provider_lobe_license.txt`.

| CLI ID | Original asset | Android resource |
|---|---|---|
| Codex | `icons/openai.svg` | `ic_provider_openai` |
| OpenCode | `icons/opencode.svg` | `ic_provider_opencode` |
| DeepSeek Harness | `icons/deepseek.svg` | `ic_provider_deepseek` |
| Kimi Code | `icons/kimi.svg` | `ic_provider_kimi` |
| Claude Code | `icons/claude.svg` | `ic_provider_claude` |
| Antigravity | `icons/antigravity.svg` | `ic_provider_antigravity` |
| Qoder CLI | `icons/qoder.svg` | `ic_provider_qoder` |
| Grok Build | `icons/grok.svg` | `ic_provider_grok` |

- **pi**: [`badlogic/pi-mono/apps/p2/public/icons/favicon.svg`](https://github.com/badlogic/pi-mono/blob/1bc7076b2500cba83e9951f07d01f3308b0710d1/apps/p2/public/icons/favicon.svg), revision `1bc7076b2500cba83e9951f07d01f3308b0710d1`. The official pixel mark's five rectangles are fitted to a 24×24 viewport with a 2-unit inset; source colors are unified to theme tint. MIT, full notice `raw/provider_pi_license.txt`. This is the coding agent, **not** Lobe Icons' unrelated Inflection Pi brand.

These copyright licenses cover the distributed graphic source, not ownership of the providers' trademarks. Marks are used only to identify the corresponding third-party CLI; no affiliation or endorsement is asserted.

- **CodeBuddy Code**: official `@tencent-ai/codebuddy-code` 2.180.0 npm package, `dist/web-ui/pwa-icon.svg`. The current mascot mark is preserved as a tintable 12×12 VectorDrawable (`ic_provider_codebuddy`); only the PWA background rectangle and outer padding/scale are removed. Tencent Cloud MIT notice: `raw/provider_codebuddy_license.txt`.

## Functional menu icons

Google Material Design Icons, revision [`10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7`](https://github.com/google/material-design-icons/tree/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7), Apache-2.0, existing full license `raw/material_icons_license`:

- Plugin entry: `src/device/widgets/materialiconsround/24px.svg`.
- Skill entries: `src/action/extension/materialiconsround/24px.svg`.
- MCP entries: `src/hardware/device_hub/materialiconsround/24px.svg`.

These are native 24×24 filled/rounded symbols, centered inside the existing fixed icon slots. Existing generic process/tool icons and accepted process animation are unchanged. No new icon framework or dependency.
