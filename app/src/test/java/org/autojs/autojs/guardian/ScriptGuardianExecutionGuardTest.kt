package org.autojs.autojs.guardian

import com.stardust.autojs.script.JavaScriptFileSource
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScriptGuardianExecutionGuardTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @After
    fun tearDown() {
        ScriptGuardianExecutionGuard.updateConfig(null)
    }

    @Test
    fun rejectsMatchingScriptOutsideManagedLaunch() {
        val guardedFile = temporaryFolder.newFile("receiver.js")

        assertFalse(
            ScriptGuardianExecutionGuard.allows(
                JavaScriptFileSource(guardedFile),
                guardedFile
            )
        )
    }

    @Test
    fun managedLaunchBypassesGuardOnlyInsideScope() {
        val guardedFile = temporaryFolder.newFile("receiver.js")
        val source = JavaScriptFileSource(guardedFile)

        assertTrue(
            ScriptGuardianExecutionGuard.runManagedLaunch {
                ScriptGuardianExecutionGuard.allows(source, guardedFile)
            }
        )
        assertFalse(ScriptGuardianExecutionGuard.allows(source, guardedFile))
    }

    @Test
    fun allowsDifferentScript() {
        val guardedFile = temporaryFolder.newFile("receiver.js")
        val otherFile = temporaryFolder.newFile("other.js")

        assertTrue(
            ScriptGuardianExecutionGuard.allows(
                JavaScriptFileSource(otherFile),
                guardedFile
            )
        )
    }

    @Test
    fun processLocalConfigIsReplacedAndCleared() {
        val root = temporaryFolder.newFolder("scripts")
        val first = File(root, "first.js").apply { createNewFile() }
        val second = File(root, "second.js").apply { createNewFile() }

        ScriptGuardianExecutionGuard.updateConfig(
            ScriptGuardianConfig(true, first.name, root.path)
        )
        assertFalse(ScriptGuardianExecutionGuard.allows(JavaScriptFileSource(first)))

        ScriptGuardianExecutionGuard.updateConfig(
            ScriptGuardianConfig(true, second.name, root.path)
        )
        assertTrue(ScriptGuardianExecutionGuard.allows(JavaScriptFileSource(first)))
        assertFalse(ScriptGuardianExecutionGuard.allows(JavaScriptFileSource(second)))

        ScriptGuardianExecutionGuard.updateConfig(null)
        assertTrue(ScriptGuardianExecutionGuard.allows(JavaScriptFileSource(second)))
    }
}
