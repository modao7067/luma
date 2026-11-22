package io.github.modao7067.luma.gui.ui

import androidx.compose.ui.platform.LocalHapticFeedback

import io.github.modao7067.luma.gui.i18n.tr

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.modao7067.luma.gui.session.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class MediaContext(val sessionId: String? = null, val cwd: String? = null)
internal val LocalMediaContext = compositionLocalOf { MediaContext() }
internal val LocalMediaActions = compositionLocalOf<CockpitActions?> { null }
internal val LocalOpenMedia = compositionLocalOf<((MediaResource, Offset?) -> Unit)?> { null }
internal val LocalMediaFixtureFrame = compositionLocalOf<MediaFrame?> { null }
internal val LocalMediaFrameLoader = compositionLocalOf<(suspend (MediaResource, Int, Int) -> MediaFrame)?> { null }
internal val LocalMediaOwnerKey = compositionLocalOf { "" }
private data class FrameState(val frame: MediaFrame? = null, val error: String? = null)
private data class MediaRoute(val resource: MediaResource, val origin: Offset?)

internal fun MediaResource.inContext(context: MediaContext) = copy(
    sessionId = sessionId ?: context.sessionId, cwd = cwd ?: context.cwd,
)
internal fun mediaTitle(resource: MediaResource): String = resource.name.ifBlank {
    if (resource.source.startsWith("data:")) tr("图片", "Image") else Uri.decode(resource.source.substringBefore('?').substringAfterLast('/')).ifBlank { tr("附件", "Attachment") }
}
internal fun mediaIsImage(resource: MediaResource) = resource.mimeType.startsWith("image/") ||
    resource.source.startsWith("data:image/") || resource.source.substringBefore('?').substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "avif", "heic", "heif", "svg")
internal fun mediaIsPdf(resource: MediaResource) = resource.mimeType == "application/pdf" || resource.source.substringBefore('?').endsWith(".pdf", true)
internal fun mediaIsDirectory(resource: MediaResource) = resource.mimeType == "inode/directory" || resource.mimeType == "application/x-directory"

@Composable
internal fun MediaViewerHost(
    ownerKey: String, actions: CockpitActions, context: MediaContext,
    previewResource: MediaResource? = null, previewFraction: Float? = null,
    content: @Composable () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var route by remember(ownerKey) { mutableStateOf(previewResource?.let { MediaRoute(it.inContext(context), Offset(280f, 320f)) }) }
    var retained by remember(ownerKey) { mutableStateOf<MediaRoute?>(null) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var backProgress by remember(ownerKey) { mutableFloatStateOf(0f) }
    val animatedFraction by animateFloatAsState(if (route != null) 1f else 0f, spatialSpring(), label = "media-detail-motion")
    val fraction = ((previewFraction ?: animatedFraction) * (1f - backProgress * 0.35f)).coerceIn(0f, 1f)
    val open: (MediaResource, Offset?) -> Unit = { resource, origin ->
        keyboard?.hide()
        MediaRoute(resource.inContext(context), origin).let { route = it; retained = it }
    }
    CompositionLocalProvider(
        LocalMediaActions provides actions,
        LocalMediaContext provides context,
        LocalOpenMedia provides open,
        LocalMediaOwnerKey provides ownerKey,
    ) {
        Box(Modifier.fillMaxSize().onSizeChanged { rootSize = it }.testTag("media-host:$ownerKey").semantics { stateDescription = if (route != null) tr("正在查看媒体", "Viewing media") else tr("来源页面", "Source page") }) {
            val contentInteractive = LocalSessionInteractive.current && route == null
            Box(Modifier.fillMaxSize().then(if (route == null && (previewFraction ?: animatedFraction) == 0f) Modifier else Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
            })) {
                CompositionLocalProvider(LocalSessionInteractive provides contentInteractive) {
                    content()
                }
            }
            val displayed = route ?: retained
            if (spatialContentVisible(route != null, previewFraction ?: animatedFraction) && displayed != null) {
                val interactive = route != null
                val origin = displayed.origin?.let {
                    TransformOrigin((it.x / max(1, rootSize.width)).coerceIn(0f, 1f), (it.y / max(1, rootSize.height)).coerceIn(0f, 1f))
                } ?: TransformOrigin.Center
                Box(Modifier.fillMaxSize().testTag("media-overlay").graphicsLayer {
                    val f = fraction.coerceIn(0f, 1f)
                    scaleX = .86f + .14f * f; scaleY = scaleX; alpha = f; transformOrigin = origin
                }.clip(LumaCardShape(((1f - fraction.coerceIn(0f, 1f)) * 28f).dp))
                    .then(if (interactive) Modifier else Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                        awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                    })) {
                    key(displayed.resource) { MediaViewerScreen(displayed.resource, onBack = { route = null }) }
                }
            }
        }
        if (route != null) {
            PredictiveBackHandler(enabled = true) { progress ->
                try {
                    progress.collect { event ->
                        backProgress = event.progress.coerceIn(0f, 1f)
                    }
                    backProgress = 0f
                    route = null
                } catch (cancelled: CancellationException) {
                    backProgress = 0f
                    throw cancelled
                }
            }
        }
    }
}

