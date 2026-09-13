package dev.shepherd.api

import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class UiRoutesTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `the manager's address opens the web UI`() = testApplication {
        startManager(tempDir, "ui-root")
        val browser = createClient { followRedirects = false }

        val root = browser.get("/")
        val bare = browser.get("/ui")

        assertEquals(HttpStatusCode.Found, root.status)
        assertEquals(UI_PATH, root.headers[HttpHeaders.Location])
        assertEquals(UI_PATH, bare.headers[HttpHeaders.Location])
    }

    @Test
    fun `UI files are served under a strict content security policy`() = testApplication {
        startManager(tempDir, "ui-files")

        val page = client.get("/ui/")
        val script = client.get("/ui/js/main.js")
        val missing = client.get("/ui/js/missing.js")
        val headOnly = client.head("/ui/")

        assertEquals(HttpStatusCode.OK, page.status)
        assertEquals(ContentType.Text.Html, page.contentType()?.withoutParameters())
        assertContains(page.bodyAsText(), """<script type="module" src="/ui/js/main.js"></script>""")
        assertEquals(UI_CONTENT_SECURITY_POLICY, page.headers["Content-Security-Policy"])
        assertEquals("DENY", page.headers["X-Frame-Options"])
        assertContains(page.headers[HttpHeaders.CacheControl].orEmpty(), "no-cache")
        assertEquals(HttpStatusCode.OK, script.status)
        assertContains(script.contentType().toString(), "javascript")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals(HttpStatusCode.OK, headOnly.status, "HEAD works for monitoring and proxies")
    }
}
