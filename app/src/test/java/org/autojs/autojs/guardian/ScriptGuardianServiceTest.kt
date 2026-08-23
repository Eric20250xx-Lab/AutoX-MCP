package org.autojs.autojs.guardian

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScriptGuardianServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun mainThreadNeverWaitsForBlockingScriptBridgeAcknowledgement() {
        assertFalse(shouldAwaitScriptGuardianBridgeAck(isMainThread = true))
        assertTrue(shouldAwaitScriptGuardianBridgeAck(isMainThread = false))
    }

    @Test
    fun runnableScriptValidationRejectsEmptyAndMissingPaths() {
        val root = temporaryFolder.newFolder("scripts")

        assertTrue(
            resolveRunnableGuardianScript(
                ScriptGuardianConfig(true, "", root.path)
            ).isFailure
        )
        assertTrue(
            resolveRunnableGuardianScript(
                ScriptGuardianConfig(true, "missing.js", root.path)
            ).isFailure
        )

        assertTrue(root.resolve("receiver.js").createNewFile())
        assertTrue(
            resolveRunnableGuardianScript(
                ScriptGuardianConfig(true, "receiver.js", root.path)
            ).isSuccess
        )
    }

    @Test
    fun keyguardDispatchRequiresLiveServiceRecoveryAndIdleGuardian() {
        assertTrue(
            shouldDispatchGuardianKeyguardGesture(
                destroyed = false,
                recoveryActive = true,
                recoveryJobActive = true,
                guardianAllowsGesture = true
            )
        )
        assertFalse(
            shouldDispatchGuardianKeyguardGesture(
                destroyed = true,
                recoveryActive = true,
                recoveryJobActive = true,
                guardianAllowsGesture = true
            )
        )
        assertFalse(
            shouldDispatchGuardianKeyguardGesture(
                destroyed = false,
                recoveryActive = true,
                recoveryJobActive = false,
                guardianAllowsGesture = true
            )
        )
        assertFalse(
            shouldDispatchGuardianKeyguardGesture(
                destroyed = false,
                recoveryActive = true,
                recoveryJobActive = true,
                guardianAllowsGesture = false
            )
        )
    }
}
