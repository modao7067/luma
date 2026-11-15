package io.github.modao7067.luma.gui.browser

import io.github.modao7067.luma.gui.i18n.LumaI18n
import io.github.modao7067.luma.gui.i18n.tr

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.system.Os
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.webkit.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.modao7067.luma.gui.agent.obj
import io.github.modao7067.luma.gui.agent.objects
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

class AgentBrowser(private val context: Context) {
    enum class JsDialogKind { ALERT, CONFIRM, PROMPT }

    class JsDialogState(
        val kind: JsDialogKind,
        val url: String,
        val message: String,
        val defaultPrompt: String = "",
        private val onConfirm: (String?) -> Unit,
        private val onCancel: () -> Unit,
    ) {
        private var settled = false
        fun confirm(text: String? = null) {
            if (settled) return
            settled = true
            onConfirm(text)
        }
        fun cancel() {
            if (settled) return
            settled = true
            onCancel()
        }
    }

    class FileChooserRequest(
        val acceptTypes: List<String>,
        val multiple: Boolean,
        private val callback: ValueCallback<Array<Uri>>,
    ) {
        private var settled = false
        fun complete(uris: Array<Uri>?) {
            if (settled) return
            settled = true
            callback.onReceiveValue(uris)
        }
    }

    class ConsoleEntry(
        val level: String,
        val message: String,
        val source: String,
        val line: Int,
        val navSeq: Long,
    )

    class ErrorEntry(
        val kind: String,
        val message: String,
        val url: String,
        val status: Int = 0,
        val mainFrame: Boolean = false,
        val navSeq: Long = 0L,
    )

    class Tab(val id: String, val owner: String, val web: WebView, val mobileUserAgent: String) {
        val title = mutableStateOf(tr("新标签页", "New tab"))
        val url = mutableStateOf("")
        val loading = mutableStateOf(false)
        val progress = mutableIntStateOf(0)
        val canGoBack = mutableStateOf(false)
        val canGoForward = mutableStateOf(false)
        val desktopMode = mutableStateOf(false)
        val error = mutableStateOf<String?>(null)
        val notice = mutableStateOf<String?>(null)
        val crashed = mutableStateOf(false)
        val previewBitmap = mutableStateOf<Bitmap?>(null)
        val previewRevision = mutableIntStateOf(0)
        val jsDialog = mutableStateOf<JsDialogState?>(null)
        val fileChooser = mutableStateOf<FileChooserRequest?>(null)
        val lock = Mutex()
        var generation = 0L
        var navSeq = 0L
        var inFlightCount = 0
        val consoleLogs = ArrayDeque<ConsoleEntry>()
        val pageErrors = ArrayDeque<ErrorEntry>()
        var lastSnapshotLines: List<String>? = null
        var lastAutoDialog: JSONObject? = null
        var dialogTimeoutJob: Job? = null
        var closed = false
        var resumed = true
        var lastPreviewAtMs = 0L
        var lastDialogNotice: String? = null
    }

    val tabs = mutableStateListOf<Tab>()
    val foregroundSession = mutableStateOf<String?>(null)
    val allowLiveAgent = mutableStateOf(false)
    val agentOperatingSessions = mutableStateMapOf<String, Boolean>()
    val agentOperationSeq = mutableStateMapOf<String, Int>()
    val agentInFlightCount = mutableStateMapOf<String, Int>()

    fun isAgentOperating(owner: String): Boolean = agentOperatingSessions[owner] == true
    fun agentSeqFor(owner: String): Int = agentOperationSeq[owner] ?: 0
    fun isAgentCommandInFlight(owner: String): Boolean = (agentInFlightCount[owner] ?: 0) > 0
    fun stopAgentOperating(owner: String) {
        agentOperatingSessions[owner] = false
        syncTabLifecycle()
    }

