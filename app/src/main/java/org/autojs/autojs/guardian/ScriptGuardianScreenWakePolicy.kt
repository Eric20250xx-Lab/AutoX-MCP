package org.autojs.autojs.guardian

internal class ScriptGuardianScreenWakePolicy(
    private val repeatOffWindowMillis: Long = REPEAT_OFF_WINDOW_MILLIS,
    private val maxRecoveryAttempts: Int = MAX_RECOVERY_ATTEMPTS
) {
    enum class State {
        INACTIVE,
        READY,
        RECOVERING,
        RECOVERED_GRACE,
        USER_PAUSED
    }

    enum class RecoveryAction {
        NONE,
        START,
        RETRY,
        CANCEL
    }

    data class Decision(
        val shouldHoldLease: Boolean,
        val recoveryAction: RecoveryAction
    )

    @Volatile
    var state: State = State.INACTIVE
        private set

    @Volatile
    var recoveryAttempts: Int = 0
        private set

    private var eligible = false
    private var recoveredAtMillis: Long? = null

    @Synchronized
    fun updateEligibility(
        eligible: Boolean,
        interactive: Boolean,
        nowMillis: Long
    ): Decision {
        val eligibilityChanged = this.eligible != eligible
        this.eligible = eligible
        if (!eligible) {
            resetRecovery(State.INACTIVE)
            return Decision(false, RecoveryAction.CANCEL)
        }

        if (eligibilityChanged) {
            resetRecovery(State.READY)
            return if (interactive) {
                Decision(true, RecoveryAction.CANCEL)
            } else {
                beginRecovery()
            }
        }

        val recoveredAt = recoveredAtMillis
        if (
            state == State.RECOVERED_GRACE &&
            recoveredAt != null &&
            nowMillis >= recoveredAt &&
            nowMillis - recoveredAt > repeatOffWindowMillis
        ) {
            state = State.READY
            recoveredAtMillis = null
        }

        return currentDecision()
    }

    @Synchronized
    fun onScreenOff(nowMillis: Long): Decision {
        if (!eligible || state == State.INACTIVE) {
            return Decision(false, RecoveryAction.NONE)
        }
        if (state == State.USER_PAUSED) {
            return Decision(false, RecoveryAction.NONE)
        }
        if (state == State.RECOVERING) {
            return Decision(true, RecoveryAction.NONE)
        }

        val recoveredAt = recoveredAtMillis
        if (
            state == State.RECOVERED_GRACE &&
            recoveredAt != null &&
            nowMillis >= recoveredAt &&
            nowMillis - recoveredAt <= repeatOffWindowMillis
        ) {
            resetRecovery(State.USER_PAUSED)
            return Decision(false, RecoveryAction.CANCEL)
        }
        return beginRecovery()
    }

    @Synchronized
    fun onScreenOn(nowMillis: Long): Decision {
        if (!eligible || state == State.INACTIVE) {
            return Decision(false, RecoveryAction.NONE)
        }
        if (state == State.RECOVERING) {
            if (recoveryAttempts == 0) {
                resetRecovery(State.READY)
                return Decision(true, RecoveryAction.CANCEL)
            }
            state = State.RECOVERED_GRACE
            recoveredAtMillis = nowMillis
            recoveryAttempts = 0
            return Decision(true, RecoveryAction.NONE)
        }
        return currentDecision()
    }

    @Synchronized
    fun beginRecoveryAttempt(): Boolean {
        if (
            !eligible ||
            state != State.RECOVERING ||
            recoveryAttempts >= maxRecoveryAttempts
        ) {
            return false
        }
        recoveryAttempts += 1
        return true
    }

    @Synchronized
    fun onRecoveryChecked(interactive: Boolean, nowMillis: Long): Decision {
        if (!eligible || state != State.RECOVERING) return currentDecision()
        if (interactive) {
            state = State.RECOVERED_GRACE
            recoveredAtMillis = nowMillis
            recoveryAttempts = 0
            return Decision(true, RecoveryAction.NONE)
        }
        if (recoveryAttempts < maxRecoveryAttempts) {
            return Decision(true, RecoveryAction.RETRY)
        }

        resetRecovery(State.READY)
        return Decision(true, RecoveryAction.CANCEL)
    }

    private fun beginRecovery(): Decision {
        state = State.RECOVERING
        recoveredAtMillis = null
        recoveryAttempts = 0
        return Decision(true, RecoveryAction.START)
    }

    private fun resetRecovery(nextState: State) {
        state = nextState
        recoveredAtMillis = null
        recoveryAttempts = 0
    }

    private fun currentDecision(): Decision = Decision(
        shouldHoldLease = eligible && state != State.USER_PAUSED,
        recoveryAction = RecoveryAction.NONE
    )

    companion object {
        internal const val REPEAT_OFF_WINDOW_MILLIS = 10_000L
        internal const val MAX_RECOVERY_ATTEMPTS = 2

        fun shouldHold(
            configValid: Boolean,
            userOptIn: Boolean,
            plugged: Int
        ): Boolean = configValid && userOptIn && plugged != 0
    }
}
