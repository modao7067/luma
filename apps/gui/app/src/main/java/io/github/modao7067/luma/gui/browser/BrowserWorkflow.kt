package io.github.modao7067.luma.gui.browser

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import org.json.JSONArray
import org.json.JSONObject

object BrowserProtocol {
    const val MAX_WAIT_MS = 10_000L
    const val MAX_BATCH_MS = 15_000L
    const val MAX_STEPS = 20
    val pageActions = setOf("read", "click", "fill", "type", "select", "check", "uncheck", "scroll", "press", "hover")
    val mutatingActions = setOf("click", "fill", "type", "select", "check", "uncheck", "navigate", "swipe", "back", "forward", "reload", "press", "hover", "dialog")
    private val stepActions = pageActions + setOf("wait", "navigate", "screenshot", "swipe", "back", "forward", "reload", "console", "errors", "dialog", "diff")
    private val states = setOf("attached", "detached", "visible", "hidden", "enabled", "actionable")
    private val scrollDirections = setOf("up", "down", "left", "right", "top", "bottom")

    fun milliseconds(request: JSONObject, default: Long, maximum: Long): Long {
        if (!request.has("timeoutMs")) return default
        val value = request.get("timeoutMs")
        require(value is Number && value.toDouble() == value.toLong().toDouble() && value.toLong() in 1..maximum) {
            "timeoutMs 必须是 1..$maximum 的整数毫秒"
        }
        return value.toLong()
    }

    fun budget(request: JSONObject): Long = when (request.getString("action")) {
        "batch" -> milliseconds(request, MAX_BATCH_MS, MAX_BATCH_MS)
        "wait" -> milliseconds(request, 3000, MAX_WAIT_MS)
        else -> MAX_BATCH_MS
    }

    fun waitState(request: JSONObject): String = request.optString("state").ifEmpty {
        if (request.has("selector")) "visible" else if (request.has("url")) "url" else "ready"
    }

    private fun requireFiniteNumber(request: JSONObject, key: String): Double {
        val raw = request.opt(key)
        require(raw is Number && raw.toDouble().isFinite()) { "$key 必须是有效数值" }
        return raw.toDouble()
    }

    private fun validateSingleTarget(request: JSONObject, allowCoordinates: Boolean, optional: Boolean = false) {
        val hasCoords = request.has("x") || request.has("y")
        val domTargetCount = listOf("selector", "index", "ref").count(request::has)
        if (hasCoords) {
            require(allowCoordinates && request.has("x") && request.has("y") && domTargetCount == 0) {
                "坐标点击/输入需同时提供数值 x 与 y，且不可混用 selector/index/ref"
            }
            requireFiniteNumber(request, "x")
            requireFiniteNumber(request, "y")
            return
        }
        if (optional && domTargetCount == 0) return
        require(domTargetCount == 1) { "动作必须提供 selector、index 或 ref 其中之一" }
        if (request.has("selector")) {
            require(request.opt("selector") is String && request.getString("selector").isNotBlank()) { "selector 必须是非空 CSS" }
        }
        if (request.has("ref")) {
            require(request.opt("ref") is String && request.getString("ref").matches(Regex("@[1-9][0-9]*"))) { "ref 必须是 @N" }
        }
        if (request.has("index")) {
            val index = request.get("index")
            require(
                (index is Number && index.toDouble() == index.toInt().toDouble() && index.toInt() >= 0) ||
                    (index is String && index.matches(Regex("0|[1-9][0-9]*")) && index.toIntOrNull() != null),
            ) { "index 必须是非负整数" }
        }
    }

