package io.github.modao7067.luma.gui.agent

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class MiMoLiveIntegrationTest {
    private val model = "mimo-v2.6-flash"
    private val evidence get() = File(System.getenv("LUMA_LIVE_EVIDENCE")!!)
    private fun save(name: String, value: Any) { File(evidence, name).writeText(value.toString()) }
    private fun fresh(prefix: String) = prefix + UUID.randomUUID().toString().replace("-", "")
    private suspend fun await(description: String, timeout: Long = 120_000, condition: suspend () -> Boolean) {
        try { withTimeout(timeout) { while (!condition()) delay(60) } }
        catch (e: TimeoutCancellationException) { throw AssertionError("Timed out: $description", e) }
    }

    @Test fun codexTwoAgentsQueueAndSteer() = runBlocking {
        val url = System.getenv("LUMA_LIVE_CODEX_URL")
        assumeTrue("Explicit isolated live launcher required", url != null)
        val work = System.getenv("LUMA_LIVE_CODEX_WORK")!!
        val events = CopyOnWriteArrayList<JSONObject>()
        var parent = ""
        val agents = CodexSubAgents { parent }
        val rpc = CodexRpc(url!!, System.getenv("LUMA_LIVE_CODEX_TOKEN"), { events.add(it) }, {})
        var seen = 0
        fun drain() {
            val snapshot = events.toList()
            for (event in snapshot.drop(seen)) {
                agents.event(event)
                val p = event.optJSONObject("params") ?: continue
                if (p.optString("threadId") == parent) p.optJSONObject("item")?.let { agents.item(it, p.optString("turnId")) }
            }
            seen = snapshot.size
        }
        fun agentJson() = JSONArray(agents.values().map { a -> obj("id" to a.id, "name" to a.name, "role" to a.role,
            "turnId" to a.turnId, "itemId" to a.itemId, "task" to a.task, "status" to a.status, "nativeStatus" to a.nativeStatus, "detail" to a.detail) })
        fun input(text: String) = JSONArray().put(obj("type" to "text", "text" to text))
        try {
            rpc.connect()
            parent = rpc.request("thread/start", obj("cwd" to work, "model" to model, "approvalPolicy" to "never", "sandbox" to "danger-full-access",
                "historyMode" to "paginated", "config" to obj("features.multi_agent" to true)))
                .getJSONObject("thread").getString("id")
            rpc.request("thread/section/move", obj("threadId" to parent, "sectionId" to JSONObject.NULL))
            rpc.request("thread/resume", obj("threadId" to parent, "excludeTurns" to true))
            rpc.request("thread/queue/add", obj("threadId" to parent, "clientUserMessageId" to fresh("luma-agents-main-"), "input" to input(
                "This is an isolated native multi-agent acceptance test. Spawn exactly TWO native subagents now, without forked context, using the configured mimo-v2.6-flash model/provider. " +
                "Give one this task: work only in $work; use the shell command tool exactly once with login=false to run sleep 12; printf LUMA_CHILD_A; then reply only LUMA_CHILD_A_DONE. " +
                "Give the second the same restrictions with sleep 12; printf LUMA_CHILD_B and reply LUMA_CHILD_B_DONE. " +
                "Both children must run concurrently. Never spawn a third agent. No reading files, environment, configuration, login, network, or other directories. " +
                "Do not use shell yourself. After both spawns, wait for both agents to finish, then reply briefly LUMA_PARENT_DONE. Do not close them. Keep all output short.")))
            await("two native child agents concurrently running", 120_000) {
                drain()
                check(agents.values().size <= 2) { "Unexpected additional delegated agent" }
                check(events.none { it.optString("method") == "turn/completed" && it.optJSONObject("params")?.optString("threadId") == parent }) { "Parent ended before two native children were running" }
                agents.values().count { it.status == "running" } == 2
            }
            for (a in agents.values()) rpc.request("thread/read", obj("threadId" to a.id, "includeTurns" to false)).optJSONObject("thread")?.let(agents::metadata)
            save("codex-agents-concurrent.json", agentJson().toString(2))
            val turn = events.first { it.optString("method") == "turn/started" && it.optJSONObject("params")?.optString("threadId") == parent }
                .getJSONObject("params").getJSONObject("turn").getString("id")
            val queuedClient = fresh("luma-agents-queued-")
            rpc.request("thread/queue/add", obj("threadId" to parent, "clientUserMessageId" to queuedClient,
                "input" to input("Continue waiting for exactly the same two children. Include LUMA_STEER_CONFIRMED in your final short reply. No extra agents or tools.")))
            val queue = rpc.request("thread/queue/list", obj("threadId" to parent)).getJSONArray("data")
            val row = queue.objects().single { it.optString("clientUserMessageId") == queuedClient }
            drain()
            assertEquals(2, agents.values().size)
            save("codex-agents-with-queue.json", obj("agents" to agentJson(), "queue" to queue).toString(2))
            rpc.request("thread/queue/delete", obj("threadId" to parent, "queuedSubmissionId" to row.getString("id")))
            val steerClient = fresh("luma-agents-steer-")
            rpc.request("turn/steer", obj("threadId" to parent, "expectedTurnId" to turn, "clientUserMessageId" to steerClient, "input" to row.getJSONArray("input")))
            assertFalse(rpc.request("thread/queue/list", obj("threadId" to parent)).getJSONArray("data").objects().any { it.optString("id") == row.getString("id") })
            await("native child and parent settlement", 180_000) {
                drain()
                check(agents.values().none { it.status == "failed" || it.status == "cancelled" }) { "Native child failed: ${agents.values()}" }
                agents.values().size == 2 && agents.values().all { it.status == "succeeded" } &&
                    events.any { it.optString("method") == "turn/completed" && it.optJSONObject("params")?.optString("threadId") == parent }
            }
            val completed = events.last { it.optString("method") == "turn/completed" && it.optJSONObject("params")?.optString("threadId") == parent }
                .getJSONObject("params").getJSONObject("turn")
            assertEquals(completed.toString(), "completed", completed.optString("status"))
            val rows = rpc.request("thread/items/list", obj("threadId" to parent, "sortDirection" to "asc", "limit" to 100)).getJSONArray("data")
            save("codex-agents-items.json", rows.toString(2))
            assertEquals(1, rows.objects().count { CodexProjection.clientMessageId(it.getJSONObject("item")) == steerClient })
            assertFalse(rows.objects().any { CodexProjection.clientMessageId(it.getJSONObject("item")) == queuedClient })
            assertTrue(rows.objects().any { it.getJSONObject("item").optString("type") == "agentMessage" && it.getJSONObject("item").optString("text").contains("LUMA_STEER_CONFIRMED") })
            val before = agents.values()
            rpc.close()
            val next = CodexRpc(url, System.getenv("LUMA_LIVE_CODEX_TOKEN"), {}, {})
            try {
                next.connect()
                assertEquals(parent, next.request("thread/resume", obj("threadId" to parent, "excludeTurns" to true)).getJSONObject("thread").getString("id"))
                val restored = CodexSubAgents { parent }
                val history = next.request("thread/items/list", obj("threadId" to parent, "sortDirection" to "asc", "limit" to 100)).getJSONArray("data")
                history.objects().forEach { restored.item(it.getJSONObject("item"), it.optString("turnId")) }
                for (agent in restored.values()) {
                    next.request("thread/read", obj("threadId" to agent.id, "includeTurns" to false)).optJSONObject("thread")?.let(restored::metadata)
                    val childTurn = next.request("thread/turns/list", obj("threadId" to agent.id, "limit" to 1, "sortDirection" to "desc", "itemsView" to "notLoaded")).getJSONArray("data").getJSONObject(0)
                    restored.event(obj("method" to "turn/completed", "params" to obj("threadId" to agent.id, "turn" to childTurn)))
                }
                assertEquals(before.map { it.id }.toSet(), restored.values().map { it.id }.toSet())
                assertTrue(restored.values().all { it.status == "succeeded" })
                assertTrue(restored.values().all { it.turnId == turn && it.itemId.isNotBlank() })
                save("codex-agents-result.json", obj("outcome" to "passed", "model" to model, "parentThreadId" to parent,
                    "agents" to agentJson(), "queueThenSteerNativeAcknowledged" to true, "sameIdReconnect" to true).toString(2))
            } finally { next.close() }
        } finally {
            save("codex-agents-events.json", JSONArray(events).toString(2))
            save("codex-agents-last-projection.json", agentJson().toString(2))
            rpc.close()
        }
    }

    @Test fun codexTwoAgentsShortRepliesAndReconnect() = runBlocking {
        val url = System.getenv("LUMA_LIVE_CODEX_URL")
        assumeTrue("Explicit isolated live launcher required", url != null)
        val work = System.getenv("LUMA_LIVE_CODEX_WORK")!!
        val events = CopyOnWriteArrayList<JSONObject>()
        var parent = ""
        val agents = CodexSubAgents { parent }
        val rpc = CodexRpc(url!!, System.getenv("LUMA_LIVE_CODEX_TOKEN"), { events.add(it) }, {})
        var seen = 0
        fun drain() {
            val copy = events.toList()
            for (event in copy.drop(seen)) {
                agents.event(event)
                val p = event.optJSONObject("params") ?: continue
                if (p.optString("threadId") == parent) p.optJSONObject("item")?.let { agents.item(it, p.optString("turnId")) }
            }
            seen = copy.size
        }
        fun asJson() = JSONArray(agents.values().map { a -> obj("id" to a.id, "name" to a.name, "role" to a.role, "itemId" to a.itemId,
            "turnId" to a.turnId, "status" to a.status, "nativeStatus" to a.nativeStatus, "task" to a.task, "detail" to a.detail) })
        try {
            rpc.connect()
            parent = rpc.request("thread/start", obj("cwd" to work, "model" to model, "approvalPolicy" to "never", "sandbox" to "danger-full-access",
                "historyMode" to "paginated", "config" to obj("features.multi_agent" to true))).getJSONObject("thread").getString("id")
            rpc.request("thread/section/move", obj("threadId" to parent, "sectionId" to JSONObject.NULL))
            rpc.request("thread/resume", obj("threadId" to parent, "excludeTurns" to true))
            rpc.request("thread/queue/add", obj("threadId" to parent, "clientUserMessageId" to fresh("luma-short-"), "input" to JSONArray().put(obj("type" to "text", "text" to
                "Use the native spawn-agent tool exactly TWICE now, without forked context, using only the configured mimo-v2.6-flash model/provider. First child's entire task: Reply exactly LUMA_SHORT_A. Second child's entire task: Reply exactly LUMA_SHORT_B. Both children must answer directly with NO tools, file/environment/config/login/network reads, or delegation. No third child. After launching both, use the native wait tool until both finish. Do not close agents. Then reply LUMA_SHORT_PARENT_DONE. Keep output minimal; do not use any other tools."))))
            await("two short native child replies and parent settlement", 150_000) {
                drain()
                check(agents.values().size <= 2) { "Unexpected extra native child" }
                check(agents.values().none { it.status in listOf("failed", "cancelled") }) { "Native short child failure: ${agents.values()}" }
                agents.values().size == 2 && agents.values().all { it.status == "succeeded" } &&
                    events.any { it.optString("method") == "turn/completed" && it.optJSONObject("params")?.optString("threadId") == parent }
            }
            for (a in agents.values()) rpc.request("thread/read", obj("threadId" to a.id, "includeTurns" to false)).optJSONObject("thread")?.let(agents::metadata)
            val history = rpc.request("thread/items/list", obj("threadId" to parent, "sortDirection" to "asc", "limit" to 100)).getJSONArray("data")
            save("codex-short-items.json", history.toString(2))
            val parentTurn = events.last { it.optString("method") == "turn/completed" && it.optJSONObject("params")?.optString("threadId") == parent }.getJSONObject("params").getJSONObject("turn")
            assertEquals("completed", parentTurn.optString("status"))
            rpc.close()
            val next = CodexRpc(url, System.getenv("LUMA_LIVE_CODEX_TOKEN"), {}, {})
            try {
                next.connect()
                assertEquals(parent, next.request("thread/resume", obj("threadId" to parent, "excludeTurns" to true)).getJSONObject("thread").getString("id"))
                val restored = CodexSubAgents { parent }
                next.request("thread/items/list", obj("threadId" to parent, "sortDirection" to "asc", "limit" to 100)).getJSONArray("data").objects()
                    .forEach { restored.item(it.getJSONObject("item"), it.optString("turnId")) }
                for (a in restored.values()) {
                    next.request("thread/read", obj("threadId" to a.id, "includeTurns" to false)).optJSONObject("thread")?.let(restored::metadata)
                    val t = next.request("thread/turns/list", obj("threadId" to a.id, "limit" to 1, "sortDirection" to "desc", "itemsView" to "notLoaded")).getJSONArray("data").getJSONObject(0)
                    restored.event(obj("method" to "turn/completed", "params" to obj("threadId" to a.id, "turn" to t)))
                }
                assertEquals(agents.values().map { it.id }.toSet(), restored.values().map { it.id }.toSet())
                assertTrue(restored.values().all { it.status == "succeeded" && it.itemId.isNotBlank() && it.turnId.isNotBlank() })
                save("codex-short-result.json", obj("outcome" to "passed", "model" to model, "parentThreadId" to parent,
                    "agents" to asJson(), "sameIdReconnect" to true).toString(2))
            } finally { next.close() }
        } finally {
            save("codex-short-events.json", JSONArray(events).toString(2)); save("codex-short-projection.json", asJson().toString(2)); rpc.close()
        }
    }

    @Test fun opencodeNativeSubagentLifecycle() = runBlocking {
        val url = System.getenv("LUMA_LIVE_OPENCODE_URL")
        assumeTrue("Explicit isolated live launcher required", url != null)
        val work = System.getenv("LUMA_LIVE_OPENCODE_WORK")!!
        val password = System.getenv("LUMA_LIVE_OPENCODE_PASSWORD")!!
        val selected = "luma-mimo/$model"
        val events = CopyOnWriteArrayList<JSONObject>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val api = OpenCodeClient(url!!, password)
        val id = fresh("ses_"); val root = "/api/session/$id"
        try {
            api.subscribe(scope, { events.add(it) }, {})
            api.call("/api/session", "POST", obj("id" to id, "model" to OpenCodeProjection.modelRef(selected),
                "location" to obj("directory" to work), "permissions" to JSONArray().put(obj("action" to "subagent", "resource" to "*", "effect" to "ask"))))
            api.call(root + "/model", "POST", obj("model" to OpenCodeProjection.modelRef(selected, "non-thinking")))
            api.call(root + "/prompt", "POST", obj("id" to fresh("msg_"), "delivery" to "queue", "text" to
                "Use your native subagent tool exactly ONCE, choosing an available non-primary agent and description=Lifecycle check. Child prompt: Reply exactly LUMA_OPENCODE_CHILD_DONE; no tools, files, environment, configuration, network, or further delegation. Use only the current configured mimo-v2.6-flash model/provider for both parent and child. Wait for this one child to finish, then reply LUMA_PARENT_DONE. Do not use any other tool or create another child. This is an isolated lifecycle acceptance test."))
            var history = emptyList<JSONObject>()
            await("OpenCode single native child terminal metadata", 150_000) {
                for (p in api.call(root + "/permission").getJSONArray("data").objects()) {
                    check(p.optString("action") == "subagent") { "Unexpected permission outside native child" }
                    api.call(root + "/permission/${p.getString("id")}/reply", "POST", obj("decision" to "once"))
                }
                history = api.call(root + "/message?order=asc&limit=100").getJSONArray("data").objects()
                val children = OpenCodeProjection.subAgents(history)
                check(children.size <= 1) { "Unexpected extra delegated agent" }
                children.size == 1 && children.single().status == "succeeded" && !api.call("/api/session/active").getJSONObject("data").has(id)
            }
            val child = OpenCodeProjection.subAgents(history).single()
            val metadata = api.call("/api/session/${child.id}").getJSONObject("data")
            assertEquals(id, metadata.getString("parentID"))
            assertEquals("succeeded", metadata.getString("outcome"))
            assertEquals(selected, OpenCodeProjection.modelKey(metadata.getJSONObject("model")))
            save("opencode-subagent-messages.json", JSONArray(history).toString(2))
            save("opencode-subagent-native-child.json", metadata.toString(2))
            save("opencode-subagent-result.json", obj("outcome" to "passed", "model" to selected, "parentSessionId" to id,
                "childSessionId" to child.id, "itemId" to child.itemId, "agentStatus" to child.status).toString(2))
        } finally {
            save("opencode-subagent-events.json", JSONArray(events).toString(2))
            api.close(); scope.cancel()
        }
    }

    @Test fun codexOfficialResponsesCompatibility() = runBlocking {
        val url = System.getenv("LUMA_LIVE_CODEX_URL")
        assumeTrue("Explicit isolated live launcher required", url != null)
        val work = System.getenv("LUMA_LIVE_CODEX_WORK")!!
        val events = CopyOnWriteArrayList<JSONObject>()
        val rpc = CodexRpc(url!!, System.getenv("LUMA_LIVE_CODEX_TOKEN"), { events.add(it) }, {})
        var id = ""
        try {
            rpc.connect()
            val started = rpc.request("thread/start", obj("cwd" to work, "model" to model, "approvalPolicy" to "never", "sandbox" to "danger-full-access", "historyMode" to "paginated"))
            save("codex-thread-start.json", started.toString(2))
            id = started.getJSONObject("thread").getString("id")
            rpc.request("thread/section/move", obj("threadId" to id, "sectionId" to JSONObject.NULL))
            val resumed = rpc.request("thread/resume", obj("threadId" to id, "excludeTurns" to true))
            assertEquals(id, resumed.getJSONObject("thread").getString("id"))
            val msg = fresh("luma-live-")
            rpc.request("thread/queue/add", obj("threadId" to id, "clientUserMessageId" to msg,
                "input" to JSONArray().put(obj("type" to "text", "text" to "Reply exactly LUMA_CODEX_MIMO_OK. Do not use tools."))))
            await("Codex native completed/failed turn", 90_000) { events.any { it.optString("method") == "turn/completed" } }
            val complete = events.last { it.optString("method") == "turn/completed" }.getJSONObject("params").getJSONObject("turn")
            val status = complete.getString("status")
            val items = rpc.request("thread/items/list", obj("threadId" to id, "sortDirection" to "asc", "limit" to 100))
            save("codex-items.json", items.toString(2))
            assertEquals(complete.toString(), "completed", status)
            assertTrue(events.any { it.optString("method") == "item/agentMessage/delta" })
            assertTrue(items.toString().contains("LUMA_CODEX_MIMO_OK"))
            save("codex-result.json", obj("outcome" to "official-responses-inference-succeeded", "model" to model, "threadId" to id, "status" to status).toString(2))
            rpc.close()
            val next = CodexRpc(url, System.getenv("LUMA_LIVE_CODEX_TOKEN"), {}, {})
            try {
                next.connect()
                assertEquals(id, next.request("thread/resume", obj("threadId" to id, "excludeTurns" to true)).getJSONObject("thread").getString("id"))
                assertEquals(items.getJSONArray("data").length(), next.request("thread/items/list", obj("threadId" to id, "sortDirection" to "asc", "limit" to 100)).getJSONArray("data").length())
            } finally { next.close() }
        } finally {
            save("codex-events.json", JSONArray(events).toString(2))
            rpc.close()
        }
    }

    @Test fun opencodeStreamToolsApprovalQueueStopAndRestart() = runBlocking {
        val url = System.getenv("LUMA_LIVE_OPENCODE_URL")
        assumeTrue("Explicit isolated live launcher required", url != null)
        val work = File(System.getenv("LUMA_LIVE_OPENCODE_WORK")!!)
        val hostWork = File(System.getenv("LUMA_LIVE_OPENCODE_HOST_WORK") ?: work.path)
        val password = System.getenv("LUMA_LIVE_OPENCODE_PASSWORD")!!
        val selected = System.getenv("LUMA_LIVE_OPENCODE_MODEL") ?: "luma-mimo/$model"
        val events = CopyOnWriteArrayList<JSONObject>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var api = OpenCodeClient(url!!, password)
        val id = fresh("ses_")
        val root = "/api/session/$id"
        try {
            api.subscribe(scope, { events.add(it) }, {})
            val catalog = api.call("/api/model").getJSONArray("data")
            val options = OpenCodeProjection.models(catalog)
            assertTrue(options.any { it.id == selected })
            save("opencode-selected-catalog.json", JSONArray(options.filter { it.id == selected }.map {
                obj("id" to it.id, "name" to it.label, "efforts" to JSONArray(it.efforts), "supportsImages" to it.supportsImages)
            }).toString(2))
            api.call("/api/session", "POST", obj("id" to id, "title" to "Luma MiMo live isolated fixture", "model" to OpenCodeProjection.modelRef(selected),
                "location" to obj("directory" to work.path), "permissions" to JSONArray().put(obj("action" to "shell", "resource" to "*", "effect" to "ask"))))
            val variant = options.first { it.id == selected }.efforts.firstOrNull { it == "non-thinking" }.orEmpty()
            api.call(root + "/model", "POST", obj("model" to OpenCodeProjection.modelRef(selected, variant)))
            val nativeModel = api.call(root).getJSONObject("data").getJSONObject("model")
            assertEquals(selected, OpenCodeProjection.modelKey(nativeModel))
            if (variant.isNotBlank()) assertEquals(variant, nativeModel.getString("variant"))
            save("opencode-model.json", nativeModel.toString(2))
            val main = fresh("msg_")
            api.call(root + "/prompt", "POST", obj("id" to main, "delivery" to "queue", "text" to
                "Isolated integration task. Use the read tool to read ${work.path}/seed.txt. Use the write tool to create ${work.path}/result.txt containing exactly LUMA_MIMO_FILE_OK followed by newline. Use the shell tool to execute printf LUMA_MIMO_COMMAND_OK in this directory. Do not inspect any other directory, environment variables, login or configuration. Do not delegate. After the three tools succeed reply briefly LUMA_MAIN_DONE."))
            await("OpenCode shell permission", 120_000) {
                checkFailure(events, id)
                api.call(root + "/permission").getJSONArray("data").length() > 0
            }
            val pending = api.call(root + "/permission").getJSONArray("data").getJSONObject(0)
            assertEquals("shell", pending.getString("action"))
            assertEquals(listOf("once", "always", "reject"), OpenCodeProjection.permission(pending).choices.map { it.id })
            save("opencode-permission.json", pending.toString(2))
            val cancel = fresh("msg_"); val steer = fresh("msg_"); val queued = fresh("msg_")
            api.call(root + "/prompt", "POST", obj("id" to cancel, "delivery" to "queue", "text" to "This fixture item will be canceled. Do not execute it."))
            api.call(root + "/prompt", "POST", obj("id" to steer, "delivery" to "queue", "text" to "Continue the original three-tool task. Include LUMA_STEER_DONE in the final short reply."))
            api.call(root + "/prompt", "POST", obj("id" to queued, "delivery" to "queue", "text" to "Reply only LUMA_QUEUE_DONE. No tools."))
            val inbox = api.call(root + "/inbox").getJSONArray("data")
            assertTrue(inbox.objects().any { it.getString("id") == cancel })
            assertTrue(OpenCodeProjection.queue(inbox).all { !it.canEdit })
            api.call(root + "/inbox/$cancel", "DELETE")
            api.call(root + "/inbox/$steer", "PATCH", obj("delivery" to "steer"))
            assertFalse(api.call(root + "/inbox").getJSONArray("data").objects().any { it.getString("id") == cancel })
            api.call(root + "/permission/${pending.getString("id")}/reply", "POST", obj("decision" to "once"))
            await("OpenCode tools and queued turn", 180_000) {
                checkFailure(events, id)
                val messages = api.call(root + "/message?order=asc&limit=100").getJSONArray("data")
                val extra = api.call(root + "/permission").getJSONArray("data")
                for (p in extra.objects()) {
                    check(p.toString().contains("LUMA_MIMO_COMMAND_OK")) { "Unexpected extra permission: $p" }
                    api.call(root + "/permission/${p.getString("id")}/reply", "POST", obj("decision" to "once"))
                }
                messages.toString().contains("LUMA_QUEUE_DONE") && !api.call("/api/session/active").getJSONObject("data").has(id)
            }
            val messages = api.call(root + "/message?order=asc&limit=100").getJSONArray("data")
            save("opencode-messages.json", messages.toString(2))
            val projected = messages.objects().flatMap(OpenCodeProjection::message)
            assertTrue(events.any { it.optString("type") == "session.text.delta" && it.optJSONObject("data")?.optString("sessionID") == id })
            for (tool in listOf("read", "write", "shell")) assertTrue("Missing successful $tool", projected.any { it.toolName == tool && it.outcome == "succeeded" })
            assertEquals("LUMA_MIMO_FILE_OK\n", File(hostWork, "result.txt").readText())
            assertTrue(projected.any { it.role == "tool" && it.detail.contains("LUMA_MIMO_COMMAND_OK") })
            assertTrue(projected.any { it.role == "assistant" && it.text.contains("LUMA_STEER_DONE") })
            assertFalse(messages.objects().any { it.getString("id") == cancel })
            assertTrue(messages.objects().any { it.getString("id") == steer })
            assertTrue(messages.objects().any { it.getString("id") == queued })
            val mark = events.size
            api.call(root + "/prompt", "POST", obj("id" to fresh("msg_"), "text" to "Use only shell to run sleep 20; printf LUMA_SHOULD_NOT_FINISH. Do not delegate or use other tools. This is a cancellation fixture.", "delivery" to "queue"))
            await("OpenCode cancellation permission") { checkFailure(events.drop(mark), id); api.call(root + "/permission").getJSONArray("data").length() > 0 }
            val stoppingPermission = api.call(root + "/permission").getJSONArray("data").getJSONObject(0)
            api.call(root + "/permission/${stoppingPermission.getString("id")}/reply", "POST", obj("decision" to "once"))
            await("OpenCode running sleep command", 10_000) {
                api.call(root + "/message?order=asc&limit=100").getJSONArray("data").objects().flatMap(OpenCodeProjection::message)
                    .any { it.toolName == "shell" && it.outcome == "running" && it.input.contains("sleep 20") }
            }
            val interrupted = api.call(root + "/interrupt", "POST")
            save("opencode-interrupt.json", interrupted.toString(2))
            await("native OpenCode idle after stop", 15_000) { !api.call("/api/session/active").getJSONObject("data").has(id) }
            assertTrue(interrupted.getBoolean("interrupted"))
            val stopped = api.call(root + "/message?order=asc&limit=100").getJSONArray("data")
            assertFalse(stopped.objects().flatMap(OpenCodeProjection::message).any { it.toolName == "shell" && it.outcome == "succeeded" && it.detail.contains("LUMA_SHOULD_NOT_FINISH") })
            save("opencode-stopped-messages.json", stopped.toString(2))
            val before = stopped.objects().map { it.getString("id") }
            api.close()
            val restart = System.getenv("LUMA_LIVE_OPENCODE_RESTART_DIR")
            if (restart != null) {
                File(restart, "restart.request").writeText(id)
                await("self-owned OpenCode process restart", 60_000) { File(restart, "restart.done").isFile }
            }
            api = OpenCodeClient(url, password)
            assertEquals(id, api.call(root).getJSONObject("data").getString("id"))
            assertEquals(before, api.call(root + "/message?order=asc&limit=100").getJSONArray("data").objects().map { it.getString("id") })
            assertEquals(selected, OpenCodeProjection.modelKey(api.call(root).getJSONObject("data").getJSONObject("model")))
            save("opencode-result.json", obj("outcome" to "passed", "model" to selected, "variant" to variant, "sessionId" to id,
                "streaming" to true, "tools" to JSONArray(listOf("read", "write", "shell")), "approval" to "native once",
                "queueCancelAndSteer" to true, "stopRunningCommand" to true, "sameIdReconnect" to true, "processRestart" to (restart != null),
                "messagesAfterRestart" to before.size).toString(2))
        } finally {
            save("opencode-events.json", JSONArray(events).toString(2))
            api.close(); scope.cancel()
        }
    }

    @Test fun codexToolsSettingsApprovalQueueStopAndRestart() = runBlocking {
        val url = System.getenv("LUMA_LIVE_CODEX_URL")
        assumeTrue("Explicit isolated live launcher required", url != null)
        val work = File(System.getenv("LUMA_LIVE_CODEX_WORK")!!)
        val hostWork = File(System.getenv("LUMA_LIVE_CODEX_HOST_WORK") ?: work.path)
        val events = CopyOnWriteArrayList<JSONObject>()
        val answered = mutableSetOf<String>()
        var approvalCount = 0
        var rpc = CodexRpc(url!!, System.getenv("LUMA_LIVE_CODEX_TOKEN"), { events.add(it) }, {})
        var id = ""
        fun input(text: String) = JSONArray().put(obj("type" to "text", "text" to text))
        fun approve() {
            for (event in events.filter { it.has("id") && it.optString("method").endsWith("requestApproval") }) {
                val key = requestKey(event.get("id"))
                if (!answered.add(key)) continue
                check(event.getJSONObject("params").optString("threadId") == id)
                check(event.optString("method") in listOf("item/commandExecution/requestApproval", "item/fileChange/requestApproval")) { "Unexpected approval $event" }
                rpc.respond(event.get("id"), obj("decision" to "accept")); approvalCount++
            }
        }
        fun completed(from: Int = 0) = events.drop(from).filter { it.optString("method") == "turn/completed" }.map { it.getJSONObject("params").getJSONObject("turn") }
        try {
            rpc.connect()
            val started = rpc.request("thread/start", obj("cwd" to work.path, "model" to model, "approvalPolicy" to "untrusted", "sandbox" to "danger-full-access", "historyMode" to "paginated"))
            id = started.getJSONObject("thread").getString("id")
            save("codex-tools-thread-start.json", started.toString(2))
            rpc.request("thread/section/move", obj("threadId" to id, "sectionId" to JSONObject.NULL))
            rpc.request("thread/resume", obj("threadId" to id, "excludeTurns" to true))
            save("codex-permission-profiles.json", rpc.request("permissionProfile/list").toString(2))
            rpc.request("thread/settings/update", obj("threadId" to id, "model" to model, "serviceTier" to JSONObject.NULL))
            await("Codex native settings event", 15_000) { events.any { it.optString("method") == "thread/settings/updated" } }
            val settings = events.last { it.optString("method") == "thread/settings/updated" }
            save("codex-settings.json", settings.toString(2))
            assertEquals(model, settings.getJSONObject("params").getJSONObject("threadSettings").getString("model"))
            rpc.request("thread/queue/add", obj("threadId" to id, "clientUserMessageId" to fresh("luma-live-"), "input" to input(
                "Isolated native integration task. First run exactly cat seed.txt; sleep 5; printf LUMA_CODEX_COMMAND_OK using the shell command tool, with login=false. Then use apply_patch to create result.txt containing exactly LUMA_CODEX_FILE_OK followed by newline. Work only in ${work.path}. Do not read any other directory, environment variables, login or configuration. Do not delegate. After tools succeed reply briefly LUMA_CODEX_MAIN_DONE.")))
            await("Codex native command or approval") { events.any { it.optString("method").endsWith("requestApproval") || (it.optString("method") == "item/started" && it.optJSONObject("params")?.optJSONObject("item")?.optString("type") == "commandExecution") } }
            val turn = events.first { it.optString("method") == "turn/started" }.getJSONObject("params").getJSONObject("turn").getString("id")
            suspend fun add(text: String): JSONObject = rpc.request("thread/queue/add", obj("threadId" to id, "clientUserMessageId" to fresh("luma-live-"), "input" to input(text)))
            add("Reply only LUMA_CODEX_QUEUE_BEFORE. No tools.")
            add("Canceled isolated fixture item. Do not execute.")
            add("Continue the original task and include LUMA_CODEX_STEER_DONE in its final short reply.")
            val queue = rpc.request("thread/queue/list", obj("threadId" to id)).getJSONArray("data")
            assertEquals(3, queue.length())
            save("codex-queue-before.json", queue.toString(2))
            rpc.request("thread/queue/update", obj("threadId" to id, "queuedSubmissionId" to queue.getJSONObject(0).getString("id"), "input" to input("Reply only LUMA_CODEX_QUEUE_DONE. No tools.")))
            rpc.request("thread/queue/delete", obj("threadId" to id, "queuedSubmissionId" to queue.getJSONObject(1).getString("id")))
            rpc.request("thread/queue/delete", obj("threadId" to id, "queuedSubmissionId" to queue.getJSONObject(2).getString("id")))
            rpc.request("turn/steer", obj("threadId" to id, "expectedTurnId" to turn, "clientUserMessageId" to fresh("luma-live-"), "input" to queue.getJSONObject(2).getJSONArray("input")))
            await("Codex actual tools and queued model reply", 150_000) {
                approve()
                check(completed().none { it.optString("status") == "failed" }) { "Codex real tool turn failed: ${completed()}" }
                completed().size >= 2
            }
            val items = rpc.request("thread/items/list", obj("threadId" to id, "sortDirection" to "asc", "limit" to 100)).getJSONArray("data")
            save("codex-tools-items.json", items.toString(2))
            val projected = items.objects().map { CodexProjection.item(it.getJSONObject("item")) }
            assertTrue(projected.any { it.kind == "commandExecution" && it.outcome == "succeeded" && it.detail.contains("LUMA_CODEX_COMMAND_OK") && it.detail.contains("LUMA_MIMO_SEED_ONLY") })
            assertTrue(projected.any { it.kind == "fileChange" && it.outcome == "succeeded" })
            assertEquals("LUMA_CODEX_FILE_OK\n", File(hostWork, "result.txt").readText())
            assertTrue(projected.any { it.role == "assistant" && it.text.contains("LUMA_CODEX_STEER_DONE") })
            assertTrue(projected.any { it.role == "assistant" && it.text.contains("LUMA_CODEX_QUEUE_DONE") })
            assertTrue("Native untrusted policy must actually ask", approvalCount > 0)
            val mark = events.size
            add("Use only the shell command tool to run sleep 20; printf LUMA_CODEX_SHOULD_NOT_FINISH with login=false. Do not delegate. This is a cancellation fixture.")
            await("Codex running native sleep command") {
                approve()
                events.drop(mark).any { it.optString("method") == "item/started" && it.optJSONObject("params")?.optJSONObject("item")?.let { item -> item.optString("type") == "commandExecution" && item.optString("command").contains("sleep 20") } == true }
            }
            val stopTurn = events.drop(mark).first { it.optString("method") == "turn/started" }.getJSONObject("params").getJSONObject("turn").getString("id")
            rpc.request("turn/interrupt", obj("threadId" to id, "turnId" to stopTurn))
            await("Codex native interrupted turn", 15_000) { completed(mark).any { it.optString("status") == "interrupted" } }
            val before = rpc.request("thread/items/list", obj("threadId" to id, "sortDirection" to "asc", "limit" to 100)).getJSONArray("data")
            save("codex-tools-stopped-items.json", before.toString(2))
            rpc.close()
            val restart = System.getenv("LUMA_LIVE_CODEX_RESTART_DIR")
            if (restart != null) {
                File(restart, "restart.request").writeText(id)
                await("self-owned Codex process restart", 60_000) { File(restart, "restart.done").isFile }
            }
            rpc = CodexRpc(url, System.getenv("LUMA_LIVE_CODEX_TOKEN"), {}, {})
            rpc.connect()
            assertEquals(id, rpc.request("thread/resume", obj("threadId" to id, "excludeTurns" to true)).getJSONObject("thread").getString("id"))
            val after = rpc.request("thread/items/list", obj("threadId" to id, "sortDirection" to "asc", "limit" to 100)).getJSONArray("data")
            assertEquals(before.objects().map { it.getJSONObject("item").getString("id") }, after.objects().map { it.getJSONObject("item").getString("id") })
            save("codex-tools-result.json", obj("outcome" to "passed", "model" to model, "threadId" to id, "streaming" to events.any { it.optString("method") == "item/agentMessage/delta" },
                "tools" to JSONArray(listOf("commandExecution-read", "fileChange", "commandExecution-shell")), "nativeApprovals" to approvalCount,
                "settingsConfirmed" to true, "queueEditCancelSteer" to true, "stopRunningCommand" to true, "sameIdReconnect" to true, "processRestart" to (restart != null)).toString(2))
        } finally { save("codex-tools-events.json", JSONArray(events).toString(2)); rpc.close() }
    }

    @Test fun opencodeStoredSessionAfterRestart() = runBlocking {
        val id = System.getenv("LUMA_LIVE_OPENCODE_EXISTING_ID")
        assumeTrue("Only verifies the previously completed isolated paid fixture", id != null)
        val url = System.getenv("LUMA_LIVE_OPENCODE_URL")!!
        val password = System.getenv("LUMA_LIVE_OPENCODE_PASSWORD")!!
        var api = OpenCodeClient(url, password)
        try {
            val root = "/api/session/$id"
            assertEquals(id, api.call(root).getJSONObject("data").getString("id"))
            val before = JSONArray(File(evidence, System.getenv("LUMA_LIVE_OPENCODE_RECOVERY_MESSAGES") ?: "opencode-stopped-messages.json").readText())
            val restart = System.getenv("LUMA_LIVE_OPENCODE_RESTART_DIR")
            if (restart != null) {
                api.close()
                File(restart, "restart.request").writeText(id!!)
                await("OpenCode recovery process restart", 60_000) { File(restart, "restart.done").isFile }
                api = OpenCodeClient(url, password)
                assertEquals(id, api.call(root).getJSONObject("data").getString("id"))
            }
            val after = api.call(root + "/message?order=asc&limit=100").getJSONArray("data")
            assertEquals(before.objects().map { it.getString("id") }, after.objects().map { it.getString("id") })
            assertEquals("luma-mimo/$model", OpenCodeProjection.modelKey(api.call(root).getJSONObject("data").getJSONObject("model")))
            save("opencode-recovery-result.json", obj("outcome" to "passed", "sessionId" to id, "sameIdAfterProcessRestart" to true,
                "nativeHistoryMessageCount" to after.length(), "scope" to "Production-client recovery read, no additional model call").toString(2))
        } finally { api.close() }
    }

    @Test fun codexEnvironmentToolsStreamingAndRestart() = runBlocking {
        val url = System.getenv("LUMA_LIVE_CODEX_URL")
        assumeTrue("Explicit isolated native-environment fixture required", url != null)
        val work = System.getenv("LUMA_LIVE_CODEX_WORK")!!
        val hostWork = File(System.getenv("LUMA_LIVE_CODEX_HOST_WORK") ?: work)
        val events = CopyOnWriteArrayList<JSONObject>()
        var rpc = CodexRpc(url!!, System.getenv("LUMA_LIVE_CODEX_TOKEN"), { events.add(it) }, {})
        try {
            rpc.connect()
            val started = rpc.request("thread/start", obj("cwd" to work, "model" to model, "approvalPolicy" to "never", "sandbox" to "danger-full-access", "historyMode" to "paginated"))
            val id = started.getJSONObject("thread").getString("id")
            save("codex-environment-thread.json", started.toString(2))
            rpc.request("thread/section/move", obj("threadId" to id, "sectionId" to JSONObject.NULL))
            rpc.request("thread/resume", obj("threadId" to id, "excludeTurns" to true))
            rpc.request("thread/queue/add", obj("threadId" to id, "clientUserMessageId" to fresh("luma-live-"), "input" to JSONArray().put(obj("type" to "text", "text" to
                "Isolated integration task in $work. Use the shell command tool with login=false to run cat seed.txt; printf LUMA_CODEX_COMMAND_OK. Then use apply_patch to create result.txt containing exactly LUMA_CODEX_FILE_OK followed by newline. Do not inspect other directories, environment variables, login or configuration. Do not delegate. After tools succeed reply briefly LUMA_CODEX_ENVIRONMENT_DONE."))))
            await("native-environment Codex tool turn", 150_000) { events.any { it.optString("method") == "turn/completed" } }
            val turn = events.last { it.optString("method") == "turn/completed" }.getJSONObject("params").getJSONObject("turn")
            assertEquals(turn.toString(), "completed", turn.getString("status"))
            val items = rpc.request("thread/items/list", obj("threadId" to id, "sortDirection" to "asc", "limit" to 100)).getJSONArray("data")
            save("codex-environment-items.json", items.toString(2))
            val projected = items.objects().map { CodexProjection.item(it.getJSONObject("item")) }
            assertTrue(projected.any { it.kind == "commandExecution" && it.outcome == "succeeded" && it.detail.contains("LUMA_MIMO_SEED_ONLY") && it.detail.contains("LUMA_CODEX_COMMAND_OK") })
            assertTrue(projected.any { it.kind == "fileChange" && it.outcome == "succeeded" })
            assertEquals("LUMA_CODEX_FILE_OK\n", File(hostWork, "result.txt").readText())
            assertTrue(projected.any { it.role == "assistant" && it.text.contains("LUMA_CODEX_ENVIRONMENT_DONE") })
            assertTrue(events.any { it.optString("method") == "item/agentMessage/delta" })
            rpc.close()
            val restart = System.getenv("LUMA_LIVE_CODEX_RESTART_DIR")
            if (restart != null) {
                File(restart, "restart.request").writeText(id)
                await("native-environment Codex process restart", 60_000) { File(restart, "restart.done").isFile }
            }
            rpc = CodexRpc(url, System.getenv("LUMA_LIVE_CODEX_TOKEN"), {}, {})
            rpc.connect()
            val resumed = rpc.request("thread/resume", obj("threadId" to id, "excludeTurns" to true))
            assertEquals(id, resumed.getJSONObject("thread").getString("id"))
            val after = rpc.request("thread/items/list", obj("threadId" to id, "sortDirection" to "asc", "limit" to 100)).getJSONArray("data")
            assertEquals(items.objects().map { it.getJSONObject("item").getString("id") }, after.objects().map { it.getJSONObject("item").getString("id") })
            save("codex-environment-result.json", obj("outcome" to "passed", "threadId" to id, "model" to model, "streaming" to true,
                "tools" to JSONArray(listOf("commandExecution-read", "fileChange", "commandExecution-shell")), "sameIdNativeHistory" to true, "processRestart" to (restart != null)).toString(2))
        } finally { save("codex-environment-events.json", JSONArray(events).toString(2)); rpc.close() }
    }

    @Test fun opencodeEnvironmentToolsStreamingAndRestart() = runBlocking {
        val url = System.getenv("LUMA_LIVE_OPENCODE_URL")
        assumeTrue("Explicit isolated native-environment fixture required", url != null)
        val work = System.getenv("LUMA_LIVE_OPENCODE_WORK")!!
        val hostWork = File(System.getenv("LUMA_LIVE_OPENCODE_HOST_WORK") ?: work)
        val password = System.getenv("LUMA_LIVE_OPENCODE_PASSWORD")!!
        val selected = System.getenv("LUMA_LIVE_OPENCODE_MODEL") ?: "luma-mimo/$model"
        val events = CopyOnWriteArrayList<JSONObject>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var api = OpenCodeClient(url!!, password)
        val id = fresh("ses_"); val root = "/api/session/$id"
        try {
            api.subscribe(scope, { events.add(it) }, {})
            assertTrue(OpenCodeProjection.models(api.call("/api/model").getJSONArray("data")).any { it.id == selected })
            api.call("/api/session", "POST", obj("id" to id, "model" to OpenCodeProjection.modelRef(selected, "non-thinking"),
                "location" to obj("directory" to work), "permissions" to JSONArray().put(obj("action" to "shell", "resource" to "*", "effect" to "allow"))))
            val native = api.call(root).getJSONObject("data")
            assertEquals(selected, OpenCodeProjection.modelKey(native.getJSONObject("model")))
            save("opencode-environment-session.json", native.toString(2))
            api.call(root + "/prompt", "POST", obj("id" to fresh("msg_"), "delivery" to "queue", "text" to
                "Isolated integration task. Use read to read $work/seed.txt. Use write to create $work/result.txt containing exactly LUMA_MIMO_FILE_OK followed by newline. Use shell to execute printf LUMA_MIMO_COMMAND_OK in this directory. Do not inspect other directories, environment variables, login or configuration. Do not delegate. After the three tools succeed reply briefly LUMA_OPENCODE_ENVIRONMENT_DONE."))
            await("native-environment OpenCode tool turn", 150_000) {
                checkFailure(events, id)
                api.call(root + "/message?order=asc&limit=100").getJSONArray("data").objects().flatMap(OpenCodeProjection::message).any { it.role == "assistant" && it.text.contains("LUMA_OPENCODE_ENVIRONMENT_DONE") } &&
                    !api.call("/api/session/active").getJSONObject("data").has(id)
            }
            val messages = api.call(root + "/message?order=asc&limit=100").getJSONArray("data")
            save("opencode-environment-messages.json", messages.toString(2))
            val projected = messages.objects().flatMap(OpenCodeProjection::message)
            for (tool in listOf("read", "write", "shell")) assertTrue("Missing successful native-environment $tool", projected.any { it.toolName == tool && it.outcome == "succeeded" })
            assertTrue(projected.any { it.toolName == "shell" && it.detail.contains("LUMA_MIMO_COMMAND_OK") })
            assertEquals("LUMA_MIMO_FILE_OK\n", File(hostWork, "result.txt").readText())
            assertTrue(events.any { it.optString("type") == "session.text.delta" && it.optJSONObject("data")?.optString("sessionID") == id })
            api.close()
            val restart = System.getenv("LUMA_LIVE_OPENCODE_RESTART_DIR")
            if (restart != null) {
                File(restart, "restart.request").writeText(id)
                await("native-environment OpenCode process restart", 60_000) { File(restart, "restart.done").isFile }
            }
            api = OpenCodeClient(url, password)
            assertEquals(id, api.call(root).getJSONObject("data").getString("id"))
            val after = api.call(root + "/message?order=asc&limit=100").getJSONArray("data")
            assertEquals(messages.objects().map { it.getString("id") }, after.objects().map { it.getString("id") })
            save("opencode-environment-result.json", obj("outcome" to "passed", "sessionId" to id, "model" to selected, "streaming" to true,
                "tools" to JSONArray(listOf("read", "write", "shell")), "sameIdNativeHistory" to true, "processRestart" to (restart != null)).toString(2))
        } finally { save("opencode-environment-events.json", JSONArray(events).toString(2)); api.close(); scope.cancel() }
    }

    private fun checkFailure(events: List<JSONObject>, id: String) {
        val fail = events.firstOrNull { it.optString("type") == "session.execution.failed" && it.optJSONObject("data")?.optString("sessionID") == id }
        check(fail == null) { "Native MiMo execution failed: $fail" }
    }
}
