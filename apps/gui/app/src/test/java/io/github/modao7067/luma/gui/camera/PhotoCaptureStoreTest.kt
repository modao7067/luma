package io.github.modao7067.luma.gui.camera

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PhotoCaptureStoreTest {
    @Test fun pruneDirectoryRemovesExpiredCapturesAndOrphanedOrExpiredReadyMarkers() {
        val dir = Files.createTempDirectory("luma-photo-prune").toFile()
        try {
            val now = 2_000_000_000_000L
            val expiredTime = now - 25 * 60 * 60 * 1000L
            val freshTime = now - 60 * 60 * 1000L

            val expiredJpg = File(dir, "capture-11111111-1111-1111-1111-111111111111.jpg").apply {
                writeText("old")
                setLastModified(expiredTime)
            }
            val expiredReady = File(dir, "${expiredJpg.name}.ready").apply {
                writeText("")
                setLastModified(freshTime)
            }
            val orphanReady = File(dir, "capture-22222222-2222-2222-2222-222222222222.jpg.ready").apply {
                writeText("")
                setLastModified(freshTime)
            }
            val freshJpgWithExpiredReady = File(dir, "capture-33333333-3333-3333-3333-333333333333.jpg").apply {
                writeText("fresh")
                setLastModified(freshTime)
            }
            val staleReadyForFreshJpg = File(dir, "${freshJpgWithExpiredReady.name}.ready").apply {
                writeText("")
                setLastModified(expiredTime)
            }
            val activeJpg = File(dir, "capture-44444444-4444-4444-4444-444444444444.jpg").apply {
                writeText("active")
                setLastModified(freshTime)
            }
            val activeReady = File(dir, "${activeJpg.name}.ready").apply {
                writeText("")
                setLastModified(freshTime)
            }
            val unrelated = File(dir, "keep.txt").apply {
                writeText("keep")
                setLastModified(expiredTime)
            }

            PhotoCaptureStore.pruneDirectory(dir, nowMs = now) {}

            assertFalse(expiredJpg.exists())
            assertFalse(expiredReady.exists())
            assertFalse(orphanReady.exists())
            assertTrue(freshJpgWithExpiredReady.exists())
            assertFalse(staleReadyForFreshJpg.exists())
            assertTrue(activeJpg.exists())
            assertTrue(activeReady.exists())
            assertTrue(unrelated.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