    fun validate(request: JSONObject) {
        val action = request.getString("action")
        if (action == "batch") {
            budget(request)
            val steps = request.getJSONArray("steps")
            require(steps.length() in 1..MAX_STEPS) { "batch 需要 1..$MAX_STEPS 个步骤" }
            for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                require(!step.has("tab") && !step.has("token")) { "批内步骤使用外层 tab 与授权，不可覆盖" }
                require(step.getString("action") in stepActions) { "不支持的批内操作" }
                validateStep(step, true)
            }
        } else {
            require(action in stepActions + setOf("open", "tabs", "close")) { "未知操作" }
            validateStep(request, false)
        }
    }

    private fun validateStep(request: JSONObject, batch: Boolean) {
        if (request.has("snapshot")) {
            require(request.opt("snapshot") is String && request.getString("snapshot").isNotEmpty()) { "snapshot 必须是非空字符串" }
            require(batch || request.getString("snapshot") != "\$last") { "\$last 只能在 batch 内使用" }
        }
        when (request.getString("action")) {
            "open" -> {
                require(BrowserPolicy.allowedUrl(request.getString("url"))) { "仅支持 HTTPS 或本机 HTTP 页面" }
                if (request.has("background")) require(request.opt("background") is Boolean) { "background 必须是布尔值" }
            }
            "navigate" -> require(BrowserPolicy.allowedUrl(request.getString("url"))) { "仅支持 HTTPS 或本机 HTTP 页面" }
            "read" -> {
                if (request.has("interactiveOnly")) require(request.opt("interactiveOnly") is Boolean) { "interactiveOnly 必须是布尔值" }
                if (request.has("includeUrls")) require(request.opt("includeUrls") is Boolean) { "includeUrls 必须是布尔值" }
                if (request.has("scopeSelector")) {
                    require(request.opt("scopeSelector") is String && request.getString("scopeSelector").isNotBlank()) { "scopeSelector 必须是非空选择器" }
                }
            }
            "diff" -> {
                if (request.has("includeUrls")) require(request.opt("includeUrls") is Boolean) { "includeUrls 必须是布尔值" }
                if (request.has("scopeSelector")) {
                    require(request.opt("scopeSelector") is String && request.getString("scopeSelector").isNotBlank()) { "scopeSelector 必须是非空选择器" }
                }
            }
            "screenshot" -> {
                if (request.has("annotate")) require(request.opt("annotate") is Boolean) { "annotate 必须是布尔值" }
            }
            "click", "fill", "type", "hover" -> validateSingleTarget(request, allowCoordinates = true)
            "press" -> {
                val key = request.opt("key")
                require(key is String && key.isNotBlank() && key.length <= 32) { "press 需要提供 1..32 字符的按键名 key" }
                validateSingleTarget(request, allowCoordinates = false, optional = true)
            }
            "console", "errors" -> {
                if (request.has("clear")) require(request.opt("clear") is Boolean) { "clear 必须是布尔值" }
                if (request.has("limit")) {
                    val limit = request.get("limit")
                    require(limit is Number && limit.toDouble() == limit.toInt().toDouble() && limit.toInt() in 1..200) { "limit 必须是 1..200 的整数" }
                }
            }
            "dialog" -> {
                val decision = request.optString("decision", "status")
                require(decision in setOf("status", "accept", "dismiss")) { "dialog decision 仅支持 status/accept/dismiss" }
                if (request.has("promptText")) require(request.opt("promptText") is String) { "promptText 必须是字符串" }
            }
            "select" -> {
                validateSingleTarget(request, allowCoordinates = false)
                require((request.opt("text") is String && request.getString("text").isNotEmpty()) ||
                    (request.opt("value") is String && request.getString("value").isNotEmpty())) { "select 需要提供选项 text 或 value" }
            }
            "check", "uncheck" -> validateSingleTarget(request, allowCoordinates = true)
            "scroll" -> {
                validateSingleTarget(request, allowCoordinates = false, optional = true)
                val dir = request.optString("direction", "down").lowercase()
                require(dir in scrollDirections) { "direction 仅支持 up/down/left/right/top/bottom" }
                if (request.has("amount")) {
                    val amount = requireFiniteNumber(request, "amount")
                    require(amount > 0 && amount <= 20000) { "amount 必须在 1..20000 像素之间" }
                }
            }
            "swipe" -> {
                requireFiniteNumber(request, "fromX")
                requireFiniteNumber(request, "fromY")
                requireFiniteNumber(request, "toX")
                requireFiniteNumber(request, "toY")
                if (request.has("durationMs")) {
                    val d = request.get("durationMs")
                    require(d is Number && d.toLong() in 50..3000) { "durationMs 必须在 50..3000 之间" }
                }
            }
            "wait" -> {
                milliseconds(request, 3000, MAX_WAIT_MS)
                val state = waitState(request)
                when (state) {
                    "ready" -> require(!request.has("selector") && !request.has("url")) { "ready 不接受 selector/url" }
                    "url" -> require(!request.has("selector") && BrowserPolicy.allowedUrl(request.getString("url"))) { "url 等待需要合法的完整 URL" }
                    else -> require(state in states && !request.has("url") && request.opt("selector") is String && request.getString("selector").isNotBlank()) { "selector 等待状态无效" }
                }
            }
        }
    }
}

