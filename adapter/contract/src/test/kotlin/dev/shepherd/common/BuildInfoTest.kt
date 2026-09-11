package dev.shepherd.common

import kotlin.test.Test
import kotlin.test.assertTrue

class BuildInfoTest {

    @Test
    fun `version is stamped from the build`() {
        // A placeholder or the fallback would mean processResources did not expand the file.
        assertTrue(BuildInfo.version.matches(Regex("""\d+\.\d+\.\d+.*""")), "unexpected version '${BuildInfo.version}'")
    }
}