@Composable
private fun rememberMediaFrame(resource: MediaResource, maxDimension: Int, page: Int = 0, retry: Int = 0): FrameState {
    LocalMediaFixtureFrame.current?.let { return FrameState(frame = it) }
    val context = LocalContext.current
    val actions = LocalMediaActions.current
    val override = LocalMediaFrameLoader.current
    val ownerKey = LocalMediaOwnerKey.current
    val bound = resource.inContext(LocalMediaContext.current)
    val cacheOwner = remember(ownerKey, actions) { "${actions?.environment.orEmpty()}:$ownerKey" }
    val warmCached = remember(bound, maxDimension, page, retry, override, cacheOwner) {
        if (retry == 0 && override == null) {
            MediaFrameCache.findExactFrame(
                owner = cacheOwner,
                sessionId = bound.sessionId.orEmpty(),
                cwd = bound.cwd.orEmpty(),
                source = bound.source,
                maxDimension = maxDimension,
                page = page,
            )
        } else null
    }
    return produceState(
        initialValue = warmCached?.let { FrameState(frame = it) } ?: FrameState(),
        bound, maxDimension, page, retry, actions, override, cacheOwner,
    ) {
        if (warmCached == null) value = FrameState()
        value = try {
            val frame = if (override != null) override(bound, maxDimension, page) else {
                val resolved = requireNotNull(actions) { tr("无法读取", "Unable to read") }.let { act ->
                    if (retry > 0) act.resolveMedia(bound, forceRefresh = true) else act.resolveMedia(bound)
                }
                val key = MediaFrameKey(
                    owner = cacheOwner,
                    sessionId = bound.sessionId.orEmpty(),
                    cwd = bound.cwd.orEmpty(),
                    source = bound.source,
                    resolvedFileStamp = resolvedFileStamp(resolved),
                    maxDimension = maxDimension,
                    page = page,
                )
                MediaFrameCache.get(key) ?: withContext(Dispatchers.IO) {
                    decodeMediaFrame(context, resolved, maxDimension, page).also { decoded ->
                        MediaFrameCache.put(key, decoded)
                    }
                }
            }
            FrameState(frame = frame)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { FrameState(error = error.message?.take(120)?.ifBlank { null } ?: tr("无法预览", "Preview unavailable")) }
    }.value
}

@Composable
internal fun ImageThumbnail(source: String, name: String, busy: Boolean = false) {
    MediaImageCard(MediaResource(source, name, "image/*"), Modifier.size(84.dp), busy, crop = true)
}

internal data class MediaCardLayout(
    val widthDp: Float,
    val heightDp: Float,
    val cornerDp: Float,
    val crop: Boolean,
)

internal fun mediaImageCardLayout(
    intrinsicWidth: Int,
    intrinsicHeight: Int,
    maxWidthDp: Float = 280f,
    maxHeightDp: Float = 360f,
): MediaCardLayout {
    val w = intrinsicWidth.coerceAtLeast(1).toFloat()
    val h = intrinsicHeight.coerceAtLeast(1).toFloat()
    val aspect = w / h
    val downScale = minOf(1f, maxWidthDp / w, maxHeightDp / h)
    val fittedW = w * downScale
    val fittedH = h * downScale
    if (aspect < 0.4f && h > maxHeightDp) {
        val croppedW = minOf(w, maxWidthDp, maxHeightDp * 0.4f)
        val croppedH = minOf(h, maxHeightDp)
        return MediaCardLayout(
            widthDp = croppedW,
            heightDp = croppedH,
            cornerDp = minOf(12f, minOf(croppedW, croppedH) * 0.25f).coerceAtLeast(4f),
            crop = true,
        )
    }
    val finalW = fittedW.coerceAtLeast(minOf(w, 20f))
    val finalH = fittedH.coerceAtLeast(minOf(h, 20f))
    val corner = minOf(12f, minOf(finalW, finalH) * 0.25f).coerceAtLeast(4f)
    return MediaCardLayout(widthDp = finalW, heightDp = finalH, cornerDp = corner, crop = false)
}

@Composable
internal fun MediaImageCard(
    resource: MediaResource,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    crop: Boolean = false,
    collapseOnFailure: Boolean = false,
    onLoadFailed: (() -> Unit)? = null,
) {
    val bound = resource.inContext(LocalMediaContext.current)
    val state = rememberMediaFrame(bound, if (crop) 256 else 768)
    if (collapseOnFailure && state.error != null) {
        LaunchedEffect(bound, state.error) { onLoadFailed?.invoke() }
        return
    }
    val open = LocalOpenMedia.current
    val haptic = LocalHapticFeedback.current
    val interactive = LocalSessionInteractive.current
    var origin by remember { mutableStateOf<Offset?>(null) }
    val cardLayout = state.frame?.let { frame ->
        mediaImageCardLayout(frame.intrinsicWidth, frame.intrinsicHeight)
    }
    val corner = (if (crop) 12f else cardLayout?.cornerDp ?: 12f).dp
    val sizeModifier = when {
        crop -> Modifier
        cardLayout != null -> Modifier
            .widthIn(max = cardLayout.widthDp.dp)
            .heightIn(max = cardLayout.heightDp.dp)
            .aspectRatio(cardLayout.widthDp / cardLayout.heightDp)
        else -> Modifier.size(width = 120.dp, height = 84.dp)
    }
    Surface(shape = LumaCardShape(corner), color = contentBlockColors(MaterialTheme.colorScheme).surface,
        modifier = modifier.then(sizeModifier)
            .onGloballyPositioned { origin = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f) }
            .clip(LumaCardShape(corner))
            .blockHorizontalPageSwipe(enabled = open != null && interactive && !busy, allowVerticalScroll = true)
            .clickable(enabled = open != null && interactive && !busy) {
                haptic.tick()
                open?.invoke(bound, origin)
            }
            .semantics { contentDescription = tr("查看图片：${mediaTitle(bound)}", "View image: ${mediaTitle(bound)}") }.testTag(mediaCardTag(resource))) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            state.frame?.let { frame ->
                val imageBitmap = remember(frame.bitmap) { frame.bitmap.asImageBitmap() }
                Image(imageBitmap, mediaTitle(bound), Modifier.fillMaxSize(), contentScale = if (crop || cardLayout?.crop == true) ContentScale.Crop else ContentScale.Fit)
            }
                ?: if (state.error != null) Text(tr("无法预览", "Preview unavailable"), style = MaterialTheme.typography.labelMedium)
                else CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            if (busy && state.frame != null) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

internal fun isStaticUnpreviewableMedia(resource: MediaResource): Boolean {
    val src = resource.source.trim()
    return src.isEmpty() || src.startsWith("codex-file:") || src.startsWith("luma-error:")
}

private fun mediaFailureKey(resource: MediaResource): String =
    "${resource.sessionId.orEmpty()}|${resource.cwd.orEmpty()}|${resource.source}"

@Composable
internal fun MessageMedia(
    items: List<MediaResource>,
    user: Boolean = false,
    onVisibilityChanged: ((Boolean) -> Unit)? = null,
) {
    val mediaContext = LocalMediaContext.current
    val previewableItems = remember(items) { items.filterNot(::isStaticUnpreviewableMedia) }
    val failedKeys = remember(mediaContext, previewableItems) { mutableStateMapOf<String, Boolean>() }
    val visibleItems = previewableItems.filterNot { failedKeys[mediaFailureKey(it.inContext(mediaContext))] == true }
    SideEffect { onVisibilityChanged?.invoke(visibleItems.isNotEmpty()) }
    if (visibleItems.isEmpty()) return
    if (user) {
        val images = visibleItems.filter(::mediaIsImage)
        val files = visibleItems.filterNot(::mediaIsImage)
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (images.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    images.chunked(3).forEach { row ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            repeat(3 - row.size) {
                                Spacer(Modifier.weight(1f))
                            }
                            row.forEach { resource ->
                                val failureKey = mediaFailureKey(resource.inContext(mediaContext))
                                key(resource.source, resource.sessionId, resource.cwd) {
                                    Box(Modifier.weight(1f), contentAlignment = Alignment.TopEnd) {
                                        MediaImageCard(
                                            resource = resource,
                                            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                                            crop = true,
                                            collapseOnFailure = true,
                                            onLoadFailed = { failedKeys[failureKey] = true },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            files.forEach { resource ->
                val failureKey = mediaFailureKey(resource.inContext(mediaContext))
                key(resource.source, resource.sessionId, resource.cwd) {
                    MediaFileCard(
                        resource,
                        collapseOnFailure = true,
                        onLoadFailed = { failedKeys[failureKey] = true },
                    )
                }
            }
        }
        return
    }
    if (visibleItems.all(::mediaIsImage) && visibleItems.size > 1) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            visibleItems.forEach { resource ->
                val failureKey = mediaFailureKey(resource.inContext(mediaContext))
                key(resource.source, resource.sessionId, resource.cwd) {
                    MediaImageCard(
                        resource,
                        collapseOnFailure = true,
                        onLoadFailed = { failedKeys[failureKey] = true },
                    )
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.Start) {
        visibleItems.forEach { resource ->
            val failureKey = mediaFailureKey(resource.inContext(mediaContext))
            key(resource.source, resource.sessionId, resource.cwd) {
                if (mediaIsImage(resource)) MediaImageCard(
                    resource,
                    collapseOnFailure = true,
                    onLoadFailed = { failedKeys[failureKey] = true },
                )
                else MediaFileCard(
                    resource,
                    collapseOnFailure = true,
                    onLoadFailed = { failedKeys[failureKey] = true },
                )
            }
        }
    }
}

internal fun messageMedia(item: ConversationItem): List<MediaResource> =
    (item.media + item.images.filterNot { source -> item.media.any { it.source == source } }.map { MediaResource(it, mimeType = "image/*") })
        .distinctBy { it.source }
        .filterNot(::isStaticUnpreviewableMedia)

@Composable
internal fun MediaFileCard(
    resource: MediaResource,
    clickable: Boolean = true,
    collapseOnFailure: Boolean = false,
    onLoadFailed: (() -> Unit)? = null,
) {
    val bound = resource.inContext(LocalMediaContext.current)
    val open = LocalOpenMedia.current
    val interactive = LocalSessionInteractive.current && clickable
    val actions = LocalMediaActions.current
    val metadata by produceState(bound, bound, actions) {
        if ((bound.sizeBytes == null || bound.mimeType.isBlank() || bound.mimeType == "application/octet-stream") && actions != null) {
            value = try { actions.inspectMedia(bound) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { bound }
        }
    }
    if (mediaIsImage(metadata)) { MediaImageCard(metadata, collapseOnFailure = collapseOnFailure, onLoadFailed = onLoadFailed); return }
    var origin by remember { mutableStateOf<Offset?>(null) }
    val haptic = LocalHapticFeedback.current
    val isDir = mediaIsDirectory(metadata)
    Surface(shape = LumaCardShape(12.dp), color = contentBlockColors(MaterialTheme.colorScheme).surface,
        modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().onGloballyPositioned { origin = it.positionInRoot() }
            .clip(LumaCardShape(12.dp))
            .blockHorizontalPageSwipe(enabled = (isDir || open != null) && interactive, allowVerticalScroll = true)
            .clickable(enabled = (isDir || open != null) && interactive) {
                haptic.tick()
                if (isDir) {
                    val targetPath = try { io.github.modao7067.luma.gui.runtime.MediaSourcePolicy.guestPath(metadata.source, metadata.cwd ?: "/workspace") } catch (_: Exception) { metadata.source }
                    actions?.browseFiles(targetPath)
                } else if (mediaIsPdf(metadata)) open?.invoke(metadata, origin)
                else actions?.previewResource(metadata)
            }
            .testTag(mediaCardTag(resource))) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            MenuIcon(mediaFileSymbol(metadata), size = 24.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(mediaTitle(metadata), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                val kind = if (isDir) tr("文件夹", "Folder") else metadata.sizeBytes?.let(::formatMediaSize) ?: mediaFormatLabel(metadata)
                Text(kind, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun MediaViewerScreen(resource: MediaResource, previewLoading: Boolean = false, previewError: String? = null, previewZoom: Float = 1f, onBack: () -> Unit) {
    val saveFeedback = remember { SnackbarHostState() }
    val saveScope = rememberCoroutineScope()
    val saveActions = LocalMediaActions.current
    val latestActions by rememberUpdatedState(saveActions)
    val openState = remember(resource, saveScope) { ExternalMediaOpenState(resource, { latestActions }, saveScope) }
    DisposableEffect(openState) { onDispose { openState.dispose() } }
    var retry by remember(resource) { mutableIntStateOf(0) }
    var page by remember(resource) { mutableIntStateOf(0) }
    val bound = resource.inContext(LocalMediaContext.current)
    val ownerKey = LocalMediaOwnerKey.current
    val cacheOwner = remember(ownerKey, saveActions) { "${saveActions?.environment.orEmpty()}:$ownerKey" }
    val raster = mediaIsImage(bound) || mediaIsPdf(bound)
    val state = when {
        previewError != null -> FrameState(error = previewError)
        previewLoading -> FrameState()
        raster -> rememberMediaFrame(bound, 2048, page, retry)
        else -> FrameState()
    }
    val hasFixtureFrame = LocalMediaFixtureFrame.current != null
    val placeholderFrame = remember(bound, page, retry, state.frame, state.error, previewLoading, previewError, cacheOwner, raster, hasFixtureFrame) {
        if (retry == 0 && !previewLoading && previewError == null && state.frame == null && state.error == null && raster && !hasFixtureFrame) {
            MediaFrameCache.findPreviewFrame(
                owner = cacheOwner,
                sessionId = bound.sessionId.orEmpty(),
                cwd = bound.cwd.orEmpty(),
                source = bound.source,
                page = page,
            )
        } else null
    }
    val activeFrame = state.frame ?: placeholderFrame
    var pageCount by remember(resource) { mutableIntStateOf(1) }
    SideEffect { state.frame?.let { pageCount = it.pageCount } }
    Surface(Modifier.fillMaxSize().testTag("media-viewer"), color = MaterialTheme.colorScheme.background) {
        LiquidScaffold(wrapTop = false, wrapBottom = false, edgeToEdgeContent = true,
            topBar = {
                DetailPageHeader(mediaTitle(bound), onBack, backLabel = tr("返回来源", "Back")) {
                    MediaSaveButton(bound, saveActions) { message -> saveScope.launch { saveFeedback.showSnackbar(message) } }
                }
            },
            bottomBar = {
                val count = pageCount
                if (count > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { page-- }, enabled = page > 0) { Text(tr("上一页", "Previous")) }
                    Text("${page + 1} / $count", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { page++ }, enabled = page + 1 < count) { Text(tr("下一页", "Next")) }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (activeFrame == null && state.error == null && !raster && mediaIsApk(bound)) {
                    Row(
                        Modifier.fillMaxWidth().align(Alignment.TopStart).padding(padding).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(enabled = !openState.opening && saveActions != null, onClick = { openState.start(chooseApp = true) }) {
                            MenuIcon(MenuSymbol.OPEN_EXTERNAL, size = 18.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(tr("用其他应用打开", "Open in another app"))
                        }
                    }
                }
                when {
                    activeFrame != null -> key(bound, page) { ZoomableMedia(activeFrame.bitmap, mediaTitle(bound), initialScale = previewZoom, fitPadding = padding) }
                    state.error != null -> Column(Modifier.padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { retry++ }) { Text(tr("重试", "Retry")) }
                        ExternalMediaOpenButton(openState, saveActions != null, tr("用其他应用打开", "Open in another app"))
                    }
                    raster -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(32.dp)) }
                    else -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { ExternalMediaContent(bound, openState, saveActions != null) }
                }
                SnackbarHost(saveFeedback, Modifier.align(Alignment.BottomCenter).padding(padding).padding(16.dp))
            }
        }
    }
}

internal fun fittedMediaSize(image: IntSize, viewport: IntSize): Size {
    if (image.width <= 0 || image.height <= 0 || viewport.width <= 0 || viewport.height <= 0) return Size.Zero
    val factor = min(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
    return Size(image.width * factor, image.height * factor)
}
internal fun clampMediaOffset(offset: Offset, scale: Float, fitted: Size, viewport: IntSize, centerOffset: Offset = Offset.Zero): Offset {
    val x = max(0f, (fitted.width * scale - viewport.width) / 2f)
    val y = max(0f, (fitted.height * scale - viewport.height) / 2f)
    return Offset(if (x == 0f) 0f else offset.x.coerceIn(-x - centerOffset.x, x - centerOffset.x), if (y == 0f) 0f else offset.y.coerceIn(-y - centerOffset.y, y - centerOffset.y))
}

@Composable
internal fun ZoomableMedia(bitmap: Bitmap, name: String, initialScale: Float = 1f, fitPadding: PaddingValues = PaddingValues()) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(initialScale) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var semanticsScale by remember { mutableIntStateOf((initialScale * 100).roundToInt()) }
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val density = LocalDensity.current
    val left = with(density) { fitPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr).toPx() }
    val right = with(density) { fitPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr).toPx() }
    val top = with(density) { fitPadding.calculateTopPadding().toPx() }
    val bottom = with(density) { fitPadding.calculateBottomPadding().toPx() }
    val safeViewport = IntSize((viewport.width - left - right).toInt().coerceAtLeast(0), (viewport.height - top - bottom).toInt().coerceAtLeast(0))
    val fitted = fittedMediaSize(IntSize(bitmap.width, bitmap.height), safeViewport)
    val centerOffset = Offset((left - right) / 2f, (top - bottom) / 2f)
    val imageCenter = Offset(viewport.width / 2f, viewport.height / 2f) + centerOffset
    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged {
        if (viewport != it) {
            viewport = it
            offset = Offset.Zero
            scale = initialScale
            semanticsScale = (initialScale * 100).roundToInt()
        }
    }
        .testTag("media-zoom").semantics { contentDescription = tr("$name，缩放 $semanticsScale%", "$name, zoom $semanticsScale%") }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    if (event.changes.none { it.pressed }) {
                        val pct = (scale * 100).roundToInt()
                        if (semanticsScale != pct) semanticsScale = pct
                    }
                }
            }
        }
        .pointerInput(bitmap, viewport, fitted, centerOffset) {
            detectTapGestures(onDoubleTap = { position ->
                val next = if (scale > 1.01f) 1f else max(2.5f, viewport.width / fitted.width.coerceAtLeast(1f)).coerceAtMost(32f)
                offset = if (next == 1f) Offset.Zero else clampMediaOffset(
                    (position - imageCenter) * (1f - next), next, fitted, viewport, centerOffset)
                scale = next
                semanticsScale = (next * 100).roundToInt()
            })
        }.pointerInput(bitmap, viewport, fitted, centerOffset) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                val maximum = max(6f, viewport.width / fitted.width.coerceAtLeast(1f) * 2f).coerceAtMost(32f)
                val next = (scale * zoom).coerceIn(1f, maximum)
                val center = imageCenter
                val transformed = (offset - (centroid - center)) * (next / scale) + (centroid - center) + pan
                offset = clampMediaOffset(transformed, next, fitted, viewport, centerOffset); scale = next
            }
        }, contentAlignment = Alignment.Center) {
        Image(imageBitmap, name, Modifier.requiredSize(with(density) { fitted.width.toDp() }, with(density) { fitted.height.toDp() }).graphicsLayer {
            scaleX = scale; scaleY = scale; translationX = centerOffset.x + offset.x; translationY = centerOffset.y + offset.y
        }.testTag("media-image"), contentScale = ContentScale.Fit)
    }
}

internal fun formatMediaSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024 * 1024 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024))
    bytes >= 1024 -> "%.1f KB".format(java.util.Locale.ROOT, bytes / 1024.0)
    else -> "$bytes B"
}

