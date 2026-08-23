package org.autojs.autojs.guardian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptGuardianHeartbeatTest {
    @Test
    fun bridgeReturnsTheSynchronousSinkDecision() {
        val expectationListener: (String) -> Boolean = { false }
        val heartbeatListener: (ScriptGuardianHeartbeatReport) -> Boolean = { false }
        ScriptGuardianHeartbeat.bindExpectation(expectationListener)
        ScriptGuardianHeartbeat.bind(heartbeatListener)
        try {
            assertFalse(ScriptGuardianHeartbeat.expect("session"))
            assertFalse(ScriptGuardianHeartbeat.reportIdle("session", 1L))
            assertFalse(ScriptGuardianHeartbeat.reportBusy("session", 2L, "command"))
        } finally {
            ScriptGuardianHeartbeat.unbindExpectation(expectationListener)
            ScriptGuardianHeartbeat.unbind(heartbeatListener)
        }
    }

    @Test
    fun heartbeatExpectationRequiresSessionAndUsesDedicatedSink() {
        val sessions = mutableListOf<String>()
        val listener: (String) -> Boolean = {
            sessions += it
            true
        }
        ScriptGuardianHeartbeat.bindExpectation(listener)
        try {
            assertFalse(ScriptGuardianHeartbeat.expect(""))
            assertTrue(ScriptGuardianHeartbeat.expect(" session "))
        } finally {
            ScriptGuardianHeartbeat.unbindExpectation(listener)
        }

        assertEquals(listOf("session"), sessions)
        assertFalse(ScriptGuardianHeartbeat.expect("session"))
    }

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
