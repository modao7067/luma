package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.session.ConversationItem
import org.junit.Assert.*
import org.junit.Test

class ProcessCategoryTest {
    private fun native(kind: String) = ConversationItem(kind, "tool", kind = kind, outcome = "succeeded")
    private fun named(name: String) = native("tool").copy(id = name, toolName = name)

    @Test fun categoriesCountNativeInvocationsNotFilesOrCommandGuesses() {
        val rows = listOf(native("commandExecution"), native("commandExecution").copy(id = "cat", text = "cat file", input = "cat file"),
            native("fileChange").copy(input = "a.kt\nb.kt")) + (1..3).map { named("read").copy(id = "read$it") } + native("reasoning")
        assertEquals("6 次 shell、fileChange、read · 1 次 思考", processGroupCaption(rows, "pi"))
    }
    @Test fun codexKindsPreserveTheirNativeMeanings() {
        assertEquals(ProcessCategory.Terminal, processCategory(native("commandExecution")))
        assertEquals(ProcessCategory.Edit, processCategory(native("fileChange")))
        assertEquals(ProcessCategory.WebSearch, processCategory(native("webSearch")))
        assertEquals(ProcessCategory.Image, processCategory(native("imageView")))
        assertEquals(ProcessCategory.Tool, processCategory(native("mcpToolCall").copy(toolName = "read"), "pi"))
    }
    @Test fun acpCategoriesTakePrecedenceOverNamesAndHumanTitles() {
        val kinds = mapOf("execute" to ProcessCategory.Terminal, "read" to ProcessCategory.Read, "edit" to ProcessCategory.Edit,
            "delete" to ProcessCategory.Edit, "move" to ProcessCategory.Edit, "search" to ProcessCategory.Search, "fetch" to ProcessCategory.Browse)
        kinds.forEach { (kind, expected) -> assertEquals(expected, processCategory(named("write").copy(toolKind = kind, text = "read"), "DeepSeek Harness")) }
        listOf("think", "other", "switch_mode", "future_kind").forEach { kind ->
            assertEquals(ProcessCategory.Tool, processCategory(named("read").copy(toolKind = kind), "DeepSeek Harness"))
        }
    }
    @Test fun exactNamesAreScopedToTheActualCli() {
        assertEquals(ProcessCategory.Read, processCategory(named("ls"), "pi"))
        assertEquals(ProcessCategory.Tool, processCategory(named("ls"), "OpenCode"))
        assertEquals(ProcessCategory.Browse, processCategory(named("webfetch"), "OpenCode"))
        assertEquals(ProcessCategory.Tool, processCategory(named("read"), "unknown-cli"))
        assertEquals(ProcessCategory.Terminal, processCategory(named("shell"), "OpenCode"))
        assertEquals(ProcessCategory.Edit, processCategory(named("patch"), "OpenCode"))
        assertEquals(ProcessCategory.Image, processCategory(named("experimental_image_generate"), "OpenCode"))
        assertEquals(ProcessCategory.Tool, processCategory(named("bash"), "OpenCode"))
    }
    @Test fun kimiUsesAcpKindAndClaudeUsesExactNativeToolNames() {
        assertEquals(ProcessCategory.Terminal, processCategory(named("anything").copy(toolKind = "execute"), "Kimi Code"))
        assertEquals(ProcessCategory.Read, processCategory(named("anything").copy(toolKind = "read"), "Kimi Code"))
        mapOf("Bash" to ProcessCategory.Terminal, "Read" to ProcessCategory.Read, "Edit" to ProcessCategory.Edit,
            "Write" to ProcessCategory.Edit, "NotebookEdit" to ProcessCategory.Edit, "Grep" to ProcessCategory.Search,
            "Glob" to ProcessCategory.Search, "WebSearch" to ProcessCategory.WebSearch, "WebFetch" to ProcessCategory.Browse
        ).forEach { (name, expected) -> assertEquals(expected, processCategory(named(name), "Claude Code")) }
        listOf("mcp__server__Read", "mcp__server__Bash", "custom-Read", "read", "Skill", "Agent", "").forEach {
            assertEquals(ProcessCategory.Tool, processCategory(named(it), "Claude Code"))
        }
        assertEquals("Parameters", processInputCaption(named("Bash"), "Claude Code"))
    }
    @Test fun opaqueAndMissingNamesRemainTools() {
        listOf("mcp__server_read", "server_read", "custom-bash", "", "Read").forEach { name ->
            assertEquals(ProcessCategory.Tool, processCategory(named(name).copy(text = "read", input = "cat file"), "pi"))
        }
        assertEquals("执行过程", processGroupCaption(listOf(native("futureEvent"))))
    }
    @Test fun singleEntriesUseTheSameTruthfulSummary() {
        assertEquals("1 次 思考", processGroupCaption(listOf(native("reasoning"))))
        assertEquals("1 次 shell", processGroupCaption(listOf(native("commandExecution"))))
        assertEquals("思考中", processGroupCaption(listOf(native("reasoning").copy(outcome = "running"))))
    }
    @Test fun shellAndOutputLabelsDoNotRelabelNonShellParametersOrDiffs() {
        assertEquals("shell", processEntryLabel(native("commandExecution"), "Codex"))
        assertEquals("Parameters", processInputCaption(native("commandExecution"), "Codex"))
        assertEquals("Parameters", processInputCaption(named("bash"), "pi"))
        assertEquals("Parameters", processInputCaption(named("shell"), "OpenCode"))
        assertEquals("Parameters", processInputCaption(named("read"), "pi"))
        assertEquals("Parameters", processInputCaption(named("").copy(toolKind = "execute"), "DeepSeek Harness"))
        assertEquals("Diff", processOutputCaption(native("fileChange")))
        assertEquals("Output", processOutputCaption(native("commandExecution")))
        assertEquals("Error", processOutputCaption(native("commandExecution").copy(outcome = "failed")))
    }