private class ExternalMediaOpenState(
    private val resource: MediaResource,
    private val actionsProvider: () -> CockpitActions?,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    var opening by mutableStateOf(false)
    var cancelling by mutableStateOf(false)
    var operation by mutableStateOf<Job?>(null)
    var error by mutableStateOf<String?>(null)
    fun start(chooseApp: Boolean = false) {
        val actions = actionsProvider() ?: return
        if (opening) return
        opening = true; cancelling = false; error = null
        operation = scope.launch {
            try {
                if (chooseApp) actions.openMediaWithChooser(resource)
                else actions.openMediaExternally(resource)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: tr("无法打开", "Unable to open") }
            finally { opening = false; operation = null }
        }
    }
    fun cancel() {
        if (cancelling) return
        cancelling = true
        operation?.cancel()
    }
    fun dispose() { operation?.cancel() }
}

@Composable
private fun ExternalMediaContent(resource: MediaResource, openState: ExternalMediaOpenState, hasActions: Boolean) {
    val actions = LocalMediaActions.current
    val isDir = mediaIsDirectory(resource)
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MediaFileCard(resource, clickable = false)
        if (isDir && actions != null) {
            Button(onClick = {
                val targetPath = try { io.github.modao7067.luma.gui.runtime.MediaSourcePolicy.guestPath(resource.source, resource.cwd ?: "/workspace") } catch (_: Exception) { resource.source }
                actions.closePreview()
                actions.browseFiles(targetPath)
            }) { Text(tr("浏览文件夹", "Browse folder")) }
        } else {
            ExternalMediaOpenButton(openState, hasActions)
        }
    }
}

