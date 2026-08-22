package org.autojs.autojs.guardian

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptGuardianServiceTest {
    @Test
    fun mainThreadNeverWaitsForBlockingScriptBridgeAcknowledgement() {
        assertFalse(shouldAwaitScriptGuardianBridgeAck(isMainThread = true))
        assertTrue(shouldAwaitScriptGuardianBridgeAck(isMainThread = false))
    }
}
