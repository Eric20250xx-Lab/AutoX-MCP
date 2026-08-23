package org.autojs.autojs.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptGuardianBackgroundReadinessPolicyTest {
    @Test
    fun deniedExactAlarmPermissionIsReportedAndOpensRecoveryGrant() {
        assertEquals(
            ScriptGuardianExactAlarmReadiness.REQUIRED,
            scriptGuardianExactAlarmReadiness(
                guardianEnabled = true,
                exactAlarmAllowed = false
            )
        )
        assertEquals(
            ScriptGuardianBackgroundSettingsAction.EXACT_ALARM,
            scriptGuardianBackgroundSettingsAction(
                guardianEnabled = true,
                exactAlarmAllowed = false,
                batteryOptimizationExempt = false
            )
        )
    }

    @Test
    fun exactAlarmPermissionDoesNotReplaceBatteryReadinessWhenGranted() {
        assertEquals(
            ScriptGuardianExactAlarmReadiness.READY,
            scriptGuardianExactAlarmReadiness(
                guardianEnabled = true,
                exactAlarmAllowed = true
            )
        )
        assertEquals(
            ScriptGuardianBackgroundSettingsAction.BATTERY_OPTIMIZATION,
            scriptGuardianBackgroundSettingsAction(
                guardianEnabled = true,
                exactAlarmAllowed = true,
                batteryOptimizationExempt = false
            )
        )
    }
}
