package org.autojs.autojs.guardian

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptGuardianDiagnosticsTest {
    @Test
    fun wakeLockIsFreshOnlyInsideItsLeaseWindow() {
        val ownerId = "process-a"
        val snapshot = ScriptGuardianDiagnosticSnapshot(
            wakeLockHeld = true,
            wakeLockUpdatedAt = 1_000L,
            wakeLockOwnerId = ownerId
        )

        assertTrue(snapshot.hasFreshWakeLock(1_000L, ownerId))
        assertTrue(
            snapshot.hasFreshWakeLock(
                1_000L + ScriptGuardianWakeLockLease.LEASE_MILLIS,
                ownerId
            )
        )
        assertFalse(
            snapshot.hasFreshWakeLock(
                1_001L + ScriptGuardianWakeLockLease.LEASE_MILLIS,
                ownerId
            )
        )
        assertFalse(snapshot.hasFreshWakeLock(999L, ownerId))
    }

    @Test
    fun persistedWakeLockIsFreshOnlyForItsOwningProcess() {
        val snapshot = ScriptGuardianDiagnosticSnapshot(
            wakeLockHeld = true,
            wakeLockUpdatedAt = 1_000L,
            wakeLockOwnerId = "process-a"
        )

        assertTrue(snapshot.hasFreshWakeLock(1_001L, "process-a"))
        assertFalse(snapshot.hasFreshWakeLock(1_001L, "process-b"))
    }

    @Test
    fun releasedWakeLockIsNeverReportedAsFresh() {
        val snapshot = ScriptGuardianDiagnosticSnapshot(
            wakeLockHeld = false,
            wakeLockUpdatedAt = 1_000L
        )

        assertFalse(snapshot.hasFreshWakeLock(1_000L))
    }

    @Test
    fun firstHeartbeatForANewSessionBypassesPersistenceThrottle() {
        assertFalse(
            shouldPersistGuardianHeartbeat(
                lastPersistedAt = 1_000L,
                lastPersistedState = "IDLE",
                lastPersistedSessionId = "session-a",
                now = 1_001L,
                state = "IDLE",
                sessionId = "session-a",
                persistIntervalMillis = 60_000L
            )
        )
        assertTrue(
            shouldPersistGuardianHeartbeat(
                lastPersistedAt = 1_000L,
                lastPersistedState = "IDLE",
                lastPersistedSessionId = "session-a",
                now = 1_001L,
                state = "IDLE",
                sessionId = "session-b",
                persistIntervalMillis = 60_000L
            )
        )
    }
}
