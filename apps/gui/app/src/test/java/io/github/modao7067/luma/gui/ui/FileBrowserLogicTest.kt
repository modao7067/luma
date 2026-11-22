package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.session.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class FileBrowserLogicTest {
    @Test fun `breadcrumbs expose every ancestor`() {
        assertEquals(listOf("/" to "/"), fileBreadcrumbs("/"))
        assertEquals(listOf("/" to "/", "workspace" to "/workspace", "project" to "/workspace/project"), fileBreadcrumbs("/workspace/project/"))
    }

    @Test fun `parent stops at root`() {
        assertEquals("/workspace", fileParent("/workspace/project"))
        assertEquals("/", fileParent("/workspace"))
        assertEquals("/", fileParent("/"))
    }

    @Test fun `references quote each path`() {
        assertEquals("@\"/a b/c.kt\" @\"/workspace/dir\"", fileReferenceText(listOf("/a b/c.kt", "/workspace/dir")))
        assertEquals("@\"/tmp/a b.kt\" @\"/tmp/clean.kt\"", fileReferenceText(listOf("/tmp/a \"b\r\n.kt", "/tmp/\"clean\"\n.kt")))
    }

    @Test fun `entry detail shows folder or size`() {
        assertEquals("文件夹", fileEntryDetail(FileEntry("d", "/d", true)))
        assertEquals(formatMediaSize(2048), fileEntryDetail(FileEntry("f", "/f", false, size = 2048)))
    }
}