    @Test fun readAndEditPreviewsParseStructuredFileContentAndUnifiedDiffs() {
        val readItem = named("read_file").copy(
            toolKind = "read",
            input = """{"Path":"/workspace/luma/apps/gui/src/ui/NativeMarkdown.kt","StartLine":180,"EndLine":182}""",
            detail = "Created At: 2026-11-08T16:20:00+08:00\nCompleted At: 2026-11-08T16:20:01+08:00\nShowing lines 180-182 of 674\n180: val a = 1\n181: val b = 2\n182: val c = 3",
        )
        val readPreview = parseProcessReadPreview(readItem, "Antigravity")
        assertEquals("NativeMarkdown.kt", readPreview.fileName)
        assertEquals("src/ui/", readPreview.parentDir)
        assertEquals("kt", readPreview.language)
        assertEquals("L180-182", readPreview.rangeLabel)
        assertEquals("val a = 1\nval b = 2\nval c = 3", readPreview.cleanText)
        assertEquals(listOf(180, 181, 182), readPreview.lines.map { it.lineNumber })

        val editItem = named("edit_file").copy(
            toolKind = "edit",
            input = """{"Path":"/workspace/luma/apps/gui/src/ui/NativeMarkdown.kt","Target":"val a = 1\nval b = 2","Replacement":"val a = 1\nval b = 42\nval c = 3"}""",
            detail = "{}",
        )
        val editPreview = parseProcessEditPreview(editItem, "Antigravity")
        assertEquals("NativeMarkdown.kt", editPreview.fileName)
        assertEquals(2, editPreview.addedCount)
        assertEquals(1, editPreview.removedCount)
        assertTrue(editPreview.diffLines.any { it.kind == DiffLineKind.DELETION && it.text == "val b = 2" })
        assertTrue(editPreview.diffLines.any { it.kind == DiffLineKind.ADDITION && it.text == "val b = 42" })

        val emptyDetailReadItem = readItem.copy(detail = "")
        val emptyPreview = parseProcessReadPreview(emptyDetailReadItem, "Antigravity")
        assertEquals("", emptyPreview.cleanText)
        assertEquals(180, emptyPreview.startLine)
        assertEquals(182, emptyPreview.endLine)
        val rawFileText = (1..200).joinToString("\n") { "line $it" }
        val populated = populateReadPreviewFromText(emptyPreview, rawFileText)
        assertEquals("line 180\nline 181\nline 182", populated.cleanText)
        assertEquals(listOf(180, 181, 182), populated.lines.map { it.lineNumber })
    }

