# SukiSU 四 Tab 基座玻璃

Luma 的主界面玻璃使用 **SukiSU 四 Tab 底栏的基座参数**，保留现有 MD3 配色、44 dp 顶栏和输入框布局。不是 `TonalCard`，也不套用底栏内可拖动选中指示器的按压参数。

## 固定来源与调用链

- SukiSU-Ultra [`e67b14e6bbbbd3e67f24df658a01d3e73a64159c`](https://github.com/SukiSU-Ultra/SukiSU-Ultra/tree/e67b14e6bbbbd3e67f24df658a01d3e73a64159c)。
- [`manager/app/src/main/java/com/sukisu/ultra/ui/component/bottombar/BottomBarMaterial.kt`](https://github.com/SukiSU-Ultra/SukiSU-Ultra/blob/e67b14e6bbbbd3e67f24df658a01d3e73a64159c/manager/app/src/main/java/com/sukisu/ultra/ui/component/bottombar/BottomBarMaterial.kt) 的 Home / Superuser / Module / Settings 四项进入 `LiquidBottomBarMaterial`，传入 `MaterialTheme.colorScheme.surfaceContainerHigh`。
- [`ui/component/FloatingBottomBar.kt`](https://github.com/SukiSU-Ultra/SukiSU-Ultra/blob/e67b14e6bbbbd3e67f24df658a01d3e73a64159c/manager/app/src/main/java/com/sukisu/ultra/ui/component/FloatingBottomBar.kt) 的第一层 `Row.drawBackdrop` 是实际基座；文件注明适配自 compose-miuix-ui example，Apache 2.0。
- [`ui/component/liquid/Lens.kt`](https://github.com/SukiSU-Ultra/SukiSU-Ultra/blob/e67b14e6bbbbd3e67f24df658a01d3e73a64159c/manager/app/src/main/java/com/sukisu/ultra/ui/component/liquid/Lens.kt)、[`Vibrancy.kt`](https://github.com/SukiSU-Ultra/SukiSU-Ultra/blob/e67b14e6bbbbd3e67f24df658a01d3e73a64159c/manager/app/src/main/java/com/sukisu/ultra/ui/component/liquid/Vibrancy.kt) 文件头注明 Kyant0/AndroidLiquidGlass → compose-miuix-ui，Apache 2.0。
- SukiSU 固定的 `top.yukonga.miuix.kmp:miuix-blur-android:0.9.4` [源码包](https://repo.maven.apache.org/maven2/top/yukonga/miuix/kmp/miuix-blur-android/0.9.4/miuix-blur-android-0.9.4-sources.jar) 提供 `highlight/HighlightStyle.kt`、`internal/Shaders.kt`、`internal/BlurEffect.kt`、`androidMain/.../RenderEffectCompat.android.kt`。版权：Copyright 2026, compose-miuix-ui contributors；Apache-2.0。

## 参数与实现

| 基座设置 | Luma 值 |
| --- | --- |
| Surface | SPEC_2025 的 `surfaceContainerHigh`，alpha **0.4**，日夜相同 |
| Vibrancy | brightness **0**、contrast **1**、saturation **1.5**，Rec.709 亮度系数 0.2126 / 0.7152 / 0.0722 |
| Blur | 横纵均 **4 dp**；miuix 的 sigma = radius × **0.45** |
| Lens | refractionHeight **24 dp**、refractionAmount **24 dp**；depthEffect=false、chromaticAberration=0 |
| 采样外扩 | **40 dp**，只扩大离屏采样，不增加控件布局尺寸 |
| Highlight | 宽 **1 dp**，alpha **0.75**，白色基础描边 alpha **0.12**，内光晕 **2 dp**，双峰、Plus 混合 |
| 光源 | 主光强度 **1**、z=-0.05；副光 `(0.5, 0.8, -0.5)`、强度 **0.4**；基座采用上游静置重力角 -90° 再偏移 -45° |
| 阴影 | 黑色、半径 **10 dp**，亮色 alpha **0.1** / 暗色 **0.2**，零偏移 |

生产代码：`ui/SukiGlassDefaults.kt`、`ui/LiquidChrome.kt`、`ui/SukiGlassHighlight.kt`。按压与菜单动作沿用 Luma 现有状态机。

### Compose 1.8.1 兼容层

没有链接 miuix 0.9.4；其 POM 要求 Kotlin 2.4.20 / Compose 1.12.0。保留 Luma 的 Kotlin 2.1.21 / Compose 1.8.1：

- 镜片移植上游无色散 SDF 算法，统一圆角，坐标改为显式 `samplePadding`，保留零长度梯度保护。
- 双峰高光 shader 和法线/光源算法由 miuix 源码适配；直边内部的零向量直接取平面法线，修复 `normalize(0)` 在高面板产生的黑色接缝；保留静置光源方向，不引入重力传感器或四 Tab 拖动指示器。
- 饱和度以等价 ColorMatrix 实现；模糊用系统 RenderEffect，并通过 `(sigma - 0.5) / 0.57735` 换算 Android 半径。采用相同 sigma，不声称原生 Gaussian 与 miuix 的自定义降采样/tap kernel 逐像素相同。
- API 33+ 为 **vibrancy → blur → lens**，之后合成 tint、高光和清晰控件。API 31–32 保留振彩、模糊与 tint，不执行 AGSL 镜片/高光。
- 阴影用独立离屏圆角遮罩与原生 blur 绘制。没有多层浮雕或内阴影。
- 背景录制 `content` 内固定 `LocalGlassScene provides null`；玻璃及回到底部按钮仅在兄弟 top/bottom/overlay 层采样，防止 RenderNode 自采样循环。

用户消息、侧栏选中项均使用不透明 `secondaryContainer` 的原生 MD3 Surface。菜单保留不透明表面和 6 dp 阴影，不采样玻璃。

## 许可与复制范围

SukiSU 仓库整体为 GPL-3.0；未复制其四 Tab 业务/导航实现。玻璃参数从该实现核对，镜片依据明确标注 Apache 2.0 的 `Lens.kt` 适配；双峰 shader、光源设置与半径换算依据 miuix 的 Apache-2.0 源码适配。没有复制 SukiSU 图标或素材。

`LICENSE-Apache-2.0.txt` 来自 [miuix v0.9.4 LICENSE](https://github.com/compose-miuix-ui/miuix/blob/v0.9.4/LICENSE)。APK 内含 `licenses/Suki-Glass-Apache-2.0.txt` 和 `licenses/Suki-Glass-NOTICE.txt`，适配源码保留版权/来源注释。
