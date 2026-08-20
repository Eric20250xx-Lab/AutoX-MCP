package org.autojs.autojs.guardian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptGuardianHeartbeatTest {
    @Test
    fun invalidReportsReturnFalseWithoutCallingSink() {
        var calls = 0
        val listener: (ScriptGuardianHeartbeatReport) -> Boolean = {
            calls += 1
            true
        }
        ScriptGuardianHeartbeat.bind(listener)
        try {
            assertFalse(ScriptGuardianHeartbeat.reportIdle("", 1L))
            assertFalse(ScriptGuardianHeartbeat.reportIdle("session", 0L))
            assertFalse(ScriptGuardianHeartbeat.reportBusy("session", 1L, ""))
            assertEquals(0, calls)
        } finally {
            ScriptGuardianHeartbeat.unbind(listener)
        }
    }

    @Test
    fun validReportsAreDeliveredToCurrentSink() {
        val reports = mutableListOf<ScriptGuardianHeartbeatReport>()
        val listener: (ScriptGuardianHeartbeatReport) -> Boolean = {
            reports += it
            true
        }
        ScriptGuardianHeartbeat.bind(listener)
        try {
            assertTrue(ScriptGuardianHeartbeat.reportIdle(" session ", 1L))
            assertTrue(ScriptGuardianHeartbeat.reportBusy("session", 2L, " command-1 "))
        } finally {
            ScriptGuardianHeartbeat.unbind(listener)
        }

        assertEquals(
            listOf(
                ScriptGuardianHeartbeatReport(
                    "session",
                    1L,
                    ScriptGuardianHeartbeatState.IDLE
                ),
                ScriptGuardianHeartbeatReport(
                    "session",
                    2L,
                    ScriptGuardianHeartbeatState.BUSY,
                    "command-1"
                )
            ),
            reports
        )
        assertFalse(ScriptGuardianHeartbeat.reportIdle("session", 3L))
    }
}