    @Test fun openCodeReadEditPreviewsAndReasoningInterleavedGrouping() {
        val openCodeRead1 = named("read").copy(
            id = "r1",
            input = """{"path":"/workspace/luma/SessionDetail.kt","offset":220,"limit":3}""",
            detail = "Read file /workspace/luma/SessionDetail.kt, lines 220-222\n\n220: val x = 1\n221: val y = 2\n222: val z = 3",
        )
        val rp = parseProcessReadPreview(openCodeRead1, "OpenCode")
        assertEquals("SessionDetail.kt", rp.fileName)
        assertEquals("L220-222", rp.rangeLabel)
        assertEquals("val x = 1\nval y = 2\nval z = 3", rp.cleanText)
        assertEquals(listOf(220, 221, 222), rp.lines.map { it.lineNumber })

        val openCodeDirRead = named("read").copy(
            id = "r2",
            input = """{"path":"/workspace/luma"}""",
            detail = "Read directory /workspace/luma, entries 1-2\n1: apps/\n2: tools/",
        )
        val dp = parseProcessReadPreview(openCodeDirRead, "OpenCode")
        assertEquals("L1-2", dp.rangeLabel)
        assertEquals("apps/\ntools/", dp.cleanText)

        val openCodeEditRunning = named("edit").copy(
            id = "e1",
            outcome = "running",
            input = """{"path":"/workspace/luma/A.kt","oldString":"val a = 1","newString":"val a = 2\nval b = 3"}""",
            detail = "",
        )
        assertTrue(hasProcessEditPreview(openCodeEditRunning))
        val runningEditPreview = parseProcessEditPreview(openCodeEditRunning, "OpenCode")
        assertEquals("A.kt", runningEditPreview.fileName)
        assertEquals(2, runningEditPreview.addedCount)
        assertEquals(1, runningEditPreview.removedCount)

        val openCodeEditCompleted = named("edit").copy(
            id = "e2",
            outcome = "succeeded",
            input = """{"path":"/workspace/luma/B.kt","oldString":"old","newString":"new"}""",
            detail = "Index: luma/B.kt\n===================================================================\n--- luma/B.kt\toriginal\n+++ luma/B.kt\tmodified\n@@ -1251,2 +1251,3 @@\n keep\n-old\n+new\n+extra",
        )
        val completedEditPreview = parseProcessEditPreview(openCodeEditCompleted, "OpenCode")
        assertEquals("B.kt", completedEditPreview.fileName)
        assertEquals(1251, completedEditPreview.diffLines.first().oldLine)
        assertEquals(2, completedEditPreview.addedCount)
        assertEquals(1, completedEditPreview.removedCount)

        val rThink1 = native("reasoning").copy(id = "th1")
        val rThink2 = native("reasoning").copy(id = "th2")
        val rThink3 = native("reasoning").copy(id = "th3")
        val rThink4 = native("reasoning").copy(id = "th4")
        val rThinkTail = native("reasoning").copy(id = "th5")
        val shellCall = named("shell").copy(id = "sh1")
        val interleaved = listOf(rThink1, openCodeRead1, rThink2, openCodeDirRead, rThink3, openCodeEditRunning, rThink4, openCodeEditCompleted, rThinkTail, shellCall)
        val slices = groupProcessRunSlices(interleaved, "OpenCode", null)
        assertEquals(
            listOf(
                ProcessRunSlice.Single(rThink1),
                ProcessRunSlice.Single(rThink2),
                ProcessRunSlice.ReadBatch(listOf(openCodeRead1, openCodeDirRead)),
                ProcessRunSlice.Single(rThink3),
                ProcessRunSlice.Single(rThink4),
                ProcessRunSlice.EditGroup(listOf(openCodeEditRunning, openCodeEditCompleted)),
                ProcessRunSlice.Single(rThinkTail),
                ProcessRunSlice.Single(shellCall),
            ),
            slices,
        )
        val openCodeTruncatedRead = named("read").copy(
            id = "r-trunc",
            input = """{"path":"/workspace/luma/ProcessTimeline.kt","offset":1170,"limit":160}""",
            detail = "Read file /workspace/luma/ProcessTimeline.kt, lines 1170-1172\n1170: val hScroll = rememberScrollState()\n1171: val borderColor = colors.outlineVariant\n1172: }\n[Output truncated. Continue reading with offset: 1173]",
        )
        val tp = parseProcessReadPreview(openCodeTruncatedRead, "OpenCode")
        assertEquals("ProcessTimeline.kt", tp.fileName)
        assertEquals("L1170-1172", tp.rangeLabel)
        assertEquals("val hScroll = rememberScrollState()\nval borderColor = colors.outlineVariant\n}", tp.cleanText)
        assertEquals(listOf(1170, 1171, 1172), tp.lines.map { it.lineNumber })
        assertFalse(tp.cleanText.contains("1170:"))
        assertFalse(tp.cleanText.contains("[Output truncated"))
    }