    private fun syncTabLifecycle() {
        val fgOwner = foregroundSession.value
        val fgTabId = if (fgOwner != null) (foregroundTabByOwner[fgOwner] ?: activeTabByOwner[fgOwner]) else null
        tabs.forEach { t ->
            if (t.closed || t.crashed.value) return@forEach
            val activeForFg = fgOwner != null && t.owner == fgOwner && (t.id == fgTabId || fgTabId == null)
            val activeForAgent = (isAgentOperating(t.owner) && activeTabByOwner[t.owner] == t.id) || t.inFlightCount > 0
            val shouldResume = activeForFg || activeForAgent
            if (shouldResume != t.resumed) {
                t.resumed = shouldResume
                runCatching {
                    if (shouldResume) t.web.onResume() else t.web.onPause()
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tokens = ConcurrentHashMap<String, String>()
    private val activeTabByOwner = ConcurrentHashMap<String, String>()
    private val foregroundTabByOwner = ConcurrentHashMap<String, String>()
    private val activeAgentJobs = ConcurrentHashMap<String, Job>()
    private val dir = File(context.filesDir, "${io.github.modao7067.luma.gui.GuiProduct.agentRuntimePath()}/browser").apply {
        mkdirs()
        Os.chmod(absolutePath, 448)
    }
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val clients = java.util.concurrent.Semaphore(8)
    private val pageScript by lazy { context.assets.open("browser/actions.js").bufferedReader().use { it.readText() } }
    private val manifest = android.util.AtomicFile(File(context.filesDir, "browser-tabs.json"))

    @Volatile private var contentViewportWidthPx: Int = context.resources.displayMetrics.widthPixels.coerceAtLeast(360)
    @Volatile private var contentViewportHeightPx: Int = (context.resources.displayMetrics.heightPixels * 0.84f).roundToInt().coerceAtLeast(480)

    init {
        installAssets()
        if (manifest.baseFile.exists()) runCatching {
            JSONObject(String(manifest.readFully())).optJSONArray("tabs")?.objects()?.forEach {
                val url = it.optString("url")
                if (BrowserPolicy.allowedUrl(url)) newTab(it.getString("owner"), url, it.getString("id"))
            }
        }
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val socket = server.accept()
                if (!clients.tryAcquire()) {
                    socket.close()
                    continue
                }
                launch {
                    try {
                        serve(socket)
                    } catch (_: java.io.IOException) {
                    } finally {
                        clients.release()
                    }
                }
            }
        }
    }

    fun updateContentViewportGeometry(widthPx: Int, contentHeightPx: Int) {
        if (widthPx <= 0 || contentHeightPx <= 0) return
        contentViewportWidthPx = widthPx
        contentViewportHeightPx = contentHeightPx
        tabs.forEach { tab ->
            if (!tab.closed && !tab.crashed.value && tab.web.parent == null) {
                layoutOffscreen(tab.web)
            }
        }
    }

    fun selectActiveTab(owner: String, tabId: String) {
        if (tabs.any { it.owner == owner && it.id == tabId && !it.closed }) {
            activeTabByOwner[owner] = tabId
            if (foregroundSession.value == owner) {
                foregroundTabByOwner[owner] = tabId
            }
            syncTabLifecycle()
        }
    }

    fun activeTabFor(owner: String): Tab? {
        val preferred = activeTabByOwner[owner]
        val owned = tabs.filter { it.owner == owner && !it.closed }
        return owned.find { it.id == preferred } ?: owned.lastOrNull()
    }

    fun setForegroundSession(owner: String?) {
        val prev = foregroundSession.value
        foregroundSession.value = owner
        if (owner != null) {
            activeTabFor(owner)?.let { foregroundTabByOwner[owner] = it.id }
            if (!allowLiveAgent.value) {
                activeAgentJobs.remove(owner)?.cancel(CancellationException(tr("用户已进入浏览器接管操作", "User took over browser control")))
                agentOperatingSessions[owner] = false
            }
        }
        if (owner == null && prev != null) {
            foregroundTabByOwner.remove(prev)
            tabs.filter { it.owner == prev && !it.closed }.forEach { tab ->
                tab.dialogTimeoutJob?.cancel()
                tab.dialogTimeoutJob = null
                tab.jsDialog.value?.cancel()
                tab.jsDialog.value = null
                tab.fileChooser.value?.complete(null)
                tab.fileChooser.value = null
            }
        }
        syncTabLifecycle()
    }

    private fun layoutOffscreen(web: WebView) {
        val w = contentViewportWidthPx.coerceAtLeast(320)
        val h = contentViewportHeightPx.coerceAtLeast(320)
        if (web.measuredWidth != w || web.measuredHeight != h || web.width != w || web.height != h) {
            web.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
            )
            web.layout(0, 0, w, h)
        }
    }

    private suspend fun serve(socket: java.net.Socket) {
        socket.use {
            socket.soTimeout = 5000
            val response = try {
                val input = socket.getInputStream()
                val bytes = java.io.ByteArrayOutputStream()
                while (true) {
                    val value = input.read()
                    require(value >= 0) { tr("请求未完成", "Incomplete request") }
                    if (value == 10) break
                    require(bytes.size() < 262144) { tr("请求过大", "Request payload too large") }
                    bytes.write(value)
                }
                val request = JSONObject(bytes.toString("UTF-8"))
                val owner = tokens[request.optString("token")] ?: error(tr("浏览器接口未授权", "Unauthorized browser API token"))
                withTimeout(25000) {
                    withContext(Dispatchers.Main) {
                        val action = request.optString("action")
                        val fgBlocked = if (foregroundSession.value != owner || allowLiveAgent.value || action == "tabs") {
                            false
                        } else if (action == "open" && request.optBoolean("background", false)) {
                            false
                        } else {
                            val explicitTab = request.optString("tab")
                            val fgTab = foregroundTabByOwner[owner] ?: activeTabByOwner[owner]
                            explicitTab.isEmpty() || explicitTab == "\$active" || explicitTab == fgTab
                        }
                        if (fgBlocked) {
                            return@withContext obj(
                                "ok" to false,
                                "code" to "USER_CONTROL_ACTIVE",
                                "error" to tr("用户正在前台查看或操作该标签页；请等待用户返回会话，或使用 open --background / --tab 操作后台标签页", "User is actively viewing this tab in the foreground; wait until they return to the session, or use open --background / --tab"),
                                "dispatched" to false,
                            )
                        }
                        val currentJob = coroutineContext[Job]
                        val tracksForeground = foregroundSession.value != owner
                        if (action != "tabs" && currentJob != null && tracksForeground) activeAgentJobs[owner] = currentJob
                        try {
                            execute(owner, request)
                        } finally {
                            if (currentJob != null && tracksForeground) activeAgentJobs.remove(owner, currentJob)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                obj(
                    "ok" to false,
                    "code" to "USER_CONTROL_ACTIVE",
                    "error" to (cancelled.message ?: tr("操作已被前台用户接管中断", "Action interrupted by foreground user takeover")),
                    "dispatched" to false,
                )
            } catch (e: Exception) {
                obj("ok" to false, "error" to (e.message ?: tr("浏览器操作失败", "Browser action failed")))
            }
            socket.getOutputStream().write((response.toString() + "\n").toByteArray())
        }
    }

    private fun writeBinding(file: File, text: String) {
        val atomic = android.util.AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(text.toByteArray())
            atomic.finishWrite(out)
            Os.chmod(file.absolutePath, 384)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

    fun bind(session: String, threadId: String? = null): String {
        require(session.matches(Regex("[a-zA-Z0-9_-]{1,128}")))
        val file = File(dir, "$session.json")
        val token = if (file.isFile) runCatching { JSONObject(file.readText()).getString("token") }.getOrNull() else null
        val capability = token ?: ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
        tokens[capability] = session
        writeBinding(file, obj("host" to "127.0.0.1", "port" to server.localPort, "token" to capability).toString())
        if (threadId != null) {
            require(threadId.matches(Regex("[a-zA-Z0-9_-]{1,128}")))
            val alias = File(dir, "threads/$threadId.json")
            alias.parentFile!!.mkdirs()
            Os.chmod(alias.parentFile!!.absolutePath, 448)
            writeBinding(alias, file.readText())
        }
        return io.github.modao7067.luma.gui.GuiProduct.runtimePath(context.filesDir, "${io.github.modao7067.luma.gui.GuiProduct.agentRelative()}/browser/$session.json")
    }

    private fun installAssets() {
        for (name in listOf("luma-browser", "SKILL.md")) {
            val target = if (name == "SKILL.md") {
                File(context.filesDir, "${io.github.modao7067.luma.gui.GuiProduct.agentRuntimePath()}/skills/luma-browser/SKILL.md")
            } else {
                File(context.filesDir, "runtime/bin/luma-browser")
            }
            target.parentFile!!.mkdirs()
            val raw = context.assets.open("browser/$name").bufferedReader().use { it.readText() }
            val text = raw
                .replace("/opt/luma/agent", io.github.modao7067.luma.gui.GuiProduct.runtimePath(context.filesDir, io.github.modao7067.luma.gui.GuiProduct.agentRelative()))
                .replace("usable inside NoRoot", "usable inside the current Linux environment")
            target.writeText(text)
            Os.chmod(target.absolutePath, if (name == "SKILL.md") 384 else 448)
        }
    }

    private fun syncNavigationFlags(tab: Tab) {
        if (tab.closed || tab.crashed.value) return
        tab.canGoBack.value = tab.web.canGoBack()
        tab.canGoForward.value = tab.web.canGoForward()
    }

    fun setDesktopMode(tab: Tab, enabled: Boolean) {
        if (tab.closed || tab.crashed.value) return
        tab.desktopMode.value = enabled
        tab.web.settings.apply {
            userAgentString = if (enabled) DESKTOP_USER_AGENT else tab.mobileUserAgent
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        if (tab.url.value.isNotBlank()) {
            tab.generation++
            tab.loading.value = true
            tab.web.reload()
        }
    }

    private fun recordConsole(tab: Tab, entry: ConsoleEntry) {
        if (tab.consoleLogs.size >= 100) tab.consoleLogs.removeFirst()
        tab.consoleLogs.addLast(entry)
    }

    private fun recordError(tab: Tab, entry: ErrorEntry) {
        if (tab.pageErrors.size >= 100) tab.pageErrors.removeFirst()
        tab.pageErrors.addLast(entry)
    }

    private fun dialogJson(d: JsDialogState): JSONObject = obj(
        "kind" to d.kind.name.lowercase(),
        "message" to d.message.take(500),
        "url" to BrowserPolicy.sanitizeTelemetryUrl(d.url),
        "defaultPrompt" to d.defaultPrompt.takeIf { d.kind == JsDialogKind.PROMPT },
    )

    private fun scheduleBackgroundDialogTimeout(tab: Tab, state: JsDialogState) {
        tab.dialogTimeoutJob?.cancel()
        if (foregroundSession.value != tab.owner) {
            tab.dialogTimeoutJob = scope.launch {
                delay(30_000)
                if (tab.jsDialog.value === state) {
                    tab.jsDialog.value = null
                    state.cancel()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun newTab(owner: String, url: String = "", id: String = UUID.randomUUID().toString(), selectActive: Boolean = true): Tab {
        require(url.isEmpty() || BrowserPolicy.allowedUrl(url)) { tr("仅支持 HTTPS 或本机 HTTP 页面", "Only HTTPS or localhost HTTP pages are supported") }
        val web = WebView(MutableContextWrapper(context.applicationContext))
        web.setLayerType(View.LAYER_TYPE_NONE, null)
        val defaultUa = web.settings.userAgentString.orEmpty()
        val tab = Tab(id, owner, web, defaultUa)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val targetUrl = request.url.toString()
                val blocked = !BrowserPolicy.allowedUrl(targetUrl)
                if (blocked) tab.error.value = tr("已阻止非 HTTPS／本机 HTTP 导航", "Blocked non-HTTPS / non-localhost navigation")
                return blocked
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                tab.generation++
                tab.navSeq++
                tab.loading.value = true
                tab.progress.intValue = 10
                tab.error.value = null
                tab.url.value = url
                syncNavigationFlags(tab)
            }
            override fun onPageCommitVisible(view: WebView, url: String) {
                if (tab.closed) return
                syncNavigationFlags(tab)
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (tab.closed || url != view.url) return
                tab.loading.value = false
                tab.progress.intValue = 100
                tab.url.value = url
                syncNavigationFlags(tab)
                CookieManager.getInstance().flush()
                persist()
                if (!isAgentCommandInFlight(tab.owner)) {
                    scope.launch { refreshPreview(tab, force = true, bumpRevision = false) }
                }
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if (tab.closed) return
                tab.url.value = url
                syncNavigationFlags(tab)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                val desc = error.description?.toString().orEmpty()
                if (error.errorCode != WebViewClient.ERROR_UNSUPPORTED_SCHEME && !desc.contains("ERR_ABORTED")) {
                    recordError(
                        tab,
                        ErrorEntry(
                            kind = "network",
                            message = desc.ifBlank { "ERR_${error.errorCode}" },
                            url = BrowserPolicy.sanitizeTelemetryUrl(request.url.toString()),
                            mainFrame = request.isForMainFrame,
                            navSeq = tab.navSeq,
                        ),
                    )
                }
                if (request.isForMainFrame) {
                    tab.loading.value = false
                    tab.error.value = desc
                }
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                tab.loading.value = false
                tab.error.value = tr("证书验证失败，已阻止连接", "Certificate verification failed; connection blocked")
                recordError(
                    tab,
                    ErrorEntry(
                        kind = "ssl",
                        message = tr("证书验证失败", "Certificate verification failed"),
                        url = BrowserPolicy.sanitizeTelemetryUrl(error.url.orEmpty()),
                        mainFrame = true,
                        navSeq = tab.navSeq,
                    ),
                )
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (errorResponse.statusCode >= 400) {
                    recordError(
                        tab,
                        ErrorEntry(
                            kind = "http",
                            message = "HTTP ${errorResponse.statusCode} ${errorResponse.reasonPhrase.orEmpty()}".trim(),
                            url = BrowserPolicy.sanitizeTelemetryUrl(request.url.toString()),
                            status = errorResponse.statusCode,
                            mainFrame = request.isForMainFrame,
                            navSeq = tab.navSeq,
                        ),
                    )
                    if (request.isForMainFrame) {
                        tab.error.value = "HTTP ${errorResponse.statusCode}"
                    }
                }
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                tab.generation++
                tab.crashed.value = true
                tab.error.value = tr("网页进程已退出，请关闭并重新打开标签页", "WebView renderer exited; close and reopen the tab")
                tab.loading.value = false
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
                (view.context as MutableContextWrapper).baseContext = context.applicationContext
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                tab.title.value = title.orEmpty().ifBlank { tr("新标签页", "New tab") }
            }
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                tab.progress.intValue = newProgress.coerceIn(0, 100)
            }
            override fun onCreateWindow(view: WebView, dialog: Boolean, userGesture: Boolean, resultMsg: Message): Boolean {
                if (!userGesture) return false
                val child = newTab(owner)
                activeTabByOwner[owner] = child.id
                (resultMsg.obj as WebView.WebViewTransport).webView = child.web
                resultMsg.sendToTarget()
                return true
            }
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val level = consoleMessage.messageLevel()?.name ?: "LOG"
                val msg = consoleMessage.message().orEmpty().take(500)
                val src = BrowserPolicy.sanitizeTelemetryUrl(consoleMessage.sourceId().orEmpty())
                val line = consoleMessage.lineNumber()
                recordConsole(tab, ConsoleEntry(level, msg, src, line, tab.navSeq))
                if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    recordError(tab, ErrorEntry("console", msg, src, 0, true, tab.navSeq))
                }
                return true
            }
            override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                val msg = message.orEmpty()
                tab.lastDialogNotice = msg
                if (foregroundSession.value != tab.owner) {
                    tab.lastAutoDialog = obj("kind" to "alert", "message" to msg.take(300), "handled" to "accepted")
                    result.confirm()
                    return true
                }
                tab.dialogTimeoutJob?.cancel()
                tab.jsDialog.value?.cancel()
                tab.jsDialog.value = JsDialogState(
                    kind = JsDialogKind.ALERT,
                    url = url.orEmpty(),
                    message = msg,
                    onConfirm = { tab.jsDialog.value = null; result.confirm() },
                    onCancel = { tab.jsDialog.value = null; result.cancel() },
                )
                return true
            }
            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                val msg = message.orEmpty()
                tab.lastDialogNotice = msg
                tab.dialogTimeoutJob?.cancel()
                tab.jsDialog.value?.cancel()
                val state = JsDialogState(
                    kind = JsDialogKind.CONFIRM,
                    url = url.orEmpty(),
                    message = msg,
                    onConfirm = { tab.dialogTimeoutJob?.cancel(); tab.jsDialog.value = null; result.confirm() },
                    onCancel = { tab.dialogTimeoutJob?.cancel(); tab.jsDialog.value = null; result.cancel() },
                )
                tab.jsDialog.value = state
                scheduleBackgroundDialogTimeout(tab, state)
                return true
            }
            override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
                val msg = message.orEmpty()
                tab.lastDialogNotice = msg
                tab.dialogTimeoutJob?.cancel()
                tab.jsDialog.value?.cancel()
                val state = JsDialogState(
                    kind = JsDialogKind.PROMPT,
                    url = url.orEmpty(),
                    message = msg,
                    defaultPrompt = defaultValue.orEmpty(),
                    onConfirm = { value -> tab.dialogTimeoutJob?.cancel(); tab.jsDialog.value = null; result.confirm(value.orEmpty()) },
                    onCancel = { tab.dialogTimeoutJob?.cancel(); tab.jsDialog.value = null; result.cancel() },
                )
                tab.jsDialog.value = state
                scheduleBackgroundDialogTimeout(tab, state)
                return true
            }
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                if (foregroundSession.value != tab.owner) {
                    filePathCallback.onReceiveValue(null)
                    return true
                }
                tab.fileChooser.value?.complete(null)
                val types = fileChooserParams.acceptTypes.orEmpty().filter { it.isNotBlank() }
                val multiple = fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                tab.fileChooser.value = FileChooserRequest(types, multiple, filePathCallback)
                return true
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
            }
        }
        web.setDownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, _ ->
            if (!BrowserPolicy.allowedUrl(downloadUrl)) {
                tab.error.value = tr("已阻止非安全协议文件下载", "Blocked download over insecure protocol")
                return@setDownloadListener
            }
            val fileName = URLUtil.guessFileName(downloadUrl, contentDisposition, mimeType)
            val agentDriven = tab.inFlightCount > 0 || isAgentCommandInFlight(tab.owner) || foregroundSession.value != tab.owner
            if (agentDriven && BrowserPolicy.isBlockedAgentDownload(fileName, mimeType)) {
                val msg = tr("已拦截高危类型文件下载：$fileName", "Blocked high-risk file download: $fileName")
                tab.error.value = msg
                recordError(tab, ErrorEntry("blocked_download", msg, BrowserPolicy.sanitizeTelemetryUrl(downloadUrl), 0, true, tab.navSeq))
                return@setDownloadListener
            }
            runCatching {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val req = DownloadManager.Request(Uri.parse(downloadUrl))
                    .setTitle(fileName)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                if (!mimeType.isNullOrBlank()) req.setMimeType(mimeType)
                if (!userAgent.isNullOrBlank()) req.addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(downloadUrl)?.takeIf { it.isNotBlank() }?.let { cookie ->
                    req.addRequestHeader("Cookie", cookie)
                }
                dm.enqueue(req)
                tab.notice.value = tr("已开始下载：$fileName", "Download started: $fileName")
            }.onFailure { err ->
                tab.error.value = tr("启动下载失败：${err.message ?: "系统下载服务不可用"}", "Failed to start download: ${err.message ?: "System download manager unavailable"}")
            }
        }

        layoutOffscreen(web)
        tabs.add(tab)
        if (selectActive || !activeTabByOwner.containsKey(owner)) {
            activeTabByOwner[owner] = tab.id
        }
        syncTabLifecycle()
        if (url.isNotEmpty()) {
            tab.loading.value = true
            tab.previewRevision.intValue++
            web.loadUrl(url)
        }
        return tab
    }

