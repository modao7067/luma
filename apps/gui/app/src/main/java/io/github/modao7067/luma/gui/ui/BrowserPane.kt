package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.tr

import android.graphics.Bitmap
import android.view.View
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.modao7067.luma.gui.LumaApplication
import io.github.modao7067.luma.gui.browser.AgentBrowser
import io.github.modao7067.luma.gui.browser.BrowserPolicy
import kotlin.math.max
import kotlin.math.roundToInt

internal val LocalAgentBrowser = staticCompositionLocalOf<AgentBrowser?> { null }
internal val LocalBrowserPreviewFixture = staticCompositionLocalOf<Bitmap?> { null }

@Composable
internal fun rememberAgentBrowser(): AgentBrowser? {
    LocalAgentBrowser.current?.let { return it }
    if (LocalInspectionMode.current) return null
    val app = LocalContext.current.applicationContext as? LumaApplication
    return app?.browser
}

@Composable
private fun BrowserChromeCapsule(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = FloatingChromeRadius,
    tonalTint: Color? = null,
    borderColor: Color? = null,
    elevation: Dp? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val isLight = scheme.surface.luminance() > 0.5f
    val shape = RoundedCornerShape(cornerRadius)
    val fill = tonalTint ?: if (isLight) {
        scheme.surfaceContainerLowest
    } else {
        scheme.surfaceContainerHigh
    }
    val rimColor = borderColor ?: if (isLight) {
        scheme.outlineVariant.copy(alpha = 0.52f)
    } else {
        Color.White.copy(alpha = 0.12f)
    }
    val shadowDp = elevation ?: if (isLight) 1.5.dp else 2.5.dp
    Box(
        modifier
            .shadow(
                elevation = shadowDp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = if (isLight) 0.08f else 0.28f),
                spotColor = Color.Black.copy(alpha = if (isLight) 0.10f else 0.34f),
            )
            .clip(shape)
            .background(fill, shape)
            .border(BorderStroke(0.75.dp, rimColor), shape),
        content = content,
    )
}

@Composable
private fun BrowserMoreGlyph() {
    val color = LocalContentColor.current
    Canvas(Modifier.size(20.dp)) {
        for (fraction in listOf(0.24f, 0.5f, 0.76f)) {
            drawCircle(color, radius = 1.65.dp.toPx(), center = Offset(size.width / 2f, size.height * fraction))
        }
    }
}

