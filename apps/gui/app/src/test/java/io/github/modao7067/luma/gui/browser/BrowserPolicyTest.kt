package io.github.modao7067.luma.gui.browser
import org.junit.Assert.*
import org.junit.Test
class BrowserPolicyTest {
    @Test fun addressBarAddsSchemeWithoutGuessingDomainsOrLosingComponents() {
        mapOf(
            " example.com " to "https://example.com/",
            "example.com:8443/a?q=1#part" to "https://example.com:8443/a?q=1#part",
            "//example.com/path" to "https://example.com/path",
            "203.0.113.10:8443/a" to "https://203.0.113.10:8443/a",
            "[2001:db8::1]:8443/a" to "https://[2001:db8::1]:8443/a",
            "localhost:3000/a?q=1" to "http://localhost:3000/a?q=1",
            "localhost:443/a" to "http://localhost:443/a",
            "127.0.0.1:8000" to "http://127.0.0.1:8000/",
            "[::1]:3000" to "http://[::1]:3000/",
            "https://localhost:3000/a" to "https://localhost:3000/a",
            "http://127.0.0.1:8000/a" to "http://127.0.0.1:8000/a",
            "HTTPS://EXAMPLE.COM/a%20b" to "https://example.com/a%20b",
            "例子.测试/路径" to "https://xn--fsqu00a.xn--0zwm56d/%E8%B7%AF%E5%BE%84",
        ).forEach { (input, expected) -> assertEquals(input, expected, BrowserPolicy.addressUrl(input)) }
    }

    @Test fun addressBarKeepsNavigationRestrictionsAndRejectsInvalidInput() {
        listOf("", "   ", "not a domain", "example.com:99999", "http://example.com", "http://localhost.evil.test",
            "https://user:secret@example.com", "file:///etc/passwd", "content://provider/a", "javascript:alert(1)",
            "data:text/html,hi", "intent://example", "https://").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { BrowserPolicy.addressUrl(it) }
        }
    }
    @Test fun onlyHttpsAndExplicitLoopbackHttp() {
        listOf("https://example.com/a?q=1", "http://localhost:3000", "http://127.0.0.1:8000", "http://[::1]:3000").forEach { assertTrue(it, BrowserPolicy.allowedUrl(it)) }
        listOf("http://example.com", "http://localhost.example.com", "https://user:secret@example.com", "file:///etc/passwd", "content://provider/a", "javascript:alert(1)", "data:text/html,hi", "intent://example", "https://", "http://127.0.0.1@evil.test").forEach { assertFalse(it, BrowserPolicy.allowedUrl(it)) }
    }
    @Test fun sessionOwnershipIsExplicit() { BrowserPolicy.requireOwner("a", "a"); assertThrows(IllegalArgumentException::class.java) { BrowserPolicy.requireOwner("a", "b") } }

    @Test fun telemetryUrlsRedactQuerySecretsAndStripFragments() {
        assertEquals(
            "https://example.com/oauth/callback?code=REDACTED&state=REDACTED",
            BrowserPolicy.sanitizeTelemetryUrl("https://user:pass@example.com/oauth/callback?code=secret123&state=xyz#tok=999"),
        )
        assertEquals("http://localhost:3000/api/items?empty=", BrowserPolicy.sanitizeTelemetryUrl("http://localhost:3000/api/items?empty="))
        assertEquals("blob:...", BrowserPolicy.sanitizeTelemetryUrl("blob:https://example.com/1234"))
    }

    @Test fun agentDownloadsBlockExecutableAndActiveMarkupTypes() {
        listOf(
            "app-release.apk" to "application/octet-stream",
            "payload.sh" to "text/plain",
            "installer.exe" to null,
            "xss.svg" to "image/svg+xml",
            "page.html" to "text/html",
            "module.wasm" to "application/wasm",
            "script.sh.gz" to "application/gzip",
        ).forEach { (name, mime) ->
            assertTrue("$name ($mime)", BrowserPolicy.isBlockedAgentDownload(name, mime))
        }
        listOf(
            "report.pdf" to "application/pdf",
            "export.csv" to "text/csv",
            "photo.png" to "image/png",
            "data.json" to "application/json",
            "bundle.zip" to "application/zip",
        ).forEach { (name, mime) ->
            assertFalse("$name ($mime)", BrowserPolicy.isBlockedAgentDownload(name, mime))
        }
    }

    @Test fun snapshotDiffReportsMinimalAddedRemovedAndUnchangedCounts() {
        val prev = listOf("Demo — https://example.com", "@1 <button> \"Save\"", "Old line")
        val curr = listOf("Demo — https://example.com", "@1 <button> \"Save\" [disabled]", "New line")
        val diff = BrowserPolicy.computeSnapshotDiff(prev, curr)
        assertEquals(1, diff.unchanged)
        assertEquals(2, diff.added)
        assertEquals(2, diff.removed)
        assertTrue(diff.lines.contains("- @1 <button> \"Save\""))
        assertTrue(diff.lines.contains("+ @1 <button> \"Save\" [disabled]"))
    }
}