    @Test fun consecutiveReadsAndEditsAggregateByFileAndPreserveMultiHunkBoundaries() {
        val reads = listOf(
            named("read").copy(id = "r1", input = """{"path":"/w/base.py","offset":1,"limit":50}"""),
            named("read").copy(id = "r2", input = """{"path":"/w/base.py","offset":51,"limit":50}"""),
            named("read").copy(id = "r3", input = """{"path":"/w/base.py","offset":101,"limit":50}"""),
            named("read").copy(id = "r4", input = """{"path":"/w/grok.py","offset":1,"limit":50}"""),
        )
        assertEquals("base.py ×3, grok.py", formatProcessReadBatchSummary(reads, "OpenCode"))

        val e1 = named("edit").copy(
            id = "e1",
            input = """{"path":"/w/cli_bridge/luma_cli/base.py","oldString":"a","newString":"b"}""",
            detail = "--- a/base.py\n+++ b/base.py\n@@ -60,2 +60,3 @@\n keep\n-a\n+b\n+c",
        )
        val e2 = named("edit").copy(
            id = "e2",
            input = """{"path":"/w/cli_bridge/luma_cli/base.py","oldString":"x","newString":"y"}""",
            detail = "--- a/base.py\n+++ b/base.py\n@@ -402,2 +403,2 @@\n-x\n+y\n end",
        )
        val e3MultiHunk = named("edit").copy(
            id = "e3",
            input = """{"path":"/w/gui/session/NativeCliSession.kt"}""",
            detail = "--- a/NativeCliSession.kt\n+++ b/NativeCliSession.kt\n@@ -248,1 +248,2 @@\n+guard\n keep\n@@ -321,2 +322,2 @@\n-oldAction\n+newAction\n tail",
        )
        val merged = mergeConsecutiveProcessEdits(listOf(e1, e2, e3MultiHunk), "OpenCode")
        assertEquals(2, merged.size)

        val first = merged[0]
        assertEquals("e1", first.id)
        assertEquals("base.py", first.fileName)
        assertEquals(2, first.editCount)
        assertEquals(3, first.addedCount)
        assertEquals(2, first.removedCount)
        assertEquals(2, first.hunks.size)
        assertEquals("L403–404 · 第 2/2 处", first.hunks[1].headerLabel)

        val second = merged[1]
        assertEquals("NativeCliSession.kt", second.fileName)
        assertEquals(2, second.editCount)
        assertEquals(2, second.hunks.size)
        assertEquals("L322–323 · 第 2/2 处", second.hunks[1].headerLabel)

        val piRunningEdit = named("edit").copy(
            id = "pi-edit-1",
            outcome = "running",
            input = """{"path":"/w/pi/App.kt","edits":[{"oldText":"val x = 1","newText":"val x = 2"},{"oldText":"val y = 3","newText":"val y = 4\nval z = 5"}]}""",
            detail = "",
        )
        assertTrue(hasProcessEditPreview(piRunningEdit))
        val piEditPreview = parseProcessEditPreview(piRunningEdit, "pi")
        assertEquals("App.kt", piEditPreview.fileName)
        assertEquals(3, piEditPreview.addedCount)
        assertEquals(2, piEditPreview.removedCount)
        assertEquals(2, piEditPreview.hunks.size)

        val piTruncatedRead = named("read").copy(
            id = "pi-read-1",
            input = """{"path":"/w/pi/Large.kt","offset":null,"limit":null}""",
            detail = "line one\nline two\n\n[Showing lines 1-2 of 57 (50.0KB limit). Use offset=3 to continue.]",
        )
        val piReadPreview = parseProcessReadPreview(piTruncatedRead, "pi")
        assertEquals("L1-2", piReadPreview.rangeLabel)
        assertEquals("line one\nline two", piReadPreview.cleanText)

        val piMoreLinesRead = named("read").copy(
            id = "pi-read-2",
            input = """{"path":"/w/pi/More.kt","offset":10,"limit":2}""",
            detail = "alpha\nbeta\n\n[45 more lines in file. Use offset=12 to continue.]",
        )
        assertEquals("alpha\nbeta", parseProcessReadPreview(piMoreLinesRead, "pi").cleanText)

        val vertexThought = ConversationItem(
            "pi-think", "tool", text = "思考",
            detail = "Thinking Process:\n\n1. **Analyze the Request:**\n\n**Assessing the pi setup objectively**\n\nI gathered a complete view.",
            kind = "reasoning", outcome = "succeeded",
        )
        assertEquals("Assessing the pi setup objectively", processEntryContext(vertexThought, "pi"))
    }

