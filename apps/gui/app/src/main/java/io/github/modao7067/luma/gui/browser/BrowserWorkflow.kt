package io.github.modao7067.luma.gui.browser

import io.github.modao7067.luma.gui.i18n.tr

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
            tr("timeoutMs 必须是 1..$maximum 的整数毫秒", "timeoutMs must be an integer in 1..$maximum ms")
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
        require(raw is Number && raw.toDouble().isFinite()) { tr("$key 必须是有效数值", "$key must be a finite number") }
        return raw.toDouble()
    }

    private fun validateSingleTarget(request: JSONObject, allowCoordinates: Boolean, optional: Boolean = false) {
        val hasCoords = request.has("x") || request.has("y")
        val domTargetCount = listOf("selector", "index", "ref").count(request::has)
        if (hasCoords) {
            require(allowCoordinates && request.has("x") && request.has("y") && domTargetCount == 0) {
                tr("坐标点击/输入需同时提供数值 x 与 y，且不可混用 selector/index/ref", "Coordinate actions require numeric x and y and cannot be mixed with selector/index/ref")
            }
            requireFiniteNumber(request, "x")
            requireFiniteNumber(request, "y")
            return
        }
        if (optional && domTargetCount == 0) return
        require(domTargetCount == 1) { tr("动作必须提供 selector、index 或 ref 其中之一", "Action must specify exactly one of selector, index, or ref") }
        if (request.has("selector")) {
            require(request.opt("selector") is String && request.getString("selector").isNotBlank()) { tr("selector 必须是非空 CSS", "selector must be a non-empty CSS selector") }
        }
        if (request.has("ref")) {
            require(request.opt("ref") is String && request.getString("ref").matches(Regex("@[1-9][0-9]*"))) { tr("ref 必须是 @N", "ref must be @N") }
        }
        if (request.has("index")) {
            val index = request.get("index")
            require(
                (index is Number && index.toDouble() == index.toInt().toDouble() && index.toInt() >= 0) ||
                    (index is String && index.matches(Regex("0|[1-9][0-9]*")) && index.toIntOrNull() != null),
            ) { tr("index 必须是非负整数", "index must be a non-negative integer") }
        }
    }

    fun validate(request: JSONObject) {
        val action = request.getString("action")
        if (action == "batch") {
            budget(request)
            val steps = request.getJSONArray("steps")
            require(steps.length() in 1..MAX_STEPS) { tr("batch 需要 1..$MAX_STEPS 个步骤", "batch requires 1..$MAX_STEPS steps") }
            for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                require(!step.has("tab") && !step.has("token")) { tr("批内步骤使用外层 tab 与授权，不可覆盖", "Batch steps inherit outer tab and auth and cannot override them") }
                require(step.getString("action") in stepActions) { tr("不支持的批内操作", "Unsupported batch step action") }
                validateStep(step, true)
            }
        } else {
            require(action in stepActions + setOf("open", "tabs", "close")) { tr("未知操作", "Unknown action") }
            validateStep(request, false)
        }
    }

    private fun validateStep(request: JSONObject, batch: Boolean) {
        if (request.has("snapshot")) {
            require(request.opt("snapshot") is String && request.getString("snapshot").isNotEmpty()) { tr("snapshot 必须是非空字符串", "snapshot must be a non-empty string") }
            require(batch || request.getString("snapshot") != "\$last") { tr("\$last 只能在 batch 内使用", "\$last can only be used inside batch") }
        }
        when (request.getString("action")) {
            "open" -> {
                require(BrowserPolicy.allowedUrl(request.getString("url"))) { tr("仅支持 HTTPS 或本机 HTTP 页面", "Only HTTPS or localhost HTTP pages are supported") }
                if (request.has("background")) require(request.opt("background") is Boolean) { tr("background 必须是布尔值", "background must be a boolean") }
            }
            "navigate" -> require(BrowserPolicy.allowedUrl(request.getString("url"))) { tr("仅支持 HTTPS 或本机 HTTP 页面", "Only HTTPS or localhost HTTP pages are supported") }
            "read" -> {
                if (request.has("interactiveOnly")) require(request.opt("interactiveOnly") is Boolean) { tr("interactiveOnly 必须是布尔值", "interactiveOnly must be a boolean") }
                if (request.has("includeUrls")) require(request.opt("includeUrls") is Boolean) { tr("includeUrls 必须是布尔值", "includeUrls must be a boolean") }
                if (request.has("scopeSelector")) {
                    require(request.opt("scopeSelector") is String && request.getString("scopeSelector").isNotBlank()) { tr("scopeSelector 必须是非空选择器", "scopeSelector must be a non-empty selector") }
                }
            }
            "diff" -> {
                if (request.has("includeUrls")) require(request.opt("includeUrls") is Boolean) { tr("includeUrls 必须是布尔值", "includeUrls must be a boolean") }
                if (request.has("scopeSelector")) {
                    require(request.opt("scopeSelector") is String && request.getString("scopeSelector").isNotBlank()) { tr("scopeSelector 必须是非空选择器", "scopeSelector must be a non-empty selector") }
                }
            }
            "screenshot" -> {
                if (request.has("annotate")) require(request.opt("annotate") is Boolean) { tr("annotate 必须是布尔值", "annotate must be a boolean") }
            }
            "click", "fill", "type", "hover" -> validateSingleTarget(request, allowCoordinates = true)
            "press" -> {
                val key = request.opt("key")
                require(key is String && key.isNotBlank() && key.length <= 32) { tr("press 需要提供 1..32 字符的按键名 key", "press requires a key name of 1..32 chars") }
                validateSingleTarget(request, allowCoordinates = false, optional = true)
            }
            "console", "errors" -> {
                if (request.has("clear")) require(request.opt("clear") is Boolean) { tr("clear 必须是布尔值", "clear must be a boolean") }
                if (request.has("limit")) {
                    val limit = request.get("limit")
                    require(limit is Number && limit.toDouble() == limit.toInt().toDouble() && limit.toInt() in 1..200) { tr("limit 必须是 1..200 的整数", "limit must be an integer in 1..200") }
                }
            }
            "dialog" -> {
                val decision = request.optString("decision", "status")
                require(decision in setOf("status", "accept", "dismiss")) { tr("dialog decision 仅支持 status/accept/dismiss", "dialog decision must be status, accept, or dismiss") }
                if (request.has("promptText")) require(request.opt("promptText") is String) { tr("promptText 必须是字符串", "promptText must be a string") }
            }
            "select" -> {
                validateSingleTarget(request, allowCoordinates = false)
                require((request.opt("text") is String && request.getString("text").isNotEmpty()) ||
                    (request.opt("value") is String && request.getString("value").isNotEmpty())) { tr("select 需要提供选项 text 或 value", "select requires option text or value") }
            }
            "check", "uncheck" -> validateSingleTarget(request, allowCoordinates = true)
            "scroll" -> {
                validateSingleTarget(request, allowCoordinates = false, optional = true)
                val dir = request.optString("direction", "down").lowercase()
                require(dir in scrollDirections) { tr("direction 仅支持 up/down/left/right/top/bottom", "direction must be up, down, left, right, top, or bottom") }
                if (request.has("amount")) {
                    val amount = requireFiniteNumber(request, "amount")
                    require(amount > 0 && amount <= 20000) { tr("amount 必须在 1..20000 像素之间", "amount must be between 1 and 20000 px") }
                }
            }
            "swipe" -> {
                requireFiniteNumber(request, "fromX")
                requireFiniteNumber(request, "fromY")
                requireFiniteNumber(request, "toX")
                requireFiniteNumber(request, "toY")
                if (request.has("durationMs")) {
                    val d = request.get("durationMs")
                    require(d is Number && d.toLong() in 50..3000) { tr("durationMs 必须在 50..3000 之间", "durationMs must be between 50 and 3000") }
                }
            }
            "wait" -> {
                milliseconds(request, 3000, MAX_WAIT_MS)
                val state = waitState(request)
                when (state) {
                    "ready" -> require(!request.has("selector") && !request.has("url")) { tr("ready 不接受 selector/url", "ready does not accept selector or url") }
                    "url" -> require(!request.has("selector") && BrowserPolicy.allowedUrl(request.getString("url"))) { tr("url 等待需要合法的完整 URL", "url wait requires a valid full URL") }
                    else -> require(state in states && !request.has("url") && request.opt("selector") is String && request.getString("selector").isNotBlank()) { tr("selector 等待状态无效", "Invalid selector wait state") }
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
                    failure("SNAPSHOT_REQUIRED", tr("\$last 需要前一步尚未消费的 read 快照", "\$last requires an unconsumed read snapshot from a prior step"))
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
            val timeout = failure("DEADLINE_EXCEEDED", tr("操作总预算已到；检查已完成结果，勿重放批次或已派发动作", "Deadline exceeded; inspect completed steps instead of replaying")).put("dispatched", currentDispatched)
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
        return failure("WAIT_TIMEOUT", tr("等待条件未在 ${duration}ms 内满足", "Wait condition not met within ${duration}ms"))
            .put("state", probe.getString("state")).put("reason", last?.optString("reason") ?: "PROBE_TIMEOUT").put("dispatched", false)
    }

    private suspend fun safeDispatch(step: JSONObject): JSONObject = try {
        dispatch(step)
    } catch (error: CancellationException) { throw error }
    catch (error: Exception) {
        failure("STEP_FAILED", error.message ?: tr("浏览器操作失败", "Browser action failed"))
            .put("dispatched", step.getString("action") in BrowserProtocol.mutatingActions)
    }

    private fun batchFailure(results: JSONArray, index: Int, result: JSONObject, dispatched: Boolean): JSONObject =
        failure(result.optString("code", "STEP_FAILED"), result.optString("error", tr("批内操作失败", "Batch step failed")))
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
            .put("error", tr("等待同标签操作超时，未派发本次请求", "Timed out waiting for active tab operation; request not dispatched")).put("dispatched", false)
            .put("completed", 0).put("results", JSONArray())
        coroutineContext.ensureActive()
        return BrowserWorkflow(dispatch, now).run(request, budget - (now() - started))
    } finally {
        if (acquired) lock.unlock(lease)
    }
}
