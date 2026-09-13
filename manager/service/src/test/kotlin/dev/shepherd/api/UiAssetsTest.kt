package dev.shepherd.api

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The web UI has no build step, so these checks stand in for one: markup the Content-Security-Policy
 * allows, no way to turn a string into HTML, and no module import that points nowhere.
 */
class UiAssetsTest {
    private val root: File = File("src/main/resources/ui")
    private val scripts: List<File> by lazy { root.walkTopDown().filter { file -> file.extension == "js" }.toList() }

    @Test
    fun `the page loads scripts and styles only from files`() {
        val html: String = File(root, "index.html").readText()

        assertFalse(Regex("""<script(?![^>]*\bsrc=)[^>]*>""").containsMatchIn(html), "inline script")
        assertFalse(html.contains("<style"), "inline style block")
        assertFalse(Regex("""\sstyle=""").containsMatchIn(html), "style attribute")
        assertFalse(Regex("""\son[a-z]+=""", RegexOption.IGNORE_CASE).containsMatchIn(html), "inline event handler")
        Regex("""(?:src|href)="/ui/([^"]+)"""").findAll(html).forEach { match ->
            assertTrue(File(root, match.groupValues[1]).isFile, "index.html refers to missing ${match.groupValues[1]}")
        }
    }

    @Test
    fun `scripts never turn strings into markup or code`() {
        assertTrue(scripts.isNotEmpty(), "no scripts found under $root")
        for (script in scripts) {
            val text: String = script.readText()
            for (sink in SINKS) {
                assertFalse(text.contains(sink), "${script.path} uses $sink")
            }
        }
    }

    @Test
    fun `every module import points at a file`() {
        for (script in scripts) {
            IMPORT.findAll(script.readText()).forEach { match ->
                val target: File = script.parentFile.resolve(match.groupValues[1]).normalize()
                assertTrue(target.isFile, "${script.path} imports missing ${match.groupValues[1]}")
            }
        }
    }

    private companion object {
        val SINKS =
            listOf("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function", "setAttribute('style'")
        val IMPORT = Regex("""^\s*import\s+[^'"]*['"](\.{1,2}/[^'"]+)['"]""", RegexOption.MULTILINE)
    }
}
