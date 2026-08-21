package org.autojs.autojs.guardian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptGuardianScreenWakePolicyTest {
    @Test
    fun holdsOnlyWhenConfigIsValidUserOptedInAndDeviceIsPlugged() {
        assertTrue(
            ScriptGuardianScreenWakePolicy.shouldHold(
                configValid = true,
                userOptIn = true,
                plugged = 1
            )
        )
        assertFalse(
            ScriptGuardianScreenWakePolicy.shouldHold(
                configValid = false,
                userOptIn = true,
                plugged = 1
            )
        )
        assertFalse(
            ScriptGuardianScreenWakePolicy.shouldHold(
                configValid = true,
                userOptIn = false,
                plugged = 1
            )
        )
        assertFalse(
            ScriptGuardianScreenWakePolicy.shouldHold(
                configValid = true,
                userOptIn = true,
                plugged = 0
            )
        )
    }

    @Test
    fun treatsAnyNonZeroPluggedValueAsConnected() {
        assertTrue(
            ScriptGuardianScreenWakePolicy.shouldHold(
                configValid = true,
                userOptIn = true,
                plugged = -1
            )
        )
    }

    @Test
    fun firstScreenOffWhileEligibleStartsRecovery() {
        val policy = ScriptGuardianScreenWakePolicy()

        assertDecision(
            policy.updateEligibility(
                eligible = true,
                interactive = true,
                nowMillis = 1_000L
            ),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.CANCEL
        )
        assertEquals(ScriptGuardianScreenWakePolicy.State.READY, policy.state)

        val recovery = policy.onScreenOff(nowMillis = 1_001L)
        assertDecision(
            recovery,
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.START
        )
        assertEquals(ScriptGuardianScreenWakePolicy.State.RECOVERING, policy.state)
        assertTrue(policy.beginRecoveryAttempt(recovery.requireRecoveryGeneration()))
        assertEquals(1, policy.recoveryAttempts)
    }

    @Test
    fun successfulRecoveryAndImmediateSecondOffStartsAnotherRecovery() {
        val policy = recoverySucceededAt(5_000L)

        assertEquals(ScriptGuardianScreenWakePolicy.State.READY, policy.state)
        val secondRecovery = policy.onScreenOff(nowMillis = 5_001L)
        assertDecision(
            secondRecovery,
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.START
        )
        assertEquals(ScriptGuardianScreenWakePolicy.State.RECOVERING, policy.state)
    }

    @Test
    fun consecutiveSuccessfulScreenOffCyclesEachStartBoundedRecovery() {
        val policy = recoverySucceededAt(5_000L)

        val secondRecovery = policy.onScreenOff(nowMillis = 5_001L)
        assertDecision(
            secondRecovery,
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.START
        )
        assertEquals(ScriptGuardianScreenWakePolicy.State.RECOVERING, policy.state)
        val secondGeneration = secondRecovery.requireRecoveryGeneration()
        assertTrue(policy.beginRecoveryAttempt(secondGeneration))
        assertDecision(
            policy.onRecoveryChecked(
                recoveryGeneration = secondGeneration,
                interactive = true,
                nowMillis = 5_002L
            ),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.NONE
        )
        assertEquals(ScriptGuardianScreenWakePolicy.State.READY, policy.state)

        val thirdRecovery = policy.onScreenOff(nowMillis = 5_003L)
        assertDecision(
            thirdRecovery,
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.START
        )
        val thirdGeneration = thirdRecovery.requireRecoveryGeneration()
        assertTrue(policy.beginRecoveryAttempt(thirdGeneration))
        assertDecision(
            policy.onRecoveryChecked(
                recoveryGeneration = thirdGeneration,
                interactive = false,
                nowMillis = 5_004L
            ),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.RETRY
        )
        assertTrue(policy.beginRecoveryAttempt(thirdGeneration))
        assertFalse(policy.beginRecoveryAttempt(thirdGeneration))
        assertDecision(
            policy.onRecoveryChecked(
                recoveryGeneration = thirdGeneration,
                interactive = false,
                nowMillis = 5_005L
            ),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.CANCEL
        )
        assertEquals(ScriptGuardianScreenWakePolicy.State.READY, policy.state)
    }

    @Test
    fun ordinaryScreenOnKeepsRecoveryReady() {
        val policy = ScriptGuardianScreenWakePolicy()
        policy.updateEligibility(
            eligible = true,
            interactive = true,
            nowMillis = 1_000L
        )

        assertDecision(
            policy.onScreenOn(nowMillis = 1_000L),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.NONE
        )
        assertEquals(ScriptGuardianScreenWakePolicy.State.READY, policy.state)

        assertDecision(
            policy.onScreenOff(nowMillis = 1_001L),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.START
        )
        assertEquals(ScriptGuardianScreenWakePolicy.State.RECOVERING, policy.state)
    }

    @Test
    fun screenOnBeforeAnAutomaticAttemptCancelsPendingRecovery() {
        val policy = ScriptGuardianScreenWakePolicy()
        policy.updateEligibility(
            eligible = true,
            interactive = false,
            nowMillis = 1_000L
        )

        assertDecision(
            policy.onScreenOn(nowMillis = 1_100L),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.CANCEL
        )
        assertEquals(ScriptGuardianScreenWakePolicy.State.READY, policy.state)

        assertDecision(
            policy.onScreenOff(nowMillis = 1_500L),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.START
        )
        assertEquals(ScriptGuardianScreenWakePolicy.State.RECOVERING, policy.state)
    }

    @Test
    fun screenOnDuringAnAttemptReturnsReadyAndImmediateOffRecoversAgain() {
        val policy = ScriptGuardianScreenWakePolicy()
        val recovery = policy.updateEligibility(
            eligible = true,
            interactive = false,
            nowMillis = 1_000L
        )
        assertTrue(policy.beginRecoveryAttempt(recovery.requireRecoveryGeneration()))

        assertDecision(
            policy.onScreenOn(nowMillis = 2_000L),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.CANCEL
        )
        assertEquals(ScriptGuardianScreenWakePolicy.State.READY, policy.state)
        assertEquals(0, policy.recoveryAttempts)

        assertDecision(
            policy.onScreenOff(nowMillis = 4_000L),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.START
        )
        assertEquals(ScriptGuardianScreenWakePolicy.State.RECOVERING, policy.state)
    }

    @Test
    fun lateCheckFromCanceledRecoveryCannotAffectNextScreenOffCycle() {
        val policy = ScriptGuardianScreenWakePolicy()
        policy.updateEligibility(
            eligible = true,
            interactive = true,
            nowMillis = 1_000L
        )

        val firstRecovery = policy.onScreenOff(nowMillis = 1_100L)
        val firstGeneration = firstRecovery.requireRecoveryGeneration()
        assertTrue(policy.beginRecoveryAttempt(firstGeneration))
        assertDecision(
            policy.onScreenOn(nowMillis = 1_200L),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.CANCEL
        )

        val secondRecovery = policy.onScreenOff(nowMillis = 1_300L)
        val secondGeneration = secondRecovery.requireRecoveryGeneration()
        assertTrue(firstGeneration != secondGeneration)
        assertTrue(policy.beginRecoveryAttempt(secondGeneration))

        assertDecision(
            policy.onRecoveryChecked(
                recoveryGeneration = firstGeneration,
                interactive = true,
                nowMillis = 1_400L
            ),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.NONE
        )
        assertEquals(ScriptGuardianScreenWakePolicy.State.RECOVERING, policy.state)
        assertEquals(1, policy.recoveryAttempts)

        assertDecision(
            policy.onRecoveryChecked(
                recoveryGeneration = secondGeneration,
                interactive = false,
                nowMillis = 1_500L
            ),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.RETRY
        )
    }

    @Test
    fun latePreAttemptInteractiveCheckCannotAffectNextScreenOffCycle() {
        val policy = ScriptGuardianScreenWakePolicy()
        policy.updateEligibility(
            eligible = true,
            interactive = true,
            nowMillis = 1_000L
        )

        val firstRecovery = policy.onScreenOff(nowMillis = 1_100L)
        val firstGeneration = firstRecovery.requireRecoveryGeneration()
        assertDecision(
            policy.onScreenOn(nowMillis = 1_200L),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.CANCEL
        )

        val secondRecovery = policy.onScreenOff(nowMillis = 1_300L)
        val secondGeneration = secondRecovery.requireRecoveryGeneration()
        assertTrue(firstGeneration != secondGeneration)

        assertDecision(
            policy.onRecoveryChecked(
                recoveryGeneration = firstGeneration,
                interactive = true,
                nowMillis = 1_400L
            ),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.NONE
        )
        assertEquals(ScriptGuardianScreenWakePolicy.State.RECOVERING, policy.state)
        assertEquals(0, policy.recoveryAttempts)
        assertTrue(policy.beginRecoveryAttempt(secondGeneration))
    }

    @Test
    fun coldStartWhileEligibleAndNonInteractiveStartsRecovery() {
        val policy = ScriptGuardianScreenWakePolicy()

        assertDecision(
            policy.updateEligibility(
                eligible = true,
                interactive = false,
                nowMillis = 1_000L
            ),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.START
        )
        assertEquals(ScriptGuardianScreenWakePolicy.State.RECOVERING, policy.state)
    }

    @Test
    fun failedConfirmationRetriesOnlyOnceThenCancels() {
        val policy = ScriptGuardianScreenWakePolicy(maxRecoveryAttempts = 2)
        val recovery = policy.updateEligibility(
            eligible = true,
            interactive = false,
            nowMillis = 1_000L
        )
        val recoveryGeneration = recovery.requireRecoveryGeneration()

        assertTrue(policy.beginRecoveryAttempt(recoveryGeneration))
        assertDecision(
            policy.onRecoveryChecked(
                recoveryGeneration = recoveryGeneration,
                interactive = false,
                nowMillis = 2_000L
            ),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.RETRY
        )
        assertEquals(1, policy.recoveryAttempts)

        assertTrue(policy.beginRecoveryAttempt(recoveryGeneration))
        assertFalse(policy.beginRecoveryAttempt(recoveryGeneration))
        assertDecision(
            policy.onRecoveryChecked(
                recoveryGeneration = recoveryGeneration,
                interactive = false,
                nowMillis = 3_000L
            ),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.CANCEL
        )
        assertEquals(0, policy.recoveryAttempts)
        assertEquals(ScriptGuardianScreenWakePolicy.State.READY, policy.state)
    }

    @Test
    fun unpluggingOrDisablingCancelsRecoveryAndResetsState() {
        val policy = ScriptGuardianScreenWakePolicy()
        val recovery = policy.updateEligibility(
            eligible = true,
            interactive = false,
            nowMillis = 1_000L
        )
        assertTrue(policy.beginRecoveryAttempt(recovery.requireRecoveryGeneration()))

        assertDecision(
            policy.updateEligibility(
                eligible = false,
                interactive = false,
                nowMillis = 1_500L
            ),
            shouldHoldLease = false,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.CANCEL
        )
        assertEquals(ScriptGuardianScreenWakePolicy.State.INACTIVE, policy.state)
        assertEquals(0, policy.recoveryAttempts)

        assertDecision(
            policy.updateEligibility(
                eligible = true,
                interactive = true,
                nowMillis = 2_000L
            ),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.CANCEL
        )
        assertEquals(ScriptGuardianScreenWakePolicy.State.READY, policy.state)
    }

    @Test
    fun ineligibleScreenOffDoesNothingUntilEligibilityReturns() {
        val policy = ScriptGuardianScreenWakePolicy()
        policy.updateEligibility(
            eligible = true,
            interactive = true,
            nowMillis = 5_000L
        )
        assertDecision(
            policy.updateEligibility(
                eligible = false,
                interactive = false,
                nowMillis = 7_000L
            ),
            shouldHoldLease = false,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.CANCEL
        )
        assertDecision(
            policy.onScreenOff(nowMillis = 7_001L),
            shouldHoldLease = false,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.NONE
        )
        assertEquals(ScriptGuardianScreenWakePolicy.State.INACTIVE, policy.state)

        assertDecision(
            policy.updateEligibility(
                eligible = true,
                interactive = false,
                nowMillis = 8_000L
            ),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.START
        )
        assertEquals(ScriptGuardianScreenWakePolicy.State.RECOVERING, policy.state)
        assertEquals(0, policy.recoveryAttempts)
    }

    @Test
    fun repeatedSameEligibilityUpdateKeepsRecoveryEnabled() {
        val policy = recoverySucceededAt(5_000L)

        assertDecision(
            policy.updateEligibility(
                eligible = true,
                interactive = false,
                nowMillis = 7_000L
            ),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.NONE
        )
        assertDecision(
            policy.updateEligibility(
                eligible = true,
                interactive = true,
                nowMillis = 8_000L
            ),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.NONE
        )
        assertEquals(ScriptGuardianScreenWakePolicy.State.READY, policy.state)
        assertDecision(
            policy.onScreenOff(nowMillis = 8_001L),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.START
        )
    }

    private fun recoverySucceededAt(nowMillis: Long): ScriptGuardianScreenWakePolicy {
        return ScriptGuardianScreenWakePolicy().also { policy ->
            val recovery = policy.updateEligibility(
                eligible = true,
                interactive = false,
                nowMillis = nowMillis - 2_000L
            )
            val recoveryGeneration = recovery.requireRecoveryGeneration()
            assertTrue(policy.beginRecoveryAttempt(recoveryGeneration))
            assertDecision(
                policy.onRecoveryChecked(
                    recoveryGeneration = recoveryGeneration,
                    interactive = true,
                    nowMillis = nowMillis
                ),
                shouldHoldLease = true,
                recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.NONE
            )
        }
    }

    private fun assertDecision(
        actual: ScriptGuardianScreenWakePolicy.Decision,
        shouldHoldLease: Boolean,
        recoveryAction: ScriptGuardianScreenWakePolicy.RecoveryAction
    ) {
        assertEquals(shouldHoldLease, actual.shouldHoldLease)
        assertEquals(recoveryAction, actual.recoveryAction)
    }

    private fun ScriptGuardianScreenWakePolicy.Decision.requireRecoveryGeneration(): Long =
        checkNotNull(recoveryGeneration)
}