@Composable
private fun ExternalMediaOpenButton(openState: ExternalMediaOpenState, hasActions: Boolean, label: String = tr("打开", "Open")) {
    openState.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(enabled = !openState.opening && hasActions, onClick = { openState.start(chooseApp = false) }) {
            Text(if (openState.opening) tr("正在打开…", "Opening…") else label)
        }
        if (openState.opening) TextButton(enabled = !openState.cancelling, onClick = openState::cancel) {
            Text(if (openState.cancelling) tr("正在取消…", "Cancelling…") else tr("取消", "Cancel"))
        }
    }
}

internal fun mediaIsApk(resource: MediaResource): Boolean = mediaFileSymbol(resource) == MenuSymbol.APK

internal fun mediaFileSymbol(resource: MediaResource): MenuSymbol = when {
    mediaIsDirectory(resource) -> MenuSymbol.FOLDER
    mediaIsPdf(resource) -> MenuSymbol.PDF
    resource.mimeType.startsWith("audio/") -> MenuSymbol.AUDIO
    resource.mimeType.startsWith("video/") && fileNameSymbol(resource.source) == null -> MenuSymbol.VIDEO
    resource.source.substringBefore('?').endsWith(".apk", true) || resource.name.endsWith(".apk", true) || resource.mimeType == "application/vnd.android.package-archive" -> MenuSymbol.APK
    else -> fileNameSymbol(resource.name.ifBlank { resource.source })
        ?: fileNameSymbol(resource.source)
        ?: if (resource.mimeType.startsWith("image/")) MenuSymbol.IMAGE
        else if (io.github.modao7067.luma.gui.runtime.MediaSourcePolicy.isText(resource.mimeType)) MenuSymbol.TEXT
        else MenuSymbol.FILE
}

