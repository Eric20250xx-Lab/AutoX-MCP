package org.autojs.autoxjs.mcp

import org.junit.Assert
import org.junit.Test

class McpForegroundServiceStartFailureTest {
    @Test
    fun classifiesOnlyAndroidSBackgroundRestrictionAsDeferred() {
        val restriction = "android.app.ForegroundServiceStartNotAllowedException"
        Assert.assertTrue(shouldDeferForegroundServiceStart(31, restriction))
        Assert.assertFalse(shouldDeferForegroundServiceStart(34, SecurityException::class.java.name))
        Assert.assertFalse(shouldDeferForegroundServiceStart(30, restriction))
    }
}
