package org.autojs.autojs.guardian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScriptGuardianConfigTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun resolvesRelativePathInsideScriptDirectory() {
        val root = temporaryFolder.newFolder("scripts")
        val config = ScriptGuardianConfig(true, "icbc/receiver.js", root.path)

        assertEquals(
            File(root, "icbc/receiver.js").canonicalFile,
            config.resolveScriptFile().getOrThrow()
        )
    }

    @Test
    fun normalizesWindowsSeparators() {
        val root = temporaryFolder.newFolder("scripts")
        val config = ScriptGuardianConfig(true, "icbc\\receiver.js", root.path)

        assertEquals(
            File(root, "icbc/receiver.js").canonicalFile,
            config.resolveScriptFile().getOrThrow()
        )
    }

    @Test
    fun rejectsUnsafeOrEmptyPaths() {
        val root = temporaryFolder.newFolder("scripts")
        val invalidPaths = listOf("", "/tmp/receiver.js", "../receiver.js", "a//receiver.js", "./a.js")

        invalidPaths.forEach { path ->
            assertTrue(
                "expected path to be rejected: $path",
                ScriptGuardianConfig(true, path, root.path).resolveScriptFile().isFailure
            )
        }
    }

    @Test
    fun disabledConfigDoesNotResolveAFile() {
        val root = temporaryFolder.newFolder("scripts")

        assertTrue(
            ScriptGuardianConfig(false, "receiver.js", root.path)
                .resolveScriptFile()
                .isFailure
        )
    }
}
