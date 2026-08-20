package org.autojs.autojs.guardian

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptGuardianRestoreReceiverTest {
    @Test
    fun acceptsOnlyGuardianLifecycleActions() {
        listOf(
            "android.intent.action.BOOT_COMPLETED",
            "android.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.USER_UNLOCKED",
            "android.intent.action.MY_PACKAGE_REPLACED"
        ).forEach { action ->
            assertTrue(
                "expected dedicated Guardian restore for $action",
                shouldRestoreScriptGuardianFromDedicatedReceiver(action)
            )
        }
    }

    @Test
    fun rejectsUnrelatedAndMissingActions() {
        listOf(
            "android.intent.action.USER_PRESENT",
            "android.intent.action.TIME_SET",
            null
        ).forEach { action ->
            assertFalse(
                "expected dedicated Guardian restore to skip $action",
                shouldRestoreScriptGuardianFromDedicatedReceiver(action)
            )
        }
    }
}
