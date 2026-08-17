package org.autojs.autojs.external.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseBroadcastReceiverTest {
    @Test
    fun restoresGuardianForBootActions() {
        listOf(
            "android.intent.action.BOOT_COMPLETED",
            "android.intent.action.QUICKBOOT_POWERON"
        ).forEach { action ->
            assertTrue(
                "expected Guardian restore for $action",
                shouldRestoreScriptGuardian(action)
            )
        }
    }

    @Test
    fun ignoresUnlockAndUnrelatedActions() {
        listOf(
            "android.intent.action.USER_PRESENT",
            "android.intent.action.USER_UNLOCKED",
            "android.intent.action.TIME_SET",
            null
        ).forEach { action ->
            assertFalse(
                "expected Guardian restore to be skipped for $action",
                shouldRestoreScriptGuardian(action)
            )
        }
    }
}
