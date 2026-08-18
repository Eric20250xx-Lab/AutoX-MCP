package org.autojs.autojs.guardian

import com.stardust.autojs.script.JavaScriptFileSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScriptGuardianExecutionGuardTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

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
}