    fun attach(tab: Tab, host: android.widget.FrameLayout) {
        check(!tab.closed && !tab.crashed.value) { tr("标签页已关闭或网页进程已退出", "Tab is closed or renderer exited") }
        tab.web.setLayerType(View.LAYER_TYPE_NONE, null)
        if (tab.web.parent === host) {
            (tab.web.context as MutableContextWrapper).baseContext = host.context
            return
        }
        (tab.web.parent as? android.view.ViewGroup)?.removeView(tab.web)
        (tab.web.context as MutableContextWrapper).baseContext = host.context
        host.addView(tab.web, android.widget.FrameLayout.LayoutParams(-1, -1))
    }

    fun detach(tab: Tab, host: android.widget.FrameLayout) {
        if (tab.web.parent === host) {
            host.removeView(tab.web)
            (tab.web.context as MutableContextWrapper).baseContext = context.applicationContext
            if (!tab.closed && !tab.crashed.value) {
                layoutOffscreen(tab.web)
            }
        }
    }

    fun close(tab: Tab) {
        if (tab.closed) return
        tab.closed = true
        tab.generation++
        tab.dialogTimeoutJob?.cancel()
        tab.dialogTimeoutJob = null
        tab.jsDialog.value?.cancel()
        tab.jsDialog.value = null
        tab.fileChooser.value?.complete(null)
        tab.fileChooser.value = null
        tab.previewBitmap.value = null
        tabs.remove(tab)
        if (activeTabByOwner[tab.owner] == tab.id) {
            val fallback = tabs.lastOrNull { it.owner == tab.owner && !it.closed }?.id
            if (fallback != null) activeTabByOwner[tab.owner] = fallback else activeTabByOwner.remove(tab.owner)
        }
        if (foregroundTabByOwner[tab.owner] == tab.id) {
            val fallback = tabs.lastOrNull { it.owner == tab.owner && !it.closed }?.id
            if (fallback != null) foregroundTabByOwner[tab.owner] = fallback else foregroundTabByOwner.remove(tab.owner)
        }
        syncTabLifecycle()
        (tab.web.parent as? android.view.ViewGroup)?.removeView(tab.web)
        if (!tab.crashed.value) tab.web.destroy()
        (tab.web.context as MutableContextWrapper).baseContext = context.applicationContext
        persist()
    }