class BrowserWorkflow(
    private val dispatch: suspend (JSONObject) -> JSONObject,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun run(request: JSONObject, remainingMs: Long = BrowserProtocol.budget(request)): JSONObject {
        BrowserProtocol.validate(request)
        val batch = request.getString("action") == "batch"
        val reuseBatchSnapshot = request.optBoolean("reuseSnapshot", false)
        val steps = if (batch) request.getJSONArray("steps") else JSONArray().put(request)
        val results = JSONArray()
        var lastSnapshot: String? = null
        var currentAction = ""
        var currentDispatched = false
        var completed = 0
        var final: JSONObject? = null
        val deadline = now() + remainingMs.coerceAtLeast(0)
        val finished = withTimeoutOrNull(remainingMs.coerceAtLeast(1)) {
            for (i in 0 until steps.length()) {
                val step = JSONObject(steps.getJSONObject(i).toString())
                currentAction = step.getString("action")
                currentDispatched = false
                if (now() >= deadline) break
                val stepReuse = reuseBatchSnapshot || step.optBoolean("reuseSnapshot", false)
                val result = if (step.optString("snapshot") == "\$last" && lastSnapshot == null) {
                    failure("SNAPSHOT_REQUIRED", "\$last 需要前一步尚未消费的 read 快照")
                } else {
                    if (step.optString("snapshot") == "\$last") step.put("snapshot", lastSnapshot)
                    if (stepReuse && !step.has("reuseSnapshot")) step.put("reuseSnapshot", true)
                    if (currentAction in BrowserProtocol.mutatingActions) currentDispatched = true
                    if (currentAction == "wait") wait(step, deadline) else safeDispatch(step)
                }
                currentDispatched = result.optBoolean("dispatched", currentDispatched)
                if (currentAction == "read" && result.optBoolean("ok")) lastSnapshot = result.getString("snapshot")
                val navigationAction = currentAction in setOf("navigate", "back", "forward", "reload")
                if (navigationAction || (!stepReuse && currentAction in BrowserProtocol.mutatingActions && currentDispatched)) {
                    lastSnapshot = null
                }
                if (!result.optBoolean("ok")) {
                    final = if (batch) batchFailure(results, i, result, currentDispatched) else result
                    return@withTimeoutOrNull true
                }
                results.put(result)
                completed++
            }
            if (completed == steps.length()) {
                final = if (batch) JSONObject().put("ok", true).put("completed", completed).put("results", results) else results.getJSONObject(0)
                true
            } else false
        }
        if (finished != true) {
            val timeout = failure("DEADLINE_EXCEEDED", "操作总预算已到；检查已完成结果，勿重放批次或已派发动作").put("dispatched", currentDispatched)
            return if (batch) batchFailure(results, completed, timeout, currentDispatched) else timeout
        }
        return requireNotNull(final)
    }

    private suspend fun wait(request: JSONObject, outerDeadline: Long): JSONObject {
        val duration = BrowserProtocol.milliseconds(request, 3000, BrowserProtocol.MAX_WAIT_MS)
        val deadline = minOf(outerDeadline, now() + duration)
        val probe = JSONObject(request.toString()).put("state", BrowserProtocol.waitState(request))
        var last: JSONObject? = null
        do {
            val remaining = deadline - now()
            if (remaining <= 0) break
            last = withTimeoutOrNull(remaining) { safeDispatch(probe) }
            if (last != null && !last.optBoolean("ok")) return last
            if (last?.optBoolean("matched") == true && now() < deadline) return last.put("waited", true)
            if (now() >= deadline) break
            pause(minOf(100, deadline - now()))
        } while (true)
        return failure("WAIT_TIMEOUT", "等待条件未在 ${duration}ms 内满足")
            .put("state", probe.getString("state")).put("reason", last?.optString("reason") ?: "PROBE_TIMEOUT").put("dispatched", false)
    }

    private suspend fun safeDispatch(step: JSONObject): JSONObject = try {
        dispatch(step)
    } catch (error: CancellationException) { throw error }
    catch (error: Exception) {
        failure("STEP_FAILED", error.message ?: "浏览器操作失败")
            .put("dispatched", step.getString("action") in BrowserProtocol.mutatingActions)
    }

    private fun batchFailure(results: JSONArray, index: Int, result: JSONObject, dispatched: Boolean): JSONObject =
        failure(result.optString("code", "STEP_FAILED"), result.optString("error", "批内操作失败"))
            .put("completed", results.length()).put("results", results).put("failedStep", index)
            .put("failedAction", result).put("dispatched", dispatched)

    private fun failure(code: String, message: String) = JSONObject().put("ok", false).put("code", code).put("error", message).put("dispatched", false)
}

suspend fun runLockedBrowserWorkflow(
    lock: Mutex,
    request: JSONObject,
    dispatch: suspend (JSONObject) -> JSONObject,
    now: () -> Long = { System.nanoTime() / 1_000_000 },
): JSONObject {
    val budget = BrowserProtocol.budget(request)
    val started = now()
    val lease = Any()
    var acquired = false
    try {
        val locked = withTimeoutOrNull(budget) { lock.lock(lease); acquired = true; true }
        if (locked != true) return JSONObject().put("ok", false).put("code", "TAB_BUSY")
            .put("error", "等待同标签操作超时，未派发本次请求").put("dispatched", false)
            .put("completed", 0).put("results", JSONArray())
        coroutineContext.ensureActive()
        return BrowserWorkflow(dispatch, now).run(request, budget - (now() - started))
    } finally {
        if (acquired) lock.unlock(lease)
    }
}
