# AndroidX Material3 floating menu seam

`ui/OriginMenu.kt` adapts the thin Android DropdownMenu shell and MenuContent from
AndroidX Material3 **1.4.0**, Apache-2.0, Copyright 2021 The Android Open Source Project.
Source artifact: https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-android/1.4.0/material3-android-1.4.0-sources.jar
Files: `AndroidMenu.android.kt`, `Menu.kt`, `tokens/StandardMotionTokens.kt`.

Material Surface, MenuDefaults, DropdownMenuItem, 0.8→1 scale/alpha and the standard
FastSpatial (0.9/1400) / FastEffects (1/3800) springs are retained. Public 1.4.0
DropdownMenu does not expose its position provider or MenuContent. No reflection
or suppressed visibility accesses are used.

Adaptations: choose the origin once; reset only on screen-size/configuration
changes; reserve a transparent fixed safe-screen-height carrier while the painted
menu keeps natural height and bounded scrolling. Upward menus retain their initial
button-facing bottom edge and grow upward inside that carrier; downward menus retain
their top edge. The viewport limit is available screen space, never initial content
height. Section reveals use sequential Column layout and native reversible size
animation; large lazy catalogs use the actual menu viewport rather than a 240dp cap;
use explicit requested width rather than unsupported LazyColumn intrinsic queries; set official above-IME Popup flags and adjustNothing.
LumaCardShape(28.dp) rounds the Surface; every native item clips its focus/ripple
state layer to CircleShape (capsule), including unselected group headings.
Native Popup owns outside-window/Back handling. Transparent carrier taps dismiss
without reaching underlying controls. No trigger tracking, sheet, glass card or
IME offset is introduced.