    fun closeOwned(owner: String) {
        tabs.filter { it.owner == owner }.toList().forEach(::close)
        activeTabByOwner.remove(owner)
    }

    fun navigate(tab: Tab, url: String, selectActive: Boolean = true) {
        check(!tab.closed && !tab.crashed.value) { tr("标签页已关闭或网页进程已退出", "Tab is closed or renderer exited") }
        require(BrowserPolicy.allowedUrl(url)) { tr("请输入 HTTPS 或 localhost HTTP 网址", "Enter an HTTPS or localhost HTTP URL") }
        if (selectActive) {
            activeTabByOwner[tab.owner] = tab.id
        }
        tab.generation++
        tab.loading.value = true
        tab.error.value = null
        tab.web.loadUrl(url)
    }

    suspend fun extractSelectionQuote(tab: Tab): String? {
        if (tab.closed || tab.crashed.value || tab.loading.value) return null
        val res = evaluatePage(tab, obj("action" to "selection"))
        val text = res.optString("selection").trim()
        if (!res.optBoolean("ok") || text.isEmpty()) return null
        val url = tab.url.value.ifBlank { res.optString("url") }
        val quoted = text.lines().joinToString("\n") { "> $it" }
        return if (url.isNotBlank()) tr("$quoted\n来源：$url", "$quoted\nSource: $url") else quoted
    }

    private fun persist() {
        val out = manifest.startWrite()
        try {
            val savedTabs = tabs.filter { BrowserPolicy.allowedUrl(it.url.value) }
                .map { obj("id" to it.id, "owner" to it.owner, "url" to it.url.value) }
            out.write(obj("tabs" to JSONArray(savedTabs)).toString().toByteArray())
            manifest.finishWrite(out)
        } catch (_: Exception) {
            manifest.failWrite(out)
        }
    }

    private fun info(tab: Tab) = obj(
        "id" to tab.id,
        "title" to tab.title.value,
        "url" to tab.url.value,
        "loading" to tab.loading.value,
        "canGoBack" to tab.canGoBack.value,
        "canGoForward" to tab.canGoForward.value,
        "active" to (activeTabByOwner[tab.owner] == tab.id),
        "error" to tab.error.value,
    )