private val codeExtensions = setOf(
    "kt", "kts", "java", "groovy", "gradle", "scala", "sc", "clj", "cljs", "py", "pyi", "pyw", "rb", "pl", "pm", "php", "lua", "r", "jl",
    "dart", "swift", "m", "mm", "js", "mjs", "cjs", "jsx", "ts", "mts", "cts", "tsx", "vue", "svelte", "astro", "coffee", "c", "h", "cc",
    "cpp", "cxx", "hpp", "hh", "hxx", "cs", "fs", "fsx", "go", "rs", "zig", "nim", "hs", "ml", "mli", "ex", "exs", "erl", "elm", "sql",
    "graphql", "gql", "proto", "thrift", "css", "scss", "sass", "less", "cmake", "bzl", "aidl",
)
private val scriptExtensions = setOf("sh", "bash", "zsh", "fish", "ksh", "ps1", "psm1", "bat", "cmd", "awk", "mk", "make", "ninja")
private val scriptNames = setOf("makefile", "gnumakefile", "dockerfile", "containerfile", "jenkinsfile", "justfile", "procfile", "gradlew", "gradlew.bat")
private val dataExtensions = setOf("json", "jsonc", "json5", "jsonl", "ndjson", "xml", "xsd", "xsl", "plist", "avsc")
private val configExtensions = setOf(
    "yaml", "yml", "toml", "ini", "cfg", "conf", "config", "properties", "prop", "env", "lock", "pro", "service", "desktop", "rules",
    "gitignore", "gitattributes", "gitmodules", "editorconfig", "dockerignore", "npmrc", "nvmrc", "bashrc", "zshrc", "profile", "vimrc",
)
private val tableExtensions = setOf("csv", "tsv", "xls", "xlsx", "ods", "numbers")
private val documentExtensions = setOf("md", "markdown", "mdx", "rst", "adoc", "asciidoc", "org", "tex", "doc", "docx", "odt", "rtf", "pages")
private val plainTextExtensions = setOf("txt", "text", "log", "out", "srt", "vtt")
private val plainTextNames = setOf("license", "licence", "copying", "notice", "authors", "contributors", "readme", "changelog", "todo", "codeowners")
private val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "avif", "heic", "heif", "svg", "ico")
private val archiveExtensions = setOf("zip", "gz", "tgz", "tar", "xz", "7z", "bz2", "zst", "rar", "jar", "aar")

