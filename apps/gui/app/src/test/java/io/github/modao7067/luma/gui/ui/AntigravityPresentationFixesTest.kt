package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.runtime.MediaSourcePolicy
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.junit.Assert.*
import org.junit.Test

class AntigravityPresentationFixesTest {
    private val parser = Parser.builder().extensions(listOf(TablesExtension.create())).build()

    @Test fun tableCellResourceLinksStayInlineWhileParagraphsKeepCards() {
        val table = freezeMarkdownNode(parser.parse(
            "| 产品 | 模块 |\n|---|---|\n| Luma GUI | [build.gradle](file:///workspace/luma/apps/gui/app/build.gradle) |\n"
        )).children.single()
        val cell = table.children.flatMap { it.children }.flatMap { it.children }
            .first { c -> c.children.any { it.kind == MarkdownKind.LINK } }
        assertTrue(markdownImages(cell, resourceLinks = false).isEmpty())
        assertEquals(1, markdownImages(cell).size)
        val image = freezeMarkdownNode(parser.parse("| a |\n|---|\n| ![x](file:///tmp/x.png) |\n")).children.single()
        val imageCell = image.children.flatMap { it.children }.flatMap { it.children }.last()
        assertEquals(1, markdownImages(imageCell, resourceLinks = false).size)
        val paragraph = freezeMarkdownNode(parser.parse("[README.md](file:///workspace/luma/README.md)"))
        assertEquals(1, markdownImages(paragraph).size)
    }

    @Test fun liveExcerptSkipsTrailingBlankLinesAndKeepsNewestText() {
        val first = "Initial hypothesis confirmed: `codexRequests` generates the request data in specific scenarios."
        val second = "Tracking the three adapter types during reconnection and live turns is crucial."
        val detail = "$first\n\n\n$second\n\n\n"
        val excerpt = processLiveExcerpt(detail)
        assertTrue(excerpt.isNotBlank())
        assertFalse(excerpt.endsWith("\n"))
        assertTrue(excerpt.endsWith(second))
        assertEquals("$first\n$second", excerpt)
        val long = "x".repeat(2000) + " newest"
        val tail = processLiveExcerpt("$long\n\n", maxChars = 100)
        assertTrue(tail.startsWith("…"))
        assertTrue(tail.endsWith("newest"))
        assertTrue(tail.length <= 101)
        assertEquals("", processLiveExcerpt("\n\n  \n"))
        val bgOnly = """
            Created At: 2026-11-10T08:55:00Z
            Tool is running as a background task with task id: bg-42
            Task logs are available at: file:///root/.gemini/antigravity-cli/brain/abc/.system_generated/tasks/task-bg-42.log
            YOU MUST TAKE ONE OF THE FOLLOWING TWO ACTIONS: A) either proceed to other relevant work (if any) or, B) simply update the user with a short message (that you have launched the command and will wait for it to finish) and end the turn.
             DO NOTHING ELSE.
             When updating the user, always respond in the language of the user (defaulting to English). Never mix two or more languages in the same response.
        """.trimIndent()
        assertEquals("", processLiveExcerpt(bgOnly))
        assertEquals("real stdout line 1\nreal stdout line 2", processLiveExcerpt("real stdout line 1\nreal stdout line 2\n$bgOnly"))
    }

    @Test fun duplicatedQuestionHeaderAndPromptShowOnce() {
        assertEquals("1. 排序的作用范围是什么？" to "", requestQuestionCaption("1. 排序的作用范围是什么？", "1. 排序的作用范围是什么？"))
        assertEquals("排序" to "", requestQuestionCaption("排序", " 排序 "))
        assertEquals("1. 范围？" to "", requestQuestionCaption("1. 范围？", "范围？"))
        assertEquals("范围" to "排序的作用范围是什么？", requestQuestionCaption("范围", "排序的作用范围是什么？"))
        assertEquals("" to "问题", requestQuestionCaption("", "问题"))
        assertEquals("标题" to "", requestQuestionCaption("标题", ""))
    }

    @Test fun fileCardsDistinguishCommonKinds() {
        fun symbol(source: String, mime: String = "") = mediaFileSymbol(io.github.modao7067.luma.gui.session.MediaResource(source, mimeType = mime))
        assertEquals(MenuSymbol.CODE, symbol("file:///w/build.gradle"))
        assertEquals(MenuSymbol.CODE, symbol("file:///w/Main.kt"))
        assertEquals(MenuSymbol.CODE, symbol("file:///w/app.ts", "video/mp2t"))
        assertEquals(MenuSymbol.TERMINAL, symbol("file:///w/gradlew"))
        assertEquals(MenuSymbol.TERMINAL, symbol("file:///w/Makefile"))
        assertEquals(MenuSymbol.DATA, symbol("file:///w/package.json"))
        assertEquals(MenuSymbol.SETTINGS, symbol("file:///w/gradle.properties"))
        assertEquals(MenuSymbol.SETTINGS, symbol("file:///w/.gitignore"))
        assertEquals(MenuSymbol.TABLE, symbol("file:///w/a.csv"))
        assertEquals(MenuSymbol.ARTICLE, symbol("file:///w/README.md"))
        assertEquals(MenuSymbol.TEXT, symbol("file:///w/run.log"))
        assertEquals(MenuSymbol.TEXT, symbol("file:///w/LICENSE"))
        assertEquals(MenuSymbol.BROWSER, symbol("file:///w/index.html"))
        assertEquals(MenuSymbol.DIFF, symbol("file:///w/fix.patch"))
        assertEquals(MenuSymbol.ARCHIVE, symbol("file:///w/a.tar.gz"))
        assertEquals(MenuSymbol.VIDEO, symbol("file:///w/a.mp4"))
        assertEquals(MenuSymbol.PDF, symbol("file:///w/a.pdf"))
        assertEquals(MenuSymbol.TEXT, symbol("file:///w/unknown", "text/plain"))
        assertEquals(MenuSymbol.FILE, symbol("file:///w/blob.bin"))
        assertEquals(MenuSymbol.FOLDER, symbol("file:///w/dir", "inode/directory"))
    }

    @Test fun readableTextFilesClassifyAsText() {
        listOf("build.gradle", "settings.gradle", "gradle.properties", "Makefile", "Dockerfile", ".gitignore", "gradlew",
            "CMakeLists.txt", "LICENSE", "Cargo.lock", "app.vue", "main.swift", "x.ts", "index.html", "a.json", "README.md",
            "/workspace/luma/apps/gui/app/build.gradle").forEach {
            assertTrue(it, MediaSourcePolicy.isText(MediaSourcePolicy.mime(it)))
        }
        listOf("a.png", "a.pdf", "a.zip", "a.apk", "a.mp4", "a.bin", "noext", "a.svg").forEach {
            assertFalse(it, MediaSourcePolicy.isText(MediaSourcePolicy.mime(it)))
        }
        assertTrue(MediaSourcePolicy.isText("application/x-sh"))
        assertTrue(MediaSourcePolicy.isText("application/ld+json"))
        assertTrue(MediaSourcePolicy.isText("text/plain; charset=utf-8"))
        assertFalse(MediaSourcePolicy.isText("application/octet-stream"))
    }
}