    private fun resolveActivity(view: View): Activity? {
        var ctx: Context? = (view.context as? MutableContextWrapper)?.baseContext ?: view.context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private suspend fun captureViewportBitmap(tab: Tab, targetWidthPx: Int): Bitmap? {
        if (tab.closed || tab.crashed.value) return null
        val web = tab.web
        if (!web.isAttachedToWindow && web.parent == null) {
            layoutOffscreen(web)
        }
        val rawW = web.width.takeIf { it > 0 } ?: contentViewportWidthPx
        val rawH = web.height.takeIf { it > 0 } ?: contentViewportHeightPx
        if (rawW <= 0 || rawH <= 0) return null

        val attachedActivity = if (web.isAttachedToWindow) resolveActivity(web) else null
        val window = attachedActivity?.window
        if (Build.VERSION.SDK_INT >= 26 && window != null && web.isShown) {
            val loc = IntArray(2)
            web.getLocationInWindow(loc)
            val decorW = window.decorView.width
            val decorH = window.decorView.height
            if (rawW > 16 && rawH > 16 && loc[0] >= 0 && loc[1] >= 0 && loc[0] + rawW <= decorW && loc[1] + rawH <= decorH) {
                val scale = (targetWidthPx.toFloat() / rawW.toFloat()).coerceIn(0.15f, 1f)
                val outW = (rawW * scale).roundToInt().coerceAtLeast(1)
                val outH = (rawH * scale).roundToInt().coerceAtLeast(1)
                val dst = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                val copied = suspendCancellableCoroutine<Boolean> { cont ->
                    runCatching {
                        PixelCopy.request(window, Rect(loc[0], loc[1], loc[0] + rawW, loc[1] + rawH), dst, { code ->
                            if (cont.isActive) cont.resume(code == PixelCopy.SUCCESS)
                        }, mainHandler)
                    }.onFailure {
                        if (cont.isActive) cont.resume(false)
                    }
                }
                if (copied) return dst
            }
        }

        val scale = (targetWidthPx.toFloat() / rawW.toFloat()).coerceIn(0.15f, 1f)
        val outW = (rawW * scale).roundToInt().coerceAtLeast(1)
        val outH = (rawH * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.scale(scale, scale)
        canvas.translate(-web.scrollX.toFloat(), -web.scrollY.toFloat())
        runCatching { web.draw(canvas) }
        return bitmap
    }

    suspend fun refreshPreview(tab: Tab, force: Boolean = false, bumpRevision: Boolean = false) {
        if (tab.closed || tab.crashed.value || tab.url.value.isBlank()) return
        if (!isAgentOperating(tab.owner)) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - tab.lastPreviewAtMs < 1100L) return
        tab.lastPreviewAtMs = now
        val bmp = captureViewportBitmap(tab, targetWidthPx = 280) ?: return
        if (!tab.closed) {
            tab.previewBitmap.value = bmp
            if (bumpRevision) tab.previewRevision.intValue++
        }
    }

    private suspend fun awaitPageSettled(tab: Tab, maxWaitMs: Long = 4200L) {
        val deadline = SystemClock.uptimeMillis() + maxWaitMs.coerceIn(150L, 10_000L)
        delay(90)
        while (SystemClock.uptimeMillis() < deadline) {
            if (tab.closed || tab.crashed.value || tab.error.value != null) return
            if (!tab.loading.value) {
                delay(140)
                if (!tab.loading.value) return
            }
            delay(80)
        }
    }

    private suspend fun dispatchSwipeGesture(
        tab: Tab,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long,
    ) {
        val web = tab.web
        if (!web.isAttachedToWindow && web.parent == null) layoutOffscreen(web)
        val steps = (durationMs / 18L).toInt().coerceIn(6, 40)
        val stepDelay = (durationMs / steps).coerceAtLeast(8L)
        val downTime = SystemClock.uptimeMillis()
        fun obtain(action: Int, eventTime: Long, x: Float, y: Float): MotionEvent =
            MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
        obtain(MotionEvent.ACTION_DOWN, downTime, fromX, fromY).let { ev ->
            web.dispatchTouchEvent(ev)
            ev.recycle()
        }
        for (i in 1..steps) {
            delay(stepDelay)
            val f = i.toFloat() / steps.toFloat()
            val cx = fromX + (toX - fromX) * f
            val cy = fromY + (toY - fromY) * f
            obtain(MotionEvent.ACTION_MOVE, SystemClock.uptimeMillis(), cx, cy).let { ev ->
                web.dispatchTouchEvent(ev)
                ev.recycle()
            }
        }
        obtain(MotionEvent.ACTION_UP, SystemClock.uptimeMillis(), toX, toY).let { ev ->
            web.dispatchTouchEvent(ev)
            ev.recycle()
        }
    }

    private suspend fun dispatchCoordinateTap(tab: Tab, normX: Float, normY: Float) {
        val web = tab.web
        if (!web.isAttachedToWindow && web.parent == null) layoutOffscreen(web)
        val viewW = (web.width.takeIf { it > 0 } ?: contentViewportWidthPx).toFloat()
        val viewH = (web.height.takeIf { it > 0 } ?: contentViewportHeightPx).toFloat()
        val x = (normX * viewW).coerceIn(1f, (viewW - 1f).coerceAtLeast(1f))
        val y = (normY * viewH).coerceIn(1f, (viewH - 1f).coerceAtLeast(1f))
        val downTime = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        fun obtainTouch(action: Int, eventTime: Long, pressure: Float): MotionEvent {
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                this.pressure = pressure
                this.size = 0.05f
            })
            return MotionEvent.obtain(
                downTime, eventTime, action, 1, props, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
        }
        obtainTouch(MotionEvent.ACTION_DOWN, downTime, 1f).let { ev ->
            web.dispatchTouchEvent(ev)
            ev.recycle()
        }
        delay(32)
        obtainTouch(MotionEvent.ACTION_UP, SystemClock.uptimeMillis(), 0f).let { ev ->
            web.dispatchTouchEvent(ev)
            ev.recycle()
        }
    }

    private suspend fun finalizeTapInPage(tab: Tab, token: String, fallback: Boolean): JSONObject? {
        if (tab.closed || tab.crashed.value || tab.jsDialog.value != null) return null
        val payload = JSONObject()
            .put("action", "__finalizeTap")
            .put("token", token)
            .put("fallback", fallback)
        val deferred = CompletableDeferred<String?>()
        tab.web.evaluateJavascript("($pageScript)($payload)") { value ->
            deferred.complete(value)
        }
        val dialogWatcher = scope.launch {
            while (isActive && !deferred.isCompleted) {
                delay(50)
                if (tab.jsDialog.value != null && !deferred.isCompleted) {
                    deferred.complete(null)
                    break
                }
            }
        }
        val raw = try {
            withTimeoutOrNull(1200) { deferred.await() }
        } finally {
            dialogWatcher.cancel()
        } ?: return null
        return runCatching { org.json.JSONTokener(raw).nextValue() as? JSONObject }.getOrNull()
    }

    private suspend fun completeNativeTap(tab: Tab, prepared: JSONObject, pageRes: JSONObject): JSONObject {
        val tap = pageRes.optJSONObject("tap") ?: return pageRes
        pageRes.remove("tap")
        val token = tap.optString("token")
        val normX = tap.optDouble("normX", -1.0).toFloat()
        val normY = tap.optDouble("normY", -1.0).toFloat()
        if (token.isEmpty() || normX !in 0f..1f || normY !in 0f..1f) return pageRes

        val genBefore = tab.generation
        dispatchCoordinateTap(tab, normX, normY)

        val tag = pageRes.optString("tag")
        if (tag == "iframe" || tag == "frame") {
            delay(45)
            tab.jsDialog.value?.let { pending ->
                return obj(
                    "ok" to true,
                    "dispatched" to true,
                    "dialogOpened" to true,
                    "pendingDialog" to dialogJson(pending),
                )
            }
            return pageRes
        }

        val startMs = SystemClock.uptimeMillis()
        var waitingForClick = false
        delay(24)
        while (true) {
            if (tab.closed || tab.crashed.value) {
                return obj("ok" to false, "code" to "TAB_UNAVAILABLE", "error" to tr("标签页已关闭或网页进程已退出", "Tab is closed or renderer exited"), "dispatched" to true)
            }
            tab.jsDialog.value?.let { pending ->
                return obj(
                    "ok" to true,
                    "dispatched" to true,
                    "dialogOpened" to true,
                    "pendingDialog" to dialogJson(pending),
                )
            }
            if (tab.generation != genBefore) {
                if (prepared.optBoolean("reuseSnapshot", false)) {
                    pageRes.put("navigated", true)
                }
                return pageRes
            }
            val elapsed = SystemClock.uptimeMillis() - startMs
            val budget = if (waitingForClick) 360L else 120L
            val shouldFallback = elapsed >= budget
            val check = finalizeTapInPage(tab, token, shouldFallback) ?: return pageRes
            when (check.optString("status")) {
                "delivered", "fallback", "gone", "skipped" -> return pageRes
                "waiting_click" -> waitingForClick = true
            }
            if (shouldFallback) return pageRes
            delay(35)
        }
    }

    private fun resolveTargetTab(owner: String, request: JSONObject): Tab {
        val raw = request.optString("tab")
        if (raw.isEmpty() || raw == "\$active") {
            return activeTabFor(owner) ?: error(tr("当前会话尚无已打开的标签页；请先执行 open 打开网页", "No open tab in this session; run open first"))
        }
        val tab = tabs.firstOrNull { it.id == raw } ?: error(tr("标签页不存在；请检查 tabs", "Tab not found; check tabs"))
        BrowserPolicy.requireOwner(tab.owner, owner)
        activeTabByOwner[owner] = tab.id
        return tab
    }