internal fun fileNameSymbol(nameOrSource: String): MenuSymbol? {
    val file = nameOrSource.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/').lowercase()
    if (file.isEmpty()) return null
    val ext = if (file.contains('.')) file.substringAfterLast('.') else ""
    return when {
        file in scriptNames || ext in scriptExtensions -> MenuSymbol.TERMINAL
        ext in codeExtensions -> MenuSymbol.CODE
        ext in dataExtensions -> MenuSymbol.DATA
        ext in configExtensions -> MenuSymbol.SETTINGS
        ext in tableExtensions -> MenuSymbol.TABLE
        ext in documentExtensions -> MenuSymbol.ARTICLE
        ext in setOf("html", "htm", "xhtml") -> MenuSymbol.BROWSER
        ext in setOf("patch", "diff") -> MenuSymbol.DIFF
        ext in plainTextExtensions || file in plainTextNames || file == "cmakelists.txt" -> MenuSymbol.TEXT
        ext in imageExtensions -> MenuSymbol.IMAGE
        ext in archiveExtensions -> MenuSymbol.ARCHIVE
        ext in setOf("mp3", "wav", "ogg", "oga", "m4a", "flac", "aac", "opus") -> MenuSymbol.AUDIO
        ext in setOf("mp4", "webm", "mkv", "mov", "avi", "m4v", "3gp") -> MenuSymbol.VIDEO
        ext == "pdf" -> MenuSymbol.PDF
        ext == "apk" -> MenuSymbol.APK
        else -> null
    }
}

