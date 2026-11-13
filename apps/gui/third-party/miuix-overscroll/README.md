# Miuix translation overscroll

`ui/thirdparty/miuix/Overscroll.kt` and `SpringUtils.kt` are from the published Miuix **v0.9.4**, commit `39c40f99844227b853f0049a0933b1f3ae6c00ba` (Apache-2.0, Copyright 2025 compose-miuix-ui contributors):

- https://github.com/compose-miuix-ui/miuix/blob/39c40f99844227b853f0049a0933b1f3ae6c00ba/miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/utils/Overscroll.kt
- https://github.com/compose-miuix-ui/miuix/blob/39c40f99844227b853f0049a0933b1f3ae6c00ba/miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/utils/SpringUtils.kt

Local changes: package names and removal of unused pull-to-refresh imports/state access (the upstream bypass is always false here). The damping curve, spring engine, gesture handling, nested-scroll consumption, and layer translation are unchanged. Keep these two files versioned together when updating upstream; do not tune physics locally.

The modifier is applied only to parent/subagent conversation lists, with Android's default stretch disabled (`overscrollEffect = null`). It supports short content, unlike the upstream OverscrollFactory route. A placement-layer translation keeps glyph, image, table and selection geometry unscaled; fixed headers, composer and overlays are outside this layer. Each list provides its own `OverScrollState`, allowing one-shot near-bottom alignment to wait for rebound completion. No Miuix theme or UI dependency is linked.

License: `app/src/main/assets/licenses/miuix-overscroll-0.9.4-Apache-2.0.txt`.