    @Test fun largeEditFileUsesPatienceAnchorsSplitsHunksAndExtractsStartLine() {
        val oldLines = (1..520).map { idx -> "val item$idx = $idx" }
        val newLines = oldLines.mapIndexed { idx, line ->
            when (idx) {
                19 -> "val item20 = 2000\nval extra20 = true"
                489 -> "val item490 = 4900"
                else -> line
            }
        }
        val inputJson = org.json.JSONObject()
            .put("Path", "/w/gui/ui/GlobalUsageScreen.kt")
            .put("Target", oldLines.joinToString("\n"))
            .put("Replacement", newLines.joinToString("\n"))
            .toString()
        val editItem = named("edit_file").copy(
            id = "agy-large-edit",
            input = inputJson,
            detail = "Made 1 replacement in /w/gui/ui/GlobalUsageScreen.kt starting at line 100 (+3/-2 lines).",
        )
        val preview = parseProcessEditPreview(editItem, "Antigravity")
        assertTrue(preview.hasRealLineNumbers)
        assertEquals(3, preview.addedCount)
        assertEquals(2, preview.removedCount)
        assertEquals(2, preview.hunks.size)
        val firstDel = preview.hunks[0].first { it.kind == DiffLineKind.DELETION }
        assertEquals(119, firstDel.oldLine)
    }

    @Test fun pathologicalDiffAndBoundedReadPreviewStaySafeWithoutBreakingNormalEdits() {
        val hugeOld = (1..5200).map { idx -> "val line$idx = $idx" }
        val hugeNew = hugeOld.toMutableList().apply {
            this[2600] = "val line2601 = -1"
            add(2601, "val extraLine = true")
        }
        val hugeInput = org.json.JSONObject()
            .put("Path", "/w/gui/ui/HugeFile.kt")
            .put("Target", hugeOld.joinToString("\n"))
            .put("Replacement", hugeNew.joinToString("\n"))
            .toString()
        val hugePreview = parseProcessEditPreview(
            named("edit_file").copy(id = "huge-edit", input = hugeInput, detail = "Made 1 replacement starting at line 10."),
            "Antigravity",
        )
        assertEquals(2, hugePreview.addedCount)
        assertEquals(1, hugePreview.removedCount)
        assertEquals(1, hugePreview.hunks.size)
        assertEquals(2610, hugePreview.hunks[0].first { it.kind == DiffLineKind.DELETION }.oldLine)

        val hugeUnified = buildString {
            appendLine("--- a/HugeUnified.kt")
            appendLine("+++ b/HugeUnified.kt")
            appendLine("@@ -1,6000 +1,6005 @@")
            repeat(5500) { idx -> appendLine("+added_$idx") }
            repeat(500) { idx -> appendLine("-removed_$idx") }
        }
        val unifiedPreview = parseProcessEditPreview(
            named("edit").copy(id = "huge-unified", detail = hugeUnified),
            "OpenCode",
        )
        assertEquals(5500, unifiedPreview.addedCount)
        assertEquals(500, unifiedPreview.removedCount)
        assertTrue(unifiedPreview.diffLines.size <= MAX_PROCESS_DIFF_LINES)

        assertFalse(isAllowedReadPreviewSource("content://media/external/file/1"))
        assertFalse(isAllowedReadPreviewSource("android.resource://io.github.modao7067.luma.gui/raw/x"))
        assertFalse(isAllowedReadPreviewSource("https://example.com/a.txt"))
        assertFalse(isAllowedReadPreviewSource("data:text/plain;base64,QQ=="))
        assertTrue(isAllowedReadPreviewSource("/workspace/app/Main.kt"))
        assertTrue(isAllowedReadPreviewSource("/etc/os-release"))
        assertTrue(isAllowedReadPreviewSource("file:///tmp/notes.txt"))

        val dir = java.nio.file.Files.createTempDirectory("luma-read-preview-test").toFile()
        try {
            val utf8File = java.io.File(dir, "utf8.txt")
            val prefix = "a".repeat(510)
            utf8File.writeBytes((prefix + "中文测试").toByteArray(Charsets.UTF_8))
            val truncated = readBoundedUtf8Text(utf8File, maxBytes = 512)
            assertEquals(prefix, truncated)

            val binFile = java.io.File(dir, "bin.dat")
            binFile.writeBytes(byteArrayOf(65, 66, 0, 67))
            assertNull(readBoundedUtf8Text(binFile, maxBytes = 512))
        } finally {
            dir.deleteRecursively()
        }
    }
}
