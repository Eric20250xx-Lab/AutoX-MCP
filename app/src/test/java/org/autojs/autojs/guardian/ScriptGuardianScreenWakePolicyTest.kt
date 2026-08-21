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

        assertDecision(
            policy.onScreenOff(nowMillis = 1_001L),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.START
        )
        assertEquals(ScriptGuardianScreenWakePolicy.State.RECOVERING, policy.state)
        assertTrue(policy.beginRecoveryAttempt())
        assertEquals(1, policy.recoveryAttempts)
    }

    @Test
    fun successfulRecoveryAndSecondOffWithinTenSecondsPausesForUser() {
        val policy = recoverySucceededAt(5_000L)

        assertEquals(ScriptGuardianScreenWakePolicy.State.RECOVERED_GRACE, policy.state)
        assertDecision(
            policy.onScreenOff(nowMillis = 14_999L),
            shouldHoldLease = false,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.CANCEL
        )
        assertEquals(ScriptGuardianScreenWakePolicy.State.USER_PAUSED, policy.state)
    }

    @Test
    fun screenOffAfterGraceWindowStartsAnotherRecovery() {
        val policy = recoverySucceededAt(5_000L)

        assertDecision(
            policy.onScreenOff(nowMillis = 15_001L),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.START
        )
        assertEquals(ScriptGuardianScreenWakePolicy.State.RECOVERING, policy.state)
    }

    @Test
    fun ordinaryScreenOnDoesNotOpenTheUserPauseWindow() {
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
    fun screenOnBeforeAnAutomaticAttemptCancelsWithoutOpeningGrace() {
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
    fun screenOnDuringAnAttemptStartsGraceAndImmediateOffPausesForUser() {
        val policy = ScriptGuardianScreenWakePolicy()
        policy.updateEligibility(
            eligible = true,
            interactive = false,
            nowMillis = 1_000L
        )
        assertTrue(policy.beginRecoveryAttempt())

        assertDecision(
            policy.onScreenOn(nowMillis = 2_000L),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.NONE
        )
        assertEquals(ScriptGuardianScreenWakePolicy.State.RECOVERED_GRACE, policy.state)
        assertEquals(0, policy.recoveryAttempts)

        assertDecision(
            policy.onScreenOff(nowMillis = 4_000L),
            shouldHoldLease = false,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.CANCEL
        )
        assertEquals(ScriptGuardianScreenWakePolicy.State.USER_PAUSED, policy.state)
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
        policy.updateEligibility(
            eligible = true,
            interactive = false,
            nowMillis = 1_000L
        )

        assertTrue(policy.beginRecoveryAttempt())
        assertDecision(
            policy.onRecoveryChecked(interactive = false, nowMillis = 2_000L),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.RETRY
        )
        assertEquals(1, policy.recoveryAttempts)

        assertTrue(policy.beginRecoveryAttempt())
        assertFalse(policy.beginRecoveryAttempt())
        assertDecision(
            policy.onRecoveryChecked(interactive = false, nowMillis = 3_000L),
            shouldHoldLease = true,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.CANCEL
        )
        assertEquals(0, policy.recoveryAttempts)
        assertEquals(ScriptGuardianScreenWakePolicy.State.READY, policy.state)
    }

    @Test
    fun unpluggingOrDisablingCancelsRecoveryAndResetsState() {
        val policy = ScriptGuardianScreenWakePolicy()
        policy.updateEligibility(
            eligible = true,
            interactive = false,
            nowMillis = 1_000L
        )
        assertTrue(policy.beginRecoveryAttempt())

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
    fun eligibilityCycleClearsUserPauseAndCanRecoverAgain() {
        val policy = recoverySucceededAt(5_000L)
        policy.onScreenOff(nowMillis = 6_000L)
        assertEquals(ScriptGuardianScreenWakePolicy.State.USER_PAUSED, policy.state)

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
    fun repeatedSameEligibilityUpdateDoesNotClearUserPause() {
        val policy = recoverySucceededAt(5_000L)
        policy.onScreenOff(nowMillis = 6_000L)
        assertEquals(ScriptGuardianScreenWakePolicy.State.USER_PAUSED, policy.state)

        assertDecision(
            policy.updateEligibility(
                eligible = true,
                interactive = false,
                nowMillis = 7_000L
            ),
            shouldHoldLease = false,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.NONE
        )
        assertDecision(
            policy.updateEligibility(
                eligible = true,
                interactive = true,
                nowMillis = 8_000L
            ),
            shouldHoldLease = false,
            recoveryAction = ScriptGuardianScreenWakePolicy.RecoveryAction.NONE
        )
        assertEquals(ScriptGuardianScreenWakePolicy.State.USER_PAUSED, policy.state)
    }

    private fun recoverySucceededAt(nowMillis: Long): ScriptGuardianScreenWakePolicy {
        return ScriptGuardianScreenWakePolicy().also { policy ->
            policy.updateEligibility(
                eligible = true,
                interactive = false,
                nowMillis = nowMillis - 2_000L
            )
            assertTrue(policy.beginRecoveryAttempt())
            assertDecision(
                policy.onRecoveryChecked(interactive = true, nowMillis = nowMillis),
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
}
