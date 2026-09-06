package org.autojs.autoxjs.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpForegroundServiceStartFailureTest {
    @Test
    fun androidSRestrictionIsDeferred() {
        assertTrue(shouldDeferForegroundServiceStart(
            31, "android.app.ForegroundServiceStartNotAllowedException"
        ))
    }

    @Test
    fun otherFailuresAndOlderAndroidAreNotDeferred() {
        assertFalse(shouldDeferForegroundServiceStart(34, SecurityException::class.java.name))
        assertFalse(shouldDeferForegroundServiceStart(30, "android.app.ForegroundServiceStartNotAllowedException"))
    }
}
