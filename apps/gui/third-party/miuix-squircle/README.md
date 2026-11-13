# Miuix continuous corners

`ui/shapes/MiuixSquirclePath.kt` is the Apache-2.0 path builder from compose-miuix-ui/miuix v0.9.4, fixed commit `39c40f99844227b853f0049a0933b1f3ae6c00ba`:
https://github.com/compose-miuix-ui/miuix/blob/39c40f99844227b853f0049a0933b1f3ae6c00ba/miuix-squircle/src/commonMain/kotlin/top/yukonga/miuix/kmp/squircle/SquirclePath.kt

Only its package is changed. `LumaCardShape` adapts the path to Compose Shape, shared by MD3 card/menu/bubble fill, clip, ripple and shadow. Exact circles/capsules keep AndroidX RoundedCornerShape. No Miuix UI components or shader dependencies are included, and no Compose/Kotlin versions are changed. Existing liquid-glass capsule optics remain unchanged.
