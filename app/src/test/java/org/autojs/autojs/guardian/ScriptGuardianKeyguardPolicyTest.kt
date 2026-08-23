package org.autojs.autojs.guardian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptGuardianKeyguardPolicyTest {
    @Test
    fun waitsForScreenBeforeInspectingKeyguard() {
        assertEquals(
            ScriptGuardianKeyguardPolicy.Action.WAIT_FOR_SCREEN,
            decide(
                interactive = false,
                keyguardLocked = false,
                deviceSecure = false,
                accessibilityAvailable = true
            )
        )
    }

    @Test
    fun reportsReadyWhenKeyguardIsNotShowing() {
        assertEquals(
            ScriptGuardianKeyguardPolicy.Action.READY,
            decide(
                interactive = true,
                keyguardLocked = false,
                deviceSecure = true,
                accessibilityAvailable = false
            )
        )
    }

    @Test
    fun preservesSecureKeyguardForUserAuthentication() {
        assertEquals(
            ScriptGuardianKeyguardPolicy.Action.SECURE_LOCKED,
            decide(
                interactive = true,
                keyguardLocked = true,
                deviceSecure = true,
                accessibilityAvailable = true
            )
        )
    }

    @Test
    fun dismissesNonSecureKeyguardWhenAccessibilityIsAvailable() {
        assertEquals(
            ScriptGuardianKeyguardPolicy.Action.DISMISS,
            decide(
                interactive = true,
                keyguardLocked = true,
                deviceSecure = false,
                accessibilityAvailable = true
            )
        )
    }

    @Test
    fun waitsForAccessibilityBeforeDismissingNonSecureKeyguard() {
        assertEquals(
            ScriptGuardianKeyguardPolicy.Action.WAIT_FOR_ACCESSIBILITY,
            decide(
                interactive = true,
                keyguardLocked = true,
                deviceSecure = false,
                accessibilityAvailable = false
            )
        )
    }

    @Test
    fun recoveryIsCompleteOnlyWhenReadyOrSecurelyLocked() {
        assertTrue(
            ScriptGuardianKeyguardPolicy.isRecoveryComplete(
                ScriptGuardianKeyguardPolicy.Action.READY
            )
        )
        assertTrue(
            ScriptGuardianKeyguardPolicy.isRecoveryComplete(
                ScriptGuardianKeyguardPolicy.Action.SECURE_LOCKED
            )
        )
        assertFalse(
            ScriptGuardianKeyguardPolicy.isRecoveryComplete(
                ScriptGuardianKeyguardPolicy.Action.WAIT_FOR_SCREEN
            )
        )
        assertFalse(
            ScriptGuardianKeyguardPolicy.isRecoveryComplete(
                ScriptGuardianKeyguardPolicy.Action.WAIT_FOR_ACCESSIBILITY
            )
        )
        assertFalse(
            ScriptGuardianKeyguardPolicy.isRecoveryComplete(
                ScriptGuardianKeyguardPolicy.Action.DISMISS
            )
        )
    }

    private fun decide(
        interactive: Boolean,
        keyguardLocked: Boolean,
        deviceSecure: Boolean,
        accessibilityAvailable: Boolean
    ): ScriptGuardianKeyguardPolicy.Action = ScriptGuardianKeyguardPolicy.decide(
        interactive = interactive,
        keyguardLocked = keyguardLocked,
        deviceSecure = deviceSecure,
        accessibilityAvailable = accessibilityAvailable
    )
}