private val terminalLanguageTags = setOf(
    "sh", "bash", "zsh", "shell", "fish", "ksh", "console", "shellsession", "terminal",
    "ps1", "powershell", "pwsh", "bat", "cmd", "awk", "sed", "dockerfile", "containerfile",
    "makefile", "mk", "cmake", "ninja", "just",
)
private val dataLanguageTags = setOf(
    "json", "jsonc", "json5", "jsonl", "ndjson", "xml", "xsd", "xsl", "plist",
    "graphql", "gql", "proto", "protobuf", "thrift", "avsc", "sql", "sqlite", "postgresql", "mysql",
)
private val configLanguageTags = setOf(
    "yaml", "yml", "toml", "ini", "cfg", "conf", "config", "properties", "prop",
    "env", "dotenv", "hcl", "terraform", "tf", "nginx", "systemd", "gitignore", "editorconfig",
)
private val tableLanguageTags = setOf("csv", "tsv")
private val documentLanguageTags = setOf(
    "md", "markdown", "gfm", "commonmark", "mdx", "rmd", "quarto", "rst", "adoc", "asciidoc", "org", "tex", "latex",
)
private val diffLanguageTags = setOf("diff", "patch", "git-diff", "udiff")
private val webLanguageTags = setOf("html", "htm", "xhtml", "http")
private val plainTextLanguageTags = setOf("text", "txt", "plain", "plaintext", "log", "logs", "output", "out", "stdout", "stderr", "prompt")

