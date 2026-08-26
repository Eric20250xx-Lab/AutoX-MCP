package org.autojs.autojs

import org.junit.Assert.assertEquals
import org.junit.Test

class AppProcessRoleTest {
    @Test
    fun mapsKnownProcessesWithoutTreatingUnknownProcessAsMain() {
        val packageName = "org.autojs.autoxjs.v7"

        assertEquals(
            AppProcessRole.MAIN,
            AppProcessRole.resolve(packageName, packageName, ":script")
        )
        assertEquals(
            AppProcessRole.SCRIPT,
            AppProcessRole.resolve(packageName, "$packageName:script", ":script")
        )
        assertEquals(
            AppProcessRole.WATCHDOG,
            AppProcessRole.resolve(packageName, "$packageName:guardian", ":script")
        )
        assertEquals(
            AppProcessRole.ATTENDANCE_PROBE,
            AppProcessRole.resolve(packageName, "$packageName:attendance_probe", ":script")
        )
        assertEquals(
            AppProcessRole.OTHER,
            AppProcessRole.resolve(packageName, "$packageName:other", ":script")
        )
        assertEquals(
            AppProcessRole.OTHER,
            AppProcessRole.resolve(packageName, null, ":script")
        )
    }
}
