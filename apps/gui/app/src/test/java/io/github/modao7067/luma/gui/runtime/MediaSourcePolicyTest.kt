package io.github.modao7067.luma.gui.runtime

import io.github.modao7067.luma.gui.agent.MediaProjection
import io.github.modao7067.luma.gui.agent.withMediaContext
import io.github.modao7067.luma.gui.session.ConversationItem
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class MediaSourcePolicyTest {
    @Test fun guestPathAllowsNormalGuestLocationsAndRejectsPseudoFilesystems() {
        assertEquals("/workspace/app/Main.kt", MediaSourcePolicy.guestPath("app/Main.kt", "/workspace"))
        assertEquals("/tmp/build.log", MediaSourcePolicy.guestPath("/tmp/build.log", "/workspace"))
        assertEquals("/etc/os-release", MediaSourcePolicy.guestPath("file:///etc/os-release", "/workspace"))
        assertEquals("/root/.codex/config.toml", MediaSourcePolicy.guestPath("sandbox:/root/.codex/config.toml", "/workspace"))

        val forbidden = listOf(
            "/proc",
            "/proc/1/root/data/data/com.example/files/secret.txt",
            "/proc/self/environ",
            "/sys",
            "/sys/class/net",
            "/dev",
            "/dev/zero",
            "../../proc/1/root/etc/hosts",
            "file:///proc/1/cmdline",
            "sandbox:/dev/urandom",
        )
        for (source in forbidden) {
            try {
                MediaSourcePolicy.guestPath(source, "/workspace/project")
                fail("Expected IOException for forbidden guest path: $source")
            } catch (_: IOException) {
            }
        }
    }

    @Test fun mediaProjectionRejectsContentAndAndroidResourceUris() {
        val contentBlock = JSONObject()
            .put("type", "image")
            .put("url", "content://io.github.modao7067.luma.gui.capture-files/photo.jpg")
        assertTrue(MediaProjection.blocks(contentBlock).isEmpty())

        val androidResBlock = JSONObject()
            .put("type", "resource_link")
            .put("uri", "android.resource://io.github.modao7067.luma.gui/raw/secret")
        assertTrue(MediaProjection.blocks(androidResBlock).isEmpty())

        val embeddedContent = JSONObject()
            .put("type", "resource")
            .put("resource", JSONObject().put("uri", "content://media/external/images/media/1"))
        assertTrue(MediaProjection.blocks(embeddedContent).isEmpty())

        val validBlocks = JSONArray()
            .put(JSONObject().put("type", "localImage").put("path", "/tmp/chart.png"))
            .put(JSONObject().put("type", "image").put("data", "AA==").put("mimeType", "image/png"))
            .put(JSONObject().put("type", "image").put("url", "https://example.test/img.png"))
        assertEquals(3, MediaProjection.blocks(validBlocks).size)

        val item = ConversationItem(
            id = "u1",
            role = "user",
            images = listOf("content://media/external/images/media/42", "file:///tmp/ok.png"),
        )
        val scoped = item.withMediaContext("sess-1", "/workspace")
        assertEquals(listOf("file:///tmp/ok.png"), scoped.media.map { it.source })
    }

    @Test fun sessionMediaResolverOpenReservesDiskAndCapsAt512MiB() {
        assertEquals(512L * 1024 * 1024, SessionMediaResolver.OPEN_MAX_BYTES)
        assertEquals(256L * 1024 * 1024, SessionMediaResolver.MIN_FREE_RESERVE_BYTES)
    }

    @Test fun managedRuntimeMediaResolvesRootProcHostPathsWhileBlockingTokens() {
        val filesDir = java.nio.file.Files.createTempDirectory("luma-files-dir").toFile()
        try {
            val rootAgent = java.io.File(filesDir, "runtime/agent/root")
            val mediaCache = java.io.File(rootAgent, "cli-sessions/media-cache").apply { mkdirs() }
            val cachedJpg = java.io.File(mediaCache, "5c868dec-5635-475f-a971-51684d63d6d9.jpg").apply { writeText("jpg") }
            val browserDir = java.io.File(rootAgent, "browser").apply { mkdirs() }
            val browserShot = java.io.File(browserDir, "0ac8a749-f5bb-4d6f-9f0f-cac4c52b96bb.png").apply { writeText("png") }
            val browserToken = java.io.File(browserDir, "79cf21dc-7096-4455-8268-01ecd7726fb1.json").apply { writeText("{}") }
            val bridgeToken = java.io.File(rootAgent, "cli-bridge-token").apply { writeText("secret") }
            val skillDoc = java.io.File(rootAgent, "skills/luma-browser/SKILL.md").apply { parentFile?.mkdirs(); writeText("# Skill") }

            assertEquals(
                cachedJpg.canonicalFile,
                AgentRuntime.resolveManagedRuntimeFile(filesDir, "root", "/proc/1/root${cachedJpg.absolutePath}"),
            )
            assertEquals(
                browserShot.canonicalFile,
                AgentRuntime.resolveManagedRuntimeFile(filesDir, "root", "file:///proc/1/root${browserShot.absolutePath}"),
            )
            assertEquals(
                skillDoc.canonicalFile,
                AgentRuntime.resolveManagedRuntimeFile(filesDir, "root", "/proc/1/root${skillDoc.absolutePath}"),
            )
            assertNull(AgentRuntime.resolveManagedRuntimeFile(filesDir, "root", "/proc/1/root${browserToken.absolutePath}"))
            assertNull(AgentRuntime.resolveManagedRuntimeFile(filesDir, "root", "/proc/1/root${bridgeToken.absolutePath}"))
            assertNull(AgentRuntime.resolveManagedRuntimeFile(filesDir, "root", "/proc/1/root/data/data/com.example/files/secret.txt"))
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test fun binarySniffingHandlesWebpAndAvifWithMultibyteSequences() {
        val webpWithUtf8SizeBytes = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            0xE4.toByte(), 0xB8.toByte(), 0xAD.toByte(), 0x80.toByte(),
            'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
            'V'.code.toByte(), 'P'.code.toByte(), '8'.code.toByte(), ' '.code.toByte(),
        )
        assertEquals("image/webp", MediaSourcePolicy.sniff(webpWithUtf8SizeBytes, "application/octet-stream"))

        val avifCompatBrands = byteArrayOf(
            0x00, 0x00, 0x00, 0x1C,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'm'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), '1'.code.toByte(),
            0x00, 0x00, 0x00, 0x00,
            'm'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), '1'.code.toByte(),
            'a'.code.toByte(), 'v'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(),
        )
        assertEquals("image/avif", MediaSourcePolicy.sniff(avifCompatBrands, "application/octet-stream"))
    }

    @Test fun managedRootProtectedPathAllowsOwnPrivateMediaAndRejectsTokensOrForeignPaths() {
        val filesDir = java.nio.file.Files.createTempDirectory("luma-root-protected").toFile()
        try {
            val rootAgent = java.io.File(filesDir, "runtime/agent/root")
            val mediaCachePath = "${rootAgent.absolutePath}/cli-sessions/media-cache/5c868dec-5635-475f-a971-51684d63d6d9.jpg"
            val browserShotPath = "${rootAgent.absolutePath}/browser/shot.png"
            val skillPath = "${rootAgent.absolutePath}/skills/luma-browser/SKILL.md"

            assertEquals(
                "/proc/1/root$mediaCachePath",
                AgentRuntime.resolveManagedRootProtectedPath(filesDir, "root", "/proc/1/root$mediaCachePath"),
            )
            assertEquals(
                "/proc/1/root$mediaCachePath",
                AgentRuntime.resolveManagedRootProtectedPath(filesDir, "root", "file:///proc/1/root$mediaCachePath"),
            )
            assertEquals(
                "/proc/1/root$browserShotPath",
                AgentRuntime.resolveManagedRootProtectedPath(filesDir, "root", browserShotPath),
            )
            assertEquals(
                "/proc/1/root$skillPath",
                AgentRuntime.resolveManagedRootProtectedPath(filesDir, "root", "/proc/1/root$skillPath"),
            )

            assertNull(AgentRuntime.resolveManagedRootProtectedPath(filesDir, "noroot", "/proc/1/root$mediaCachePath"))
            assertNull(AgentRuntime.resolveManagedRootProtectedPath(filesDir, "root", "/proc/1/root${rootAgent.absolutePath}/cli-sessions/media-cache/token.json"))
            assertNull(AgentRuntime.resolveManagedRootProtectedPath(filesDir, "root", "/proc/1/root${rootAgent.absolutePath}/browser/state.json"))
            assertNull(AgentRuntime.resolveManagedRootProtectedPath(filesDir, "root", "/proc/1/root${rootAgent.absolutePath}/cli-sessions/media-cache/sub/nested.jpg"))
            assertNull(AgentRuntime.resolveManagedRootProtectedPath(filesDir, "root", "/proc/1/root${rootAgent.absolutePath}/cli-sessions/media-cache/../../cli-bridge-token"))
            assertNull(AgentRuntime.resolveManagedRootProtectedPath(filesDir, "root", "/proc/1/root${rootAgent.absolutePath}/cli-sessions/session.json"))
            assertNull(AgentRuntime.resolveManagedRootProtectedPath(filesDir, "root", "/proc/1/root/data/data/com.example/files/secret.txt"))
        } finally {
            filesDir.deleteRecursively()
        }
    }
}