internal fun codeBlockSymbol(language: String, caption: String = ""): MenuSymbol {
    val tag = language.trim().substringBefore(' ').lowercase()
    return when {
        tag in terminalLanguageTags -> MenuSymbol.TERMINAL
        tag in dataLanguageTags -> MenuSymbol.DATA
        tag in configLanguageTags -> MenuSymbol.SETTINGS
        tag in tableLanguageTags -> MenuSymbol.TABLE
        tag in documentLanguageTags -> MenuSymbol.ARTICLE
        tag in diffLanguageTags -> MenuSymbol.DIFF
        tag in webLanguageTags -> MenuSymbol.BROWSER
        tag in plainTextLanguageTags -> MenuSymbol.TEXT
        tag.isNotEmpty() -> fileNameSymbol(tag) ?: fileNameSymbol("snippet.$tag") ?: MenuSymbol.CODE
        caption.isNotBlank() && caption != "代码" && caption != "Code" -> fileNameSymbol(caption) ?: MenuSymbol.CODE
        else -> MenuSymbol.CODE
    }
}

internal fun mediaFormatLabel(resource: MediaResource): String = when (mediaFileSymbol(resource)) {
    MenuSymbol.FOLDER -> tr("文件夹", "Folder")
    MenuSymbol.PDF -> "PDF"
    MenuSymbol.APK -> "APK"
    MenuSymbol.AUDIO -> tr("音频", "Audio")
    MenuSymbol.VIDEO -> tr("视频", "Video")
    MenuSymbol.ARCHIVE -> tr("压缩文件", "Archive")
    else -> resource.source.substringBefore('?').substringAfterLast('/', "").substringAfterLast('.', tr("文件", "FILE")).uppercase().take(16)
}

internal fun mediaCardTag(resource: MediaResource) = "media-card:" +
    if (resource.source.startsWith("data:")) "inline-image" else resource.source.substringBefore('?').takeLast(160)