    private fun canonicalSnapshotLines(page: JSONObject): List<String> {
        val lines = ArrayList<String>()
        val title = page.optString("title")
        val url = page.optString("url")
        if (title.isNotBlank() || url.isNotBlank()) lines.add("$title — $url".trim(' ', '—'))
        val elements = page.optJSONArray("elements")
        if (elements != null) {
            for (i in 0 until minOf(elements.length(), 200)) {
                val el = elements.optJSONObject(i) ?: continue
                val row = buildString {
                    append(el.optString("ref"))
                    append(" <").append(el.optString("tag")).append('>')
                    el.optString("role").takeIf { it.isNotBlank() && it != el.optString("tag") }?.let { append(" role=").append(it) }
                    el.optString("text").takeIf { it.isNotBlank() }?.let { append(" \"").append(it.take(80)).append('"') }
                    el.optString("label").takeIf { it.isNotBlank() && it != el.optString("text") }?.let { append(" label=\"").append(it.take(60)).append('"') }
                    el.optString("value").takeIf { it.isNotBlank() }?.let { append(" value=\"").append(it.take(60)).append('"') }
                    if (el.has("checked")) append(" checked=").append(el.optBoolean("checked"))
                    if (el.optBoolean("disabled")) append(" [disabled]")
                    el.optString("href").takeIf { it.isNotBlank() }?.let { append(" -> ").append(it.take(120)) }
                }
                lines.add(row)
            }
        }
        val md = page.optString("markdown").ifBlank { page.optString("text") }
        if (md.isNotBlank()) {
            md.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(300).forEach { lines.add(it) }
        }
        return lines
    }

    private fun enrichReadPayload(tab: Tab, page: JSONObject): JSONObject {
        if (!page.optBoolean("ok")) return page
        tab.lastSnapshotLines = canonicalSnapshotLines(page)
        val currentErrors = tab.pageErrors.filter { it.navSeq == tab.navSeq }
        page.put("errorCount", currentErrors.size)
        if (currentErrors.isNotEmpty()) {
            page.put(
                "recentErrors",
                JSONArray(
                    currentErrors.takeLast(5).map { e ->
                        obj(
                            "kind" to e.kind,
                            "message" to e.message,
                            "url" to e.url.takeIf { it.isNotBlank() },
                            "status" to e.status.takeIf { it > 0 },
                        )
                    },
                ),
            )
        }
        tab.jsDialog.value?.let { page.put("pendingDialog", dialogJson(it)) }
        return page
    }