@Composable
private fun BrowserChevronGlyph(forward: Boolean) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.85.dp.toPx()
        val path = Path().apply {
            if (!forward) {
                moveTo(11.5.dp.toPx(), 4.dp.toPx())
                lineTo(6.dp.toPx(), size.height / 2f)
                lineTo(11.5.dp.toPx(), size.height - 4.dp.toPx())
            } else {
                moveTo(6.5.dp.toPx(), 4.dp.toPx())
                lineTo(12.dp.toPx(), size.height / 2f)
                lineTo(6.5.dp.toPx(), size.height - 4.dp.toPx())
            }
        }
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun BrowserVectorMenuItem(
    label: String,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val active = LocalMenuActive.current
    val contentAlpha = if (enabled) 1f else 0.38f
    val foreground = colors.onSurface.copy(alpha = contentAlpha)
    DropdownMenuItem(
        onClick = onClick,
        enabled = active && enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(CircleShape)
            .semantics(mergeDescendants = true) { this.role = Role.Button },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
        colors = MenuDefaults.itemColors(
            textColor = foreground,
            leadingIconColor = foreground,
            disabledTextColor = foreground,
            disabledLeadingIconColor = foreground,
        ),
        leadingIcon = {
            Surface(
                shape = CircleShape,
                color = colors.surfaceContainerHighest.copy(alpha = contentAlpha),
                contentColor = colors.onSurfaceVariant.copy(alpha = contentAlpha),
            ) {
                Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                    icon()
                }
            }
        },
        text = {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
internal fun AttachedBrowserPane(
    sessionId: String,
    active: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onQuoteSelection: ((String) -> Unit)? = null,
) {
    val browser = rememberAgentBrowser()
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val addressFocusRequester = remember { FocusRequester() }
    val inspection = LocalInspectionMode.current

    DisposableEffect(browser, sessionId, active) {
        if (browser != null && active) {
            browser.setForegroundSession(sessionId)
        }
        onDispose {
            if (browser != null && browser.foregroundSession.value == sessionId) {
                browser.setForegroundSession(null)
            }
        }
    }

    val tabs = browser?.tabs?.filter { it.owner == sessionId && !it.closed }.orEmpty()
    var selectedId by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    val tab = tabs.find { it.id == selectedId } ?: browser?.activeTabFor(sessionId) ?: tabs.lastOrNull()
    LaunchedEffect(tab?.id) {
        if (tab != null) {
            selectedId = tab.id
            browser?.selectActiveTab(sessionId, tab.id)
        }
    }

    var address by rememberSaveable(sessionId, tab?.id, tab?.url?.value) {
        mutableStateOf(tab?.url?.value.orEmpty())
    }
    var localError by remember(sessionId) { mutableStateOf<String?>(null) }
    var moreOpen by remember(sessionId) { mutableStateOf(false) }
    var requestAddressFocus by remember { mutableStateOf(false) }

    LaunchedEffect(requestAddressFocus) {
        if (requestAddressFocus) {
            requestAddressFocus = false
            runCatching { addressFocusRequester.requestFocus() }
            keyboard?.show()
        }
    }
    LaunchedEffect(active) {
        if (!active) {
            moreOpen = false
            focusManager.clearFocus()
        }
    }

    val navigateAddress = {
        runCatching {
            val url = BrowserPolicy.addressUrl(address)
            val b = requireNotNull(browser) { tr("浏览器服务未初始化", "Browser service is not initialized") }
            if (tab == null) {
                val created = b.newTab(sessionId, url)
                selectedId = created.id
            } else {
                b.navigate(tab, url)
            }
            address = url
            localError = null
            focusManager.clearFocus()
            keyboard?.hide()
        }.onFailure { localError = it.message }
        Unit
    }

    val activeNotice = tab?.notice?.value
    LaunchedEffect(activeNotice, tab?.id) {
        if (activeNotice != null) {
            delay(3200)
            if (tab.notice.value == activeNotice) tab.notice.value = null
        }
    }

    val pendingChooser = tab?.fileChooser?.value
    val multiFileLauncher = if (!inspection) {
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val req = tab?.fileChooser?.value
            tab?.fileChooser?.value = null
            req?.complete(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
        }
    } else null
    val singleFileLauncher = if (!inspection) {
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val req = tab?.fileChooser?.value
            tab?.fileChooser?.value = null
            req?.complete(uri?.let { arrayOf(it) })
        }
    } else null
    LaunchedEffect(pendingChooser, active) {
        if (pendingChooser != null && active) {
            val mimeTypes = pendingChooser.acceptTypes
                .map { if (it.contains('/')) it else "*/*" }
                .distinct()
                .ifEmpty { listOf("*/*") }
                .toTypedArray()
            runCatching {
                if (pendingChooser.multiple) multiFileLauncher?.launch(mimeTypes)
                else singleFileLauncher?.launch(mimeTypes)
            }.onFailure {
                tab.fileChooser.value = null
                pendingChooser.complete(null)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .testTag("attached-browser-pane"),
    ) {
        Surface(
            color = colors.surfaceContainerLow,
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = FloatingChromeHorizontalPadding, vertical = FloatingChromeVerticalPadding),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BrowserChromeCapsule(
                            modifier = Modifier
                                .size(FloatingChromeSize)
                                .liquidClickable(cornerRadius = FloatingChromeRadius, onClick = {
                                    focusManager.clearFocus()
                                    keyboard?.hide()
                                    onBack()
                                })
                                .semantics { contentDescription = tr("返回会话", "Back to session") }
                                .testTag("browser-back-button"),
                            cornerRadius = FloatingChromeRadius,
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                ComposerGlyph("back")
                            }
                        }

                        BrowserChromeCapsule(
                            modifier = Modifier
                                .weight(1f)
                                .height(FloatingChromeSize),
                            cornerRadius = FloatingChromeRadius,
                        ) {
                            Row(
                                Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                                    MenuIcon(
                                        if (address.startsWith("https://", ignoreCase = true)) MenuSymbol.LOCK else MenuSymbol.BROWSER,
                                        size = 15.dp,
                                    )
                                }
                                BasicTextField(
                                    value = address,
                                    onValueChange = { address = it },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Go,
                                    ),
                                    keyboardActions = KeyboardActions(onGo = { navigateAddress() }),
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                                    cursorBrush = SolidColor(colors.primary),
                                    modifier = Modifier
                                        .weight(1f)
                                        .focusRequester(addressFocusRequester)
                                        .semantics { contentDescription = tr("网址输入", "URL input") }
                                        .testTag("browser-address-field"),
                                    decorationBox = { inner ->
                                        Box(contentAlignment = Alignment.CenterStart) {
                                            if (address.isEmpty()) {
                                                Text(
                                                    tr("输入网址并按回车…", "Enter URL and press Enter…"),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = colors.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            inner()
                                        }
                                    },
                                )
                            }
                        }

                        Box {
                            BrowserChromeCapsule(
                                modifier = Modifier
                                    .size(FloatingChromeSize)
                                    .liquidClickable(cornerRadius = FloatingChromeRadius, onClick = { moreOpen = true })
                                    .semantics { contentDescription = tr("更多", "More") }
                                    .testTag("browser-more-button"),
                                cornerRadius = FloatingChromeRadius,
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    BrowserMoreGlyph()
                                }
                            }

                            val isLoading = tab?.loading?.value == true
                            OriginMenu(moreOpen, { moreOpen = false }) {
                                BrowserVectorMenuItem(
                                    label = tr("后退", "Back"),
                                    enabled = tab != null && tab.canGoBack.value && !tab.crashed.value,
                                    icon = { BrowserChevronGlyph(forward = false) },
                                    onClick = {
                                        moreOpen = false
                                        if (tab != null && tab.web.canGoBack()) tab.web.goBack()
                                    },
                                )
                                BrowserVectorMenuItem(
                                    label = tr("前进", "Forward"),
                                    enabled = tab != null && tab.canGoForward.value && !tab.crashed.value,
                                    icon = { BrowserChevronGlyph(forward = true) },
                                    onClick = {
                                        moreOpen = false
                                        if (tab != null && tab.web.canGoForward()) tab.web.goForward()
                                    },
                                )
                                ActionMenuItem(
                                    label = if (isLoading) tr("停止", "Stop") else tr("刷新", "Reload"),
                                    icon = if (isLoading) MenuSymbol.STOP else MenuSymbol.REFRESH,
                                    iconContainer = true,
                                    enabled = tab != null && !tab.crashed.value,
                                    onClick = {
                                        moreOpen = false
                                        if (tab != null && !tab.crashed.value) {
                                            if (isLoading) {
                                                tab.web.stopLoading()
                                                tab.loading.value = false
                                            } else {
                                                tab.loading.value = true
                                                tab.web.reload()
                                            }
                                        }
                                    },
                                )
                                HorizontalDivider(
                                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    color = colors.outlineVariant.copy(alpha = 0.4f),
                                )
                                BrowserVectorMenuItem(
                                    label = tr("新建标签页", "New tab"),
                                    enabled = browser != null,
                                    icon = { ComposerGlyph("plus") },
                                    onClick = {
                                        moreOpen = false
                                        if (browser != null) {
                                            val created = browser.newTab(sessionId)
                                            selectedId = created.id
                                            address = ""
                                            requestAddressFocus = true
                                        }
                                    },
                                )
                                if (onQuoteSelection != null) {
                                    ActionMenuItem(
                                        label = tr("引用选中内容", "Quote selection"),
                                        icon = MenuSymbol.NEW_CHAT,
                                        iconContainer = true,
                                        enabled = browser != null && tab != null && !tab.loading.value && !tab.crashed.value,
                                        onClick = {
                                            moreOpen = false
                                            val currentTab = tab
                                            if (browser != null && currentTab != null) {
                                                scope.launch {
                                                    val quote = browser.extractSelectionQuote(currentTab)
                                                    if (quote != null) {
                                                        onQuoteSelection(quote)
                                                    } else {
                                                        currentTab.notice.value = tr("请先在网页中选中要引用的文字", "Select text on the page first to quote it")
                                                    }
                                                }
                                            }
                                        },
                                    )
                                }
                                ActionMenuItem(
                                    label = tr("桌面版网站", "Desktop site"),
                                    icon = MenuSymbol.BROWSER,
                                    iconContainer = true,
                                    selected = tab?.desktopMode?.value == true,
                                    enabled = browser != null && tab != null && !tab.crashed.value,
                                    onClick = {
                                        moreOpen = false
                                        if (browser != null && tab != null && !tab.crashed.value) {
                                            browser.setDesktopMode(tab, !tab.desktopMode.value)
                                        }
                                    },
                                )
                                ActionMenuItem(
                                    label = tr("允许 AI 现场操作", "Allow live AI control"),
                                    icon = MenuSymbol.MODEL,
                                    iconContainer = true,
                                    selected = browser?.allowLiveAgent?.value == true,
                                    enabled = browser != null,
                                    onClick = {
                                        moreOpen = false
                                        if (browser != null) {
                                            browser.allowLiveAgent.value = !browser.allowLiveAgent.value
                                        }
                                    },
                                )
                            }
                        }
                    }

                    if (tabs.isNotEmpty()) {
                        LazyRow(
                            Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                                .padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
                                .testTag("browser-tab-bar"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(tabs, key = { it.id }) { t ->
                                val isSelected = tab?.id == t.id
                                BrowserChromeCapsule(
                                    cornerRadius = 18.dp,
                                    tonalTint = if (isSelected) {
                                        colors.secondaryContainer
                                    } else {
                                        colors.surfaceContainer
                                    },
                                    borderColor = if (isSelected) {
                                        colors.primary.copy(alpha = 0.28f)
                                    } else {
                                        colors.outlineVariant.copy(alpha = 0.38f)
                                    },
                                    elevation = if (isSelected) 1.5.dp else 0.dp,
                                    modifier = Modifier
                                        .height(36.dp)
                                        .liquidClickable(cornerRadius = 18.dp) {
                                            selectedId = t.id
                                            browser?.selectActiveTab(sessionId, t.id)
                                        },
                                ) {
                                    Row(
                                        Modifier
                                            .fillMaxHeight()
                                            .padding(start = 12.dp, end = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            t.title.value.let { if (it.isBlank() || it == "新标签页" || it == "New tab") tr("新标签页", "New tab") else it }.take(22),
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            ),
                                            color = if (isSelected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Box(
                                            Modifier
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .clickable { browser?.close(t) }
                                                .semantics { contentDescription = tr("关闭标签页", "Close tab") },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            ComposerGlyph("close")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = colors.outlineVariant.copy(alpha = 0.55f),
                    )
                }

                if (tab?.loading?.value == true) {
                    val prog = (tab.progress.intValue / 100f).coerceIn(0.06f, 0.98f)
                    LinearProgressIndicator(
                        progress = { prog },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(2.dp),
                    )
                }
            }
        }

        var contentSize by remember { mutableStateOf(IntSize.Zero) }
        LaunchedEffect(contentSize, browser) {
            if (browser != null && contentSize.width > 0 && contentSize.height > 64) {
                browser.updateContentViewportGeometry(
                    widthPx = contentSize.width,
                    contentHeightPx = contentSize.height,
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .background(colors.background)
                .onSizeChanged { contentSize = it },
        ) {
            if (browser != null && tab != null && !tab.crashed.value) {
                var attachedTab by remember { mutableStateOf<AgentBrowser.Tab?>(null) }
                var hostRef by remember { mutableStateOf<FrameLayout?>(null) }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        FrameLayout(ctx).also { frame ->
                            frame.clipChildren = true
                            hostRef = frame
                        }
                    },
                    update = { frame ->
                        hostRef = frame
                        if (attachedTab !== tab) {
                            attachedTab?.let { prev -> browser.detach(prev, frame) }
                            browser.attach(tab, frame)
                            tab.web.setLayerType(View.LAYER_TYPE_NONE, null)
                            attachedTab = tab
                        }
                    },
                )
                DisposableEffect(browser) {
                    onDispose {
                        val host = hostRef
                        val current = attachedTab
                        if (host != null && current != null) {
                            browser.detach(current, host)
                        }
                        attachedTab = null
                    }
                }
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (tab?.crashed?.value == true) tr("网页渲染进程已退出", "WebView renderer exited") else tr("输入网址开始浏览", "Enter a URL to start browsing"),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (tab?.crashed?.value == true) tr("请关闭该标签页后从菜单新建标签页重试。", "Close this tab and open a new one from the menu.")
                            else tr("在上方地址栏输入 HTTPS 或 localhost 地址并按回车跳转；同一应用内所有会话自动共用已登录的网站状态。", "Enter an HTTPS or localhost URL above and press Enter. Signed-in sessions are shared across Luma."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }

            val activeError = localError ?: tab?.error?.value
            var retainedError by remember(sessionId) { mutableStateOf(activeError.orEmpty()) }
            SideEffect { if (activeError != null) retainedError = activeError }
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AnimatedVisibility(
                    visible = activeError != null,
                    enter = expandVertically(spatialSizeSpring()) + fadeIn(),
                    exit = shrinkVertically(spatialSizeSpring()) + fadeOut(),
                ) {
                    ErrorNotice(activeError ?: retainedError) {
                        localError = null
                        tab?.error?.value = null
                    }
                }
                AnimatedVisibility(
                    visible = activeNotice != null,
                    enter = expandVertically(spatialSizeSpring()) + fadeIn(),
                    exit = shrinkVertically(spatialSizeSpring()) + fadeOut(),
                ) {
                    LiquidSurface(
                        tonalTint = colors.secondaryContainer.copy(alpha = 0.92f),
                        cornerRadius = 16.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                    ) {
                        Text(
                            activeNotice.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }

    val dialog = tab?.jsDialog?.value
    if (dialog != null && active) {
        var promptText by remember(dialog) { mutableStateOf(dialog.defaultPrompt) }
        LiquidModalDialog(
            title = when (dialog.kind) {
                AgentBrowser.JsDialogKind.ALERT -> tr("网页提示", "Page alert")
                AgentBrowser.JsDialogKind.CONFIRM -> tr("网页确认", "Page confirm")
                AgentBrowser.JsDialogKind.PROMPT -> tr("网页输入", "Page prompt")
            },
            subtitle = dialog.url.take(64),
            onDismiss = { dialog.cancel() },
            confirmButton = {
                TextButton(onClick = { dialog.confirm(promptText) }) { Text(tr("确定", "OK")) }
            },
            dismissButton = if (dialog.kind != AgentBrowser.JsDialogKind.ALERT) {
                { TextButton(onClick = { dialog.cancel() }) { Text(tr("取消", "Cancel")) } }
            } else null,
        ) {
            if (dialog.message.isNotBlank()) {
                Text(
                    dialog.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            if (dialog.kind == AgentBrowser.JsDialogKind.PROMPT) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
internal fun BoxScope.BrowserFloatingPreview(
    sessionId: String,
    browserOpen: Boolean,
    padding: PaddingValues,
    activeTurnId: String? = null,
    tailAssistantSignature: String? = null,
    onOpenBrowser: () -> Unit,
) {
    val browser = rememberAgentBrowser()
    val fixtureBitmap = LocalBrowserPreviewFixture.current
    val activeTab = browser?.activeTabFor(sessionId)
    val bitmap = fixtureBitmap ?: activeTab?.previewBitmap?.value
    val agentOperating = if (fixtureBitmap != null) true else (browser?.isAgentOperating(sessionId) == true)
    val agentSeq = browser?.agentSeqFor(sessionId) ?: 0
    val inFlight = browser?.isAgentCommandInFlight(sessionId) == true

    var dismissed by rememberSaveable(sessionId) { mutableStateOf(false) }
    var lastObservedSeq by rememberSaveable(sessionId) { mutableIntStateOf(agentSeq) }
    var baselineAssistantSig by rememberSaveable(sessionId) { mutableStateOf<String?>(tailAssistantSignature) }
    var sawTurnActive by rememberSaveable(sessionId) { mutableStateOf(activeTurnId != null) }

    LaunchedEffect(agentSeq, activeTurnId, tailAssistantSignature, agentOperating, inFlight) {
        if (agentSeq != lastObservedSeq) {
            lastObservedSeq = agentSeq
            dismissed = false
            baselineAssistantSig = tailAssistantSignature
            sawTurnActive = activeTurnId != null
        }
        if (activeTurnId != null) {
            sawTurnActive = true
        }
        if (fixtureBitmap == null && agentOperating && !inFlight) {
            val startedFinalReply = tailAssistantSignature != null && tailAssistantSignature != baselineAssistantSig
            val turnFinished = sawTurnActive && activeTurnId == null
            if (startedFinalReply || turnFinished) {
                browser?.stopAgentOperating(sessionId)
            }
        }
    }

    val visible = !browserOpen && !dismissed && agentOperating && bitmap != null
    var retainedBitmap by remember(sessionId) { mutableStateOf(bitmap) }
    if (bitmap != null) retainedBitmap = bitmap

    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var hostSize by remember { mutableStateOf(IntSize.Zero) }
    val statusTopPx = WindowInsets.safeDrawing.getTop(density)
    val topBarRowPx = with(density) { (FloatingChromeSize + FloatingChromeVerticalPadding * 2).roundToPx() }
    val tabBarRowPx = with(density) { 42.dp.roundToPx() }
    val dividerPx = with(density) { 0.5.dp.roundToPx().coerceAtLeast(1) }
    val viewportWidthPx = hostSize.width
    val viewportHeightPx = (hostSize.height - statusTopPx - topBarRowPx - tabBarRowPx - dividerPx).coerceAtLeast(1)

    LaunchedEffect(viewportWidthPx, viewportHeightPx, browserOpen, browser) {
        if (!browserOpen && browser != null && viewportWidthPx > 0 && viewportHeightPx > 64) {
            browser.updateContentViewportGeometry(
                widthPx = viewportWidthPx,
                contentHeightPx = viewportHeightPx,
            )
        }
    }

    val cardWidthDp = 136.dp
    val edgeMarginPx = with(density) { 14.dp.toPx() }
    val cardWidthPx = with(density) { cardWidthDp.toPx() }
    val displayBitmap = bitmap ?: retainedBitmap
    val aspect = if (viewportWidthPx > 0 && viewportHeightPx > 64) {
        viewportWidthPx.toFloat() / viewportHeightPx.toFloat()
    } else {
        displayBitmap?.let { it.width.toFloat() / it.height.toFloat().coerceAtLeast(1f) } ?: 0.52f
    }
    val cardHeightPx = cardWidthPx / aspect

    val topLimitPx = with(density) { (padding.calculateTopPadding() + 8.dp).toPx() }
    val bottomReservedPx = with(density) {
        (padding.calculateBottomPadding() + FloatingChromeSize + FloatingOverlayBottomGap * 2 + 12.dp).toPx()
    }

    var initialized by remember(sessionId) { mutableStateOf(false) }
    var dockedLeft by rememberSaveable(sessionId) { mutableStateOf(false) }
    var dragging by remember(sessionId) { mutableStateOf(false) }
    val offsetX = remember(sessionId) { Animatable(0f) }
    val offsetY = remember(sessionId) { Animatable(0f) }

    LaunchedEffect(hostSize, cardHeightPx, topLimitPx, bottomReservedPx) {
        if (hostSize.width > 0 && hostSize.height > 0) {
            val rightX = (hostSize.width - cardWidthPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
            val minY = topLimitPx
            val maxY = max(minY, hostSize.height - cardHeightPx - bottomReservedPx)
            if (!initialized) {
                dockedLeft = false
                offsetX.snapTo(rightX)
                offsetY.snapTo(maxY)
                initialized = true
            } else if (visible && !dragging) {
                val snappedX = if (dockedLeft) edgeMarginPx else rightX
                offsetX.snapTo(snappedX)
                offsetY.snapTo(offsetY.value.coerceIn(minY, maxY))
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { hostSize = it },
    ) {
        AnimatedVisibility(
            visible = visible && initialized,
            enter = slideInHorizontally(spatialOffsetSpring()) { fullWidth ->
                (hostSize.width - offsetX.value + edgeMarginPx).roundToInt().coerceAtLeast(fullWidth)
            },
            exit = slideOutHorizontally(spatialOffsetSpring()) { fullWidth ->
                val currentX = offsetX.value
                val flyLeft = currentX + fullWidth / 2f < hostSize.width / 2f
                if (flyLeft) {
                    (-fullWidth - edgeMarginPx - currentX).roundToInt().coerceAtMost(-1)
                } else {
                    (hostSize.width + edgeMarginPx - currentX).roundToInt().coerceAtLeast(1)
                }
            },
            modifier = Modifier.offset {
                IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt())
            },
        ) {
            DisposableEffect(Unit) {
                onDispose {
                    dockedLeft = false
                    if (hostSize.width > 0) {
                        val rightX = (hostSize.width - cardWidthPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
                        scope.launch { offsetX.snapTo(rightX) }
                    }
                }
            }

            val currentBitmap = displayBitmap
            if (currentBitmap != null) {
                val shape = LumaCardShape(16.dp)
                val borderColor = if (colors.surface.luminance() > 0.5f) {
                    Color.Black.copy(alpha = 0.09f)
                } else {
                    Color.White.copy(alpha = 0.16f)
                }
                Surface(
                    shape = shape,
                    color = Color.White,
                    shadowElevation = 10.dp,
                    border = BorderStroke(0.75.dp, borderColor),
                    modifier = Modifier
                        .width(cardWidthDp)
                        .aspectRatio(aspect)
                        .clip(shape)
                        .clickable(onClick = onOpenBrowser)
                        .pointerInput(hostSize, topLimitPx, bottomReservedPx) {
                            val tracker = VelocityTracker()
                            detectDragGestures(
                                onDragStart = {
                                    dragging = true
                                    tracker.resetTracking()
                                },
                                onDragCancel = {
                                    dragging = false
                                    scope.launch {
                                        val rightX = (hostSize.width - cardWidthPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
                                        dockedLeft = offsetX.value + cardWidthPx / 2f < hostSize.width / 2f
                                        val targetX = if (dockedLeft) edgeMarginPx else rightX
                                        offsetX.animateTo(targetX, spatialPxSpring())
                                    }
                                },
                                onDragEnd = {
                                    dragging = false
                                    val vx = tracker.calculateVelocity().x
                                    val isLeftHalf = offsetX.value + cardWidthPx / 2f < hostSize.width / 2f
                                    val pastLeft = isLeftHalf && (
                                        offsetX.value < -cardWidthPx * 0.22f ||
                                            (offsetX.value < edgeMarginPx * 1.5f && vx < -650f)
                                        )
                                    val pastRight = !isLeftHalf && (
                                        offsetX.value + cardWidthPx > hostSize.width + cardWidthPx * 0.22f ||
                                            (offsetX.value + cardWidthPx > hostSize.width - edgeMarginPx * 1.5f && vx > 650f)
                                        )
                                    if (pastLeft || pastRight) {
                                        dismissed = true
                                    } else {
                                        val rightX = (hostSize.width - cardWidthPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
                                        dockedLeft = isLeftHalf
                                        val targetX = if (dockedLeft) edgeMarginPx else rightX
                                        val minY = topLimitPx
                                        val maxY = max(minY, hostSize.height - cardHeightPx - bottomReservedPx)
                                        val targetY = offsetY.value.coerceIn(minY, maxY)
                                        scope.launch { offsetX.animateTo(targetX, spatialPxSpring()) }
                                        scope.launch { offsetY.animateTo(targetY, spatialPxSpring()) }
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    val minX = -cardWidthPx
                                    val maxX = hostSize.width.toFloat()
                                    val minY = topLimitPx
                                    val maxY = max(minY, hostSize.height - cardHeightPx - with(density) { 24.dp.toPx() })
                                    scope.launch {
                                        offsetX.snapTo((offsetX.value + dragAmount.x).coerceIn(minX, maxX))
                                        offsetY.snapTo((offsetY.value + dragAmount.y).coerceIn(minY, maxY))
                                    }
                                },
                            )
                        }
                        .semantics {
                            contentDescription = tr("网页预览悬浮窗", "Floating browser preview")
                            role = Role.Button
                        }
                        .testTag("browser-floating-preview"),
                ) {
                    Image(
                        bitmap = currentBitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("browser-floating-preview-image"),
                    )
                }
            }
        }
    }
}
