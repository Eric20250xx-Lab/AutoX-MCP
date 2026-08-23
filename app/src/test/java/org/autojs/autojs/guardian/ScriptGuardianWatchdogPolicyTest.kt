package org.autojs.autojs.guardian

import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptGuardianWatchdogPolicyTest {
    @Test
    fun missesEscalateConservativelyAndRateLimitPersistentRecovery() {
        var snapshot = ScriptGuardianWatchdogSnapshot()

        var decision = reduceScriptGuardianWatchdog(snapshot, success = false)
        snapshot = decision.snapshot
        assertEquals(ScriptGuardianWatchdogState.SUSPECT, snapshot.state)
        assertEquals(ScriptGuardianWatchdogAction.NONE, decision.action)

        decision = reduceScriptGuardianWatchdog(snapshot, success = false)
        snapshot = decision.snapshot
        assertEquals(ScriptGuardianWatchdogState.RESTORING, snapshot.state)
        assertEquals(ScriptGuardianWatchdogAction.REQUEST_RESTORE, decision.action)

        decision = reduceScriptGuardianWatchdog(snapshot, success = false)
        snapshot = decision.snapshot
        assertEquals(ScriptGuardianWatchdogState.UNRESPONSIVE, snapshot.state)
        assertEquals(ScriptGuardianWatchdogAction.VERIFY_AND_RECOVER, decision.action)

        repeat(PERSISTENT_RECOVERY_INTERVAL_MISSES - 1) {
            decision = reduceScriptGuardianWatchdog(snapshot, success = false)
            snapshot = decision.snapshot
            assertEquals(ScriptGuardianWatchdogState.UNRESPONSIVE, snapshot.state)
            assertEquals(ScriptGuardianWatchdogAction.NONE, decision.action)
        }

        decision = reduceScriptGuardianWatchdog(snapshot, success = false)
        snapshot = decision.snapshot
        assertEquals(3 + PERSISTENT_RECOVERY_INTERVAL_MISSES, snapshot.consecutiveMisses)
        assertEquals(ScriptGuardianWatchdogAction.VERIFY_AND_RECOVER, decision.action)

        decision = reduceScriptGuardianWatchdog(snapshot, success = false)
        assertEquals(ScriptGuardianWatchdogAction.NONE, decision.action)
    }

    @Test
    fun twoConsecutiveSuccessesAreRequiredToRecover() {
        val failed = ScriptGuardianWatchdogSnapshot(
            state = ScriptGuardianWatchdogState.UNRESPONSIVE,
            consecutiveMisses = 3
        )

        val first = reduceScriptGuardianWatchdog(failed, success = true)
        assertEquals(ScriptGuardianWatchdogState.UNRESPONSIVE, first.snapshot.state)
        assertEquals(0, first.snapshot.consecutiveMisses)
        assertEquals(1, first.snapshot.consecutiveSuccesses)

        val second = reduceScriptGuardianWatchdog(first.snapshot, success = true)
        assertEquals(ScriptGuardianWatchdogState.HEALTHY, second.snapshot.state)
        assertEquals(0, second.snapshot.consecutiveMisses)
        assertEquals(2, second.snapshot.consecutiveSuccesses)
    }

    @Test
    fun aSuccessBreaksTheMissStreakEvenBeforeFullRecovery() {
        val partiallyRecovered = ScriptGuardianWatchdogSnapshot(
            state = ScriptGuardianWatchdogState.SUSPECT,
            consecutiveMisses = 0,
            consecutiveSuccesses = 1
        )

        val decision = reduceScriptGuardianWatchdog(partiallyRecovered, success = false)

        assertEquals(ScriptGuardianWatchdogState.SUSPECT, decision.snapshot.state)
        assertEquals(1, decision.snapshot.consecutiveMisses)
        assertEquals(0, decision.snapshot.consecutiveSuccesses)
        assertEquals(ScriptGuardianWatchdogAction.NONE, decision.action)
    }

    @Test
    fun mainProcessReplyRequiresGuardianReadinessButBusyNeverTimesOut() {
        assertEquals(true, isScriptGuardianProbeReady("starting", null))
        assertEquals(true, isScriptGuardianProbeReady("running", null))
        assertEquals(true, isScriptGuardianProbeReady("idle", 5_000L))
        assertEquals(false, isScriptGuardianProbeReady("idle", 60_001L))
        assertEquals(true, isScriptGuardianProbeReady("busy", 600_000L))
        assertEquals(true, isScriptGuardianProbeReady("busy_warning", null))
        assertEquals(false, isScriptGuardianProbeReady("retrying", null))
        assertEquals(false, isScriptGuardianProbeReady("disabled", null))
        assertEquals(false, isScriptGuardianProbeReady("unknown", null))
        assertEquals(false, isScriptGuardianProbeReady(null, null))
    }

    @Test
    fun recoveryActionsMapOnlyToDedicatedExactAlarms() {
        assertEquals(
            null,
            scriptGuardianImmediateRecoveryAction(ScriptGuardianWatchdogAction.NONE)
        )
        assertEquals(
            ScriptGuardianImmediateRecoveryAction.REQUEST_RESTORE,
            scriptGuardianImmediateRecoveryAction(
                ScriptGuardianWatchdogAction.REQUEST_RESTORE
            )
        )
        assertEquals(
            ScriptGuardianImmediateRecoveryAction.VERIFY_AND_RECOVER,
            scriptGuardianImmediateRecoveryAction(
                ScriptGuardianWatchdogAction.VERIFY_AND_RECOVER
            )
        )
    }
}
