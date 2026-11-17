package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.agent.CodexProjection
import io.github.modao7067.luma.gui.agent.obj
import io.github.modao7067.luma.gui.session.CommandAction
import io.github.modao7067.luma.gui.session.ConversationItem
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ProcessCommandTest {
    private fun command(input: String, output: String = "alpha\nbeta\n", actions: List<CommandAction> = emptyList()) =
        ConversationItem("cmd", "tool", text = input, input = input, detail = output, kind = "commandExecution", outcome = "succeeded",
            commandActions = actions, commandCwd = "/project")

    @Test fun nativeActionsProjectWithoutReplacingTheCommandOrInvocationIdentity() {
        val raw = obj("id" to "cmd", "type" to "commandExecution", "command" to "sed -n '28,29p' src/Message.kt",
            "cwd" to "/project", "aggregatedOutput" to "alpha\nbeta\n", "status" to "completed",
            "commandActions" to JSONArray().put(obj("type" to "read", "command" to "sed -n '28,29p' src/Message.kt", "name" to "Message.kt", "path" to "src/Message.kt")))
        val item = CodexProjection.item(raw)
        assertEquals("commandExecution", item.kind)
        assertEquals("cmd", item.id)
        assertEquals(raw.getString("command"), item.input)
        assertEquals("/project", item.commandCwd)
        assertEquals("src/Message.kt", item.commandActions.single().path)
        assertEquals(ProcessCategory.Read, processCategory(item))
        assertEquals("read", processEntryLabel(item, "Codex"))
        assertEquals("shell", processToolName(item))
        assertEquals(1, processToolInvocations(listOf(item)).size)
        val preview = parseProcessReadPreview(item, "Codex")
        assertEquals("/project/src/Message.kt", preview.filePath)
        assertEquals("Message.kt", preview.fileName)
        assertEquals("L28-29", preview.rangeLabel)
        assertEquals("alpha\nbeta", preview.cleanText)
        assertFalse(preview.resolveFromFile)
        val completed = CodexProjection.item(obj("id" to "cmd", "type" to "commandExecution", "status" to "completed"), item)
        assertEquals(item.commandActions, completed.commandActions)
        assertEquals(item.commandCwd, completed.commandCwd)
        assertEquals(item.input, completed.input)
        assertEquals(item.detail, completed.detail)
        assertTrue(CodexProjection.item(raw.apply { put("id", "other"); remove("commandActions"); remove("cwd") }, item).commandActions.isEmpty())
    }

    @Test fun simpleReadsKeepLiteralFileContentsAndActualRanges() {
        val cases = mapOf(
            "cat -- 'src/a b.kt'" to 1,
            "cat src/Message.kt\n" to 1,
            "sed -n '28,90p' src/Message.kt" to 28,
            "sed -n -e '28,${'$'}p' src/Message.kt" to 28,
            "head -n 2 src/Message.kt" to 1,
            "tail -n +28 src/Message.kt" to 28,
            "bash -lc \"sed -n '28,29p' src/Message.kt\"" to 28,
            "cat src/Message.kt | sed -n '28,29p'" to 28,
            "/bin/bash -lc \"cat src/Message.kt | head -n 135\"" to 1,
            "/bin/bash -lc \"cat src/Message.kt | head -n190\"" to 1,
        )
        cases.forEach { (cmd, first) ->
            val item = command(cmd, "1: literal prefix\n</file>\n")
            assertEquals(cmd, ProcessCategory.Read, processCategory(item))
            val preview = parseProcessReadPreview(item, "Codex")
            assertEquals(cmd, "L$first-${first + 1}", preview.rangeLabel)
            assertEquals(cmd, "1: literal prefix\n</file>", preview.cleanText)
            assertFalse(preview.resolveFromFile)
        }
        val empty = parseProcessReadPreview(command("cat empty.txt", ""), "Codex")
        assertTrue(empty.lines.isEmpty())
        assertFalse(empty.resolveFromFile)
        val numbered = parseProcessReadPreview(command("nl -ba src/Message.kt | sed -n '28,29p'", "    28\talpha\n    29\tbeta\n"), "Codex")
        assertEquals("L28-29", numbered.rangeLabel)
        assertEquals("alpha\nbeta", numbered.cleanText)
    }

    @Test fun multiFileAndDirectoryOutputsDoNotBecomeOneFilesCode() {
        val multi = command("cat a.kt b.kt", actions = listOf(CommandAction("read", "cat a.kt", "a.kt"), CommandAction("read", "cat b.kt", "b.kt")))
        val directory = command("ls src", actions = listOf(CommandAction("listFiles", "ls src", "src")))
        val tail = command("tail -n 20 a.kt", actions = listOf(CommandAction("read", "tail -n 20 a.kt", "a.kt")))
        listOf(multi, directory, tail).forEach {
            assertEquals(ProcessCategory.Read, processCategory(it))
            assertTrue(hasProcessReadPreview(it))
            assertFalse(hasSingleFileReadPreview(it))
            val preview = parseProcessReadPreview(it, "Codex")
            assertEquals("", preview.filePath)
            assertEquals("", preview.rangeLabel)
            assertFalse(preview.sourceLinesKnown)
            assertFalse(preview.resolveFromFile)
            assertEquals("alpha\nbeta", preview.cleanText)
        }
        assertFalse(hasSingleFileReadPreview(command("cat a.kt", actions = listOf(CommandAction("read", "cat a.kt", "b.kt")))))
        val search = command("rg needle src", actions = listOf(CommandAction("search", "rg needle src", "src")))
        assertEquals(ProcessCategory.Search, processCategory(search))
    }

    @Test fun wrappedCompositeReadFromTheScreenshotKeepsItsCombinedOutputWithoutInventedFileBoundaries() {
        val cmd = "/bin/bash -lc \"cat src/CodexContractTest.kt | head -n 135; cat src/ContentBlocksInteractionTest.kt | head -n190; sed -n '660,730p' src/ProcessTimeline.kt; sed -n '440,470p' src/ProcessTimeline.kt\""
        val actions = listOf("CodexContractTest.kt", "ContentBlocksInteractionTest.kt", "ProcessTimeline.kt", "ProcessTimeline.kt").map {
            CommandAction("read", "", "src/$it", it)
        }
        val item = command(cmd, "package sample\nclass CodexContractTest\npackage sample\nclass ContentBlocksInteractionTest\nlast read line\n", actions)
        assertEquals(ProcessCategory.Read, processCategory(item))
        assertTrue(hasProcessReadPreview(item))
        assertFalse(hasSingleFileReadPreview(item))
        val preview = parseProcessReadPreview(item, "Codex")
        assertEquals(item.detail.removeSuffix("\n"), preview.cleanText)
        assertEquals("CodexContractTest.kt · ContentBlocksInteractionTest.kt · ProcessTimeline.kt", preview.parentDir)
        assertEquals("", preview.filePath)
        assertEquals("", preview.rangeLabel)
        assertFalse(preview.sourceLinesKnown)
    }

    @Test fun mixedCommandsAndExpansionsStayTerminalInsteadOfInventingFileMappings() {
        listOf("cat a.kt; ./build.sh", "cat a.kt && cat b.kt", "cat ${'$'}FILE", "cat *.kt", "cat a.kt | grep needle",
            "python3 -c 'print(\"read\")'", "echo 'sed -i x a.kt'", "echo 'a > b'", "cat a.kt > /dev/null").forEach {
            assertEquals(it, ProcessCategory.Terminal, processCategory(command(it)))
            assertFalse(it, hasProcessReadPreview(command(it)))
        }
        val mixed = command("cat a.kt; build", actions = listOf(CommandAction("read", "cat a.kt", "a.kt"), CommandAction("unknown", "build")))
        assertEquals(ProcessCategory.Terminal, processCategory(mixed))
    }

    @Test fun explicitShellWritesAreEditsButNeverPretendToContainADiff() {
        listOf("sed -i 's/old/new/g' src/Message.kt", "perl -pi -e 's/old/new/' src/Message.kt",
            "printf '%s' 'new text' > src/Message.kt", "echo new >> src/Message.kt", "tee src/Message.kt").forEach {
            val item = command(it, "")
            assertEquals(it, ProcessCategory.Edit, processCategory(item))
            assertEquals(it, "edit", processEntryLabel(item, "Codex"))
            assertFalse(it, hasProcessEditPreview(item))
            assertEquals(it, item.input)
        }
        assertEquals(ProcessCategory.Terminal, processCategory(command("perl -MFile -e 'print 1'")))
        val nativeEdit = ConversationItem("edit", "tool", kind = "fileChange", detail = "@@ -1 +1 @@\n-old\n+new")
        assertTrue(hasProcessEditPreview(nativeEdit))
    }
}