    private fun drawAnnotatedScreenshot(base: Bitmap, meta: JSONObject): Pair<Bitmap, JSONArray> {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val cssW = meta.optInt("viewportWidth").takeIf { it > 0 } ?: out.width
        val cssH = meta.optInt("viewportHeight").takeIf { it > 0 } ?: out.height
        val sx = out.width.toFloat() / cssW.toFloat()
        val sy = out.height.toFloat() / cssH.toFloat()
        val palette = intArrayOf(
            0xFFE53935.toInt(),
            0xFF1E88E5.toInt(),
            0xFF00897B.toInt(),
            0xFFD81B60.toInt(),
        )
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1.8f, out.width / 260f)
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = (out.width / 38f).coerceIn(10f, 16f)
        }
        val elements = meta.optJSONArray("elements") ?: JSONArray()
        val annotations = JSONArray()
        var drawn = 0
        for (i in 0 until elements.length()) {
            if (drawn >= 60) break
            val el = elements.optJSONObject(i) ?: continue
            if (!el.optBoolean("inViewport", false)) continue
            val b = el.optJSONObject("bounds") ?: continue
            val bw = b.optDouble("width", 0.0).toFloat() * sx
            val bh = b.optDouble("height", 0.0).toFloat() * sy
            if (bw < 4f || bh < 4f) continue
            val left = (b.optDouble("x", 0.0).toFloat() * sx).coerceIn(0f, out.width - 2f)
            val top = (b.optDouble("y", 0.0).toFloat() * sy).coerceIn(0f, out.height - 2f)
            val right = (left + bw).coerceAtMost(out.width.toFloat())
            val bottom = (top + bh).coerceAtMost(out.height.toFloat())
            if (right - left < 3f || bottom - top < 3f) continue
            val color = palette[drawn % palette.size]
            boxPaint.color = color
            fillPaint.color = (color and 0x00FFFFFF) or 0x18000000
            badgePaint.color = color
            val rect = RectF(left, top, right, bottom)
            canvas.drawRoundRect(rect, 3f, 3f, fillPaint)
            canvas.drawRoundRect(rect, 3f, 3f, boxPaint)
            val ref = el.optString("ref", "@${i + 1}")
            val num = ref.removePrefix("@")
            val tw = textPaint.measureText(num)
            val fm = textPaint.fontMetrics
            val padX = 4f
            val padY = 2f
            val badgeW = max(tw + padX * 2f, -fm.ascent + fm.descent + padY * 2f)
            val badgeH = -fm.ascent + fm.descent + padY * 2f
            val bx = left.coerceIn(0f, (out.width - badgeW).coerceAtLeast(0f))
            val by = (if (top >= badgeH + 1f) top - badgeH else top).coerceIn(0f, (out.height - badgeH).coerceAtLeast(0f))
            canvas.drawRoundRect(RectF(bx, by, bx + badgeW, by + badgeH), 4f, 4f, badgePaint)
            canvas.drawText(num, bx + (badgeW - tw) / 2f, by + padY - fm.ascent, textPaint)
            annotations.put(
                obj(
                    "ref" to ref,
                    "tag" to el.optString("tag"),
                    "text" to el.optString("text").take(80),
                    "label" to el.optString("label").take(60).takeIf { it.isNotEmpty() },
                    "bounds" to b,
                ),
            )
            drawn++
        }
        return out to annotations
    }

    private suspend fun execute(owner: String, request: JSONObject): JSONObject {
        BrowserProtocol.validate(request)
        val action = request.getString("action")
        if (action == "tabs") {
            return obj("ok" to true, "tabs" to JSONArray(tabs.filter { it.owner == owner }.map(::info)))
        }
        val bgOpen = action == "open" && (request.optBoolean("background", false) || (foregroundSession.value == owner && !allowLiveAgent.value))
        if (action != "close" && !bgOpen) {
            agentOperatingSessions[owner] = true
            agentOperationSeq[owner] = (agentOperationSeq[owner] ?: 0) + 1
            syncTabLifecycle()
        }
        agentInFlightCount[owner] = (agentInFlightCount[owner] ?: 0) + 1
        try {
            if (action == "open") {
                val tab = newTab(owner, request.getString("url"), selectActive = !bgOpen)
                tab.inFlightCount++
                syncTabLifecycle()
                try {
                    if (request.optBoolean("waitAfter", false)) {
                        awaitPageSettled(tab, request.optLong("settleMs", 5000L))
                    }
                    if (!bgOpen) {
                        scope.launch { refreshPreview(tab, force = true, bumpRevision = true) }
                    }
                    val out = obj("ok" to true, "tab" to info(tab), "background" to bgOpen)
                    if (request.optBoolean("returnRead", false) && !tab.loading.value && tab.error.value == null) {
                        val readReq = obj(
                            "action" to "read",
                            "interactiveOnly" to request.optBoolean("interactiveOnly", false),
                            "includeUrls" to request.optBoolean("includeUrls", false),
                        )
                        out.put("page", enrichReadPayload(tab, evaluatePage(tab, readReq)))
                    } else {
                        tab.jsDialog.value?.let { out.put("pendingDialog", dialogJson(it)) }
                    }
                    return out
                } finally {
                    tab.inFlightCount = (tab.inFlightCount - 1).coerceAtLeast(0)
                    syncTabLifecycle()
                }
            }
            val tab = resolveTargetTab(owner, request)
            val isBgTab = foregroundSession.value == owner && !allowLiveAgent.value && foregroundTabByOwner[owner] != tab.id
            tab.inFlightCount++
            syncTabLifecycle()
            try {
                val result = if (action != "wait" && action != "batch") {
                    tab.lock.withLock { executeStep(tab, request) }
                } else {
                    runLockedBrowserWorkflow(tab.lock, request, { step -> executeStep(tab, step) })
                }
                if (action == "close") {
                    if (activeTabFor(owner) == null) {
                        agentOperatingSessions[owner] = false
                    }
                } else if (action != "wait") {
                    if (request.optBoolean("waitAfter", false) && result.optBoolean("ok") && tab.jsDialog.value == null) {
                        awaitPageSettled(tab, request.optLong("settleMs", 4200L))
                        result.put("tab", info(tab))
                    }
                    if (request.optBoolean("returnRead", false) && result.optBoolean("ok") && !tab.loading.value && tab.error.value == null && tab.jsDialog.value == null) {
                        result.put("page", enrichReadPayload(tab, evaluatePage(tab, obj("action" to "read"))))
                    } else {
                        tab.jsDialog.value?.let { result.put("pendingDialog", dialogJson(it)) }
                    }
                    if (action != "screenshot" && !isBgTab) {
                        val bump = action in BrowserProtocol.mutatingActions
                        scope.launch { refreshPreview(tab, force = true, bumpRevision = bump) }
                    }
                }
                return result
            } finally {
                tab.inFlightCount = (tab.inFlightCount - 1).coerceAtLeast(0)
                syncTabLifecycle()
            }
        } finally {
            val next = ((agentInFlightCount[owner] ?: 1) - 1).coerceAtLeast(0)
            agentInFlightCount[owner] = next
        }
    }

    private suspend fun executeStep(tab: Tab, request: JSONObject): JSONObject {
        coroutineContext.ensureActive()
        if (tab.closed || tab.crashed.value) {
            return obj("ok" to false, "code" to "TAB_UNAVAILABLE", "error" to tr("标签页已关闭或网页进程已退出", "Tab is closed or renderer exited"), "dispatched" to false)
        }
        return when (val action = request.getString("action")) {
            "navigate" -> {
                val keepFg = foregroundSession.value == tab.owner && !allowLiveAgent.value && foregroundTabByOwner[tab.owner] != tab.id
                navigate(tab, request.getString("url"), selectActive = !keepFg)
                obj("ok" to true, "dispatched" to true, "tab" to info(tab))
            }
            "back" -> {
                if (!tab.web.canGoBack()) {
                    obj("ok" to false, "code" to "NO_HISTORY_BACK", "error" to tr("当前标签页没有可后退的历史记录", "No back history in current tab"), "dispatched" to false)
                } else {
                    tab.generation++
                    tab.error.value = null
                    tab.web.goBack()
                    syncNavigationFlags(tab)
                    obj("ok" to true, "dispatched" to true, "tab" to info(tab))
                }
            }
            "forward" -> {
                if (!tab.web.canGoForward()) {
                    obj("ok" to false, "code" to "NO_HISTORY_FORWARD", "error" to tr("当前标签页没有可前进的历史记录", "No forward history in current tab"), "dispatched" to false)
                } else {
                    tab.generation++
                    tab.error.value = null
                    tab.web.goForward()
                    syncNavigationFlags(tab)
                    obj("ok" to true, "dispatched" to true, "tab" to info(tab))
                }
            }
            "reload" -> {
                tab.generation++
                tab.loading.value = true
                tab.error.value = null
                tab.web.reload()
                obj("ok" to true, "dispatched" to true, "tab" to info(tab))
            }
            "close" -> {
                close(tab)
                obj("ok" to true)
            }
            "swipe" -> {
                if (tab.loading.value || tab.error.value != null) {
                    obj("ok" to false, "code" to "PAGE_NOT_READY", "error" to tr("页面尚未就绪；先 wait 或检查 tabs", "Page is not ready yet; wait or check tabs"), "dispatched" to false)
                } else {
                    dispatchSwipeGesture(
                        tab = tab,
                        fromX = request.getDouble("fromX").toFloat(),
                        fromY = request.getDouble("fromY").toFloat(),
                        toX = request.getDouble("toX").toFloat(),
                        toY = request.getDouble("toY").toFloat(),
                        durationMs = request.optLong("durationMs", 280L).coerceIn(50L, 3000L),
                    )
                    obj(
                        "ok" to true,
                        "dispatched" to true,
                        "scrollX" to tab.web.scrollX,
                        "scrollY" to tab.web.scrollY,
                        "tab" to info(tab),
                    )
                }
            }
            "console" -> {
                val limit = request.optInt("limit", 50).coerceIn(1, 200)
                val entries = tab.consoleLogs.takeLast(limit).map { c ->
                    obj(
                        "level" to c.level.lowercase(),
                        "message" to c.message,
                        "source" to c.source.takeIf { it.isNotBlank() },
                        "line" to c.line.takeIf { it > 0 },
                    )
                }
                if (request.optBoolean("clear", false)) tab.consoleLogs.clear()
                obj("ok" to true, "dispatched" to false, "entries" to JSONArray(entries), "tab" to info(tab))
            }
            "errors" -> {
                val limit = request.optInt("limit", 50).coerceIn(1, 200)
                val entries = tab.pageErrors.takeLast(limit).map { e ->
                    obj(
                        "kind" to e.kind,
                        "message" to e.message,
                        "url" to e.url.takeIf { it.isNotBlank() },
                        "status" to e.status.takeIf { it > 0 },
                        "mainFrame" to e.mainFrame,
                    )
                }
                if (request.optBoolean("clear", false)) tab.pageErrors.clear()
                obj("ok" to true, "dispatched" to false, "errors" to JSONArray(entries), "tab" to info(tab))
            }
            "dialog" -> {
                val decision = request.optString("decision", "status")
                val pending = tab.jsDialog.value
                when (decision) {
                    "status" -> obj(
                        "ok" to true,
                        "dispatched" to false,
                        "pendingDialog" to pending?.let(::dialogJson),
                        "lastAutoDialog" to tab.lastAutoDialog,
                        "tab" to info(tab),
                    )
                    "accept" -> {
                        if (pending == null) {
                            obj("ok" to false, "code" to "NO_DIALOG", "error" to tr("当前没有待处理的网页弹窗", "No pending page dialog"), "dispatched" to false)
                        } else {
                            val kind = pending.kind.name.lowercase()
                            val text = if (request.has("promptText")) request.getString("promptText") else pending.defaultPrompt
                            pending.confirm(text)
                            obj("ok" to true, "dispatched" to true, "handled" to "accepted", "kind" to kind, "tab" to info(tab))
                        }
                    }
                    "dismiss" -> {
                        if (pending == null) {
                            obj("ok" to false, "code" to "NO_DIALOG", "error" to tr("当前没有待处理的网页弹窗", "No pending page dialog"), "dispatched" to false)
                        } else {
                            val kind = pending.kind.name.lowercase()
                            pending.cancel()
                            obj("ok" to true, "dispatched" to true, "handled" to "dismissed", "kind" to kind, "tab" to info(tab))
                        }
                    }
                    else -> error(tr("未知弹窗动作", "Unknown dialog action"))
                }
            }
            "diff" -> {
                if (tab.loading.value || tab.error.value != null) {
                    obj("ok" to false, "code" to "PAGE_NOT_READY", "error" to tr("页面尚未就绪；先 wait 或检查 tabs", "Page is not ready yet; wait or check tabs"), "dispatched" to false)
                } else {
                    val readReq = obj(
                        "action" to "read",
                        "includeUrls" to request.optBoolean("includeUrls", false),
                    ).apply {
                        if (request.has("scopeSelector")) put("scopeSelector", request.getString("scopeSelector"))
                    }
                    val page = evaluatePage(tab, readReq)
                    if (!page.optBoolean("ok")) {
                        page
                    } else {
                        val prev = tab.lastSnapshotLines
                        val curr = canonicalSnapshotLines(page)
                        tab.lastSnapshotLines = curr
                        if (prev == null) {
                            obj(
                                "ok" to true,
                                "dispatched" to false,
                                "hasBaseline" to false,
                                "snapshot" to page.optString("snapshot"),
                                "added" to curr.size,
                                "removed" to 0,
                                "unchanged" to 0,
                                "diffLines" to JSONArray(curr.take(200).map { "+ $it" }),
                                "tab" to info(tab),
                            )
                        } else {
                            val diff = BrowserPolicy.computeSnapshotDiff(prev, curr)
                            obj(
                                "ok" to true,
                                "dispatched" to false,
                                "hasBaseline" to true,
                                "snapshot" to page.optString("snapshot"),
                                "added" to diff.added,
                                "removed" to diff.removed,
                                "unchanged" to diff.unchanged,
                                "diffLines" to JSONArray(diff.lines),
                                "tab" to info(tab),
                            )
                        }
                    }
                }
            }
            "screenshot" -> {
                check(!tab.loading.value && tab.error.value == null) { tr("页面尚未就绪", "Page is not ready yet") }
                val annotate = request.optBoolean("annotate", false)
                val meta = if (annotate) evaluatePage(tab, obj("action" to "read", "interactiveOnly" to true)) else null
                if (meta != null && !meta.optBoolean("ok")) return meta
                val fullW = tab.web.width.takeIf { it > 0 } ?: contentViewportWidthPx
                val rawBitmap = captureViewportBitmap(tab, targetWidthPx = fullW.coerceIn(360, 720))
                    ?: error(tr("页面尚未完成绘制", "Page has not finished rendering yet"))
                tab.previewBitmap.value = rawBitmap
                val (outBitmap, annotations) = if (annotate && meta != null) {
                    drawAnnotatedScreenshot(rawBitmap, meta)
                } else {
                    rawBitmap to null
                }
                val file = File(dir, "${UUID.randomUUID()}.png")
                val outW = outBitmap.width
                val outH = outBitmap.height
                withContext(Dispatchers.IO) {
                    file.outputStream().use { outBitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
                    Os.chmod(file.absolutePath, 384)
                }
                obj(
                    "ok" to true,
                    "path" to io.github.modao7067.luma.gui.GuiProduct.runtimePath(context.filesDir, "${io.github.modao7067.luma.gui.GuiProduct.agentRelative()}/browser/${file.name}"),
                    "width" to outW,
                    "height" to outH,
                    "annotated" to annotate,
                    "annotations" to annotations,
                    "tab" to info(tab),
                )
            }
            "wait" -> {
                val state = BrowserProtocol.waitState(request)
                if (tab.error.value != null) obj("ok" to false, "code" to "PAGE_UNAVAILABLE", "error" to tab.error.value, "dispatched" to false)
                else if (tab.loading.value) obj("ok" to true, "matched" to false, "reason" to "LOADING")
                else when (state) {
                    "ready" -> obj("ok" to true, "matched" to true, "state" to state)
                    "url" -> obj("ok" to true, "matched" to (tab.web.url == request.getString("url")), "state" to state, "reason" to "URL_MISMATCH")
                    else -> evaluatePage(tab, request)
                }
            }
            in BrowserProtocol.pageActions -> {
                if (tab.loading.value || tab.error.value != null) {
                    obj("ok" to false, "code" to "PAGE_NOT_READY", "error" to tr("页面尚未就绪；先 wait 或检查 tabs", "Page is not ready yet; wait or check tabs"), "dispatched" to false)
                } else {
                    if (!tab.web.isAttachedToWindow && tab.web.parent == null) {
                        layoutOffscreen(tab.web)
                    }
                    val prepared = if (action in setOf("click", "fill", "type", "select", "check", "uncheck", "hover", "press") && !request.has("snapshot")) {
                        JSONObject(request.toString()).put("snapshot", "__live__").put("reuseSnapshot", true)
                    } else {
                        request
                    }
                    val pageRes = evaluatePage(tab, prepared, nativeTap = (action == "click"))
                    if (action == "read") {
                        enrichReadPayload(tab, pageRes)
                    }
                    if (action == "click" && pageRes.optBoolean("ok")) {
                        completeNativeTap(tab, prepared, pageRes)
                    } else {
                        pageRes
                    }
                }
            }
            else -> error(tr("未知操作", "Unknown action"))
        }
    }

    private suspend fun evaluatePage(tab: Tab, request: JSONObject, nativeTap: Boolean = false): JSONObject {
        tab.jsDialog.value?.let { pending ->
            return obj(
                "ok" to false,
                "code" to "DIALOG_OPEN",
                "error" to tr("页面存在未处理的 JS 弹窗，请先执行 luma-browser dialog accept 或 dismiss", "Unhandled JS dialog is open; run luma-browser dialog accept or dismiss first"),
                "dispatched" to false,
                "pendingDialog" to dialogJson(pending),
            )
        }
        val data = JSONObject()
        val allowedKeys = listOf(
            "action", "snapshot", "reuseSnapshot", "selector", "index", "ref",
            "x", "y", "text", "value", "clear", "submit", "key",
            "state", "direction", "amount", "intoView", "offset", "maxChars",
            "interactiveOnly", "includeUrls", "scopeSelector",
        )
        for (key in allowedKeys) {
            if (request.has(key)) data.put(key, request.get(key))
        }
        if (nativeTap) data.put("nativeTap", true)
        data.put("__lang", LumaI18n.langCode())
        val action = request.getString("action")
        val mutating = action in setOf("click", "fill", "type", "select", "check", "uncheck", "hover", "press")
        val generation = tab.generation
        val deferred = CompletableDeferred<String?>()
        tab.web.evaluateJavascript("($pageScript)($data)") { value ->
            deferred.complete(value)
        }
        val dialogWatcher = scope.launch {
            while (isActive && !deferred.isCompleted) {
                delay(80)
                if (tab.jsDialog.value != null && !deferred.isCompleted) {
                    delay(60)
                    if (tab.jsDialog.value != null && !deferred.isCompleted) {
                        deferred.complete("__LUMA_DIALOG_OPENED__")
                        break
                    }
                }
            }
        }
        val result = try {
            withTimeoutOrNull(8000) { deferred.await() }
        } finally {
            dialogWatcher.cancel()
        }
        if (result == "__LUMA_DIALOG_OPENED__") {
            val pending = tab.jsDialog.value
            return obj(
                "ok" to true,
                "dispatched" to mutating,
                "dialogOpened" to true,
                "pendingDialog" to pending?.let(::dialogJson),
            )
        }
        if (result == null) return obj(
            "ok" to false,
            "code" to "PAGE_CALLBACK_TIMEOUT",
            "error" to tr("网页回调超时；操作可能已执行，请查看页面，勿自动重试", "Page callback timed out; action may have executed, inspect page instead of retrying"),
            "dispatched" to mutating,
        )
        if (tab.closed || generation != tab.generation) {
            if (mutating && request.optBoolean("reuseSnapshot", false) && !tab.closed) {
                return obj("ok" to true, "dispatched" to true, "navigated" to true)
            }
            return obj(
                "ok" to false,
                "code" to "NAVIGATION_CHANGED",
                "error" to tr("页面已导航或关闭；操作可能已执行，请重新读取，勿自动重试", "Page navigated or closed; action may have executed, re-read instead of retrying"),
                "dispatched" to mutating,
            )
        }
        return (org.json.JSONTokener(result).nextValue() as? JSONObject)
            ?: obj("ok" to false, "code" to "INVALID_PAGE_RESULT", "error" to tr("页面未返回有效结果", "Page did not return a valid result"), "dispatched" to mutating)
    }

    companion object {
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
    }
}
