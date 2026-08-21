package org.autojs.autojs.guardian

@Suppress("UNUSED_PARAMETER")
internal class ScriptGuardianScreenWakePolicy(
    private val maxRecoveryAttempts: Int = MAX_RECOVERY_ATTEMPTS
) {
    enum class State {
        INACTIVE,
        READY,
        RECOVERING
    }

    enum class RecoveryAction {
        NONE,
        START,
        RETRY,
        CANCEL
    }

    data class Decision(
        val shouldHoldLease: Boolean,
        val recoveryAction: RecoveryAction,
        val recoveryGeneration: Long? = null
    )

    @Volatile
    var state: State = State.INACTIVE
        private set

    @Volatile
    var recoveryAttempts: Int = 0
        private set

    private var eligible = false
    private var nextRecoveryGeneration = 0L
    private var activeRecoveryGeneration: Long? = null

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

        return currentDecision()
    }

    @Synchronized
    fun onScreenOff(nowMillis: Long): Decision {
        if (!eligible || state == State.INACTIVE) {
            return Decision(false, RecoveryAction.NONE)
        }
        if (state == State.RECOVERING) {
            return Decision(true, RecoveryAction.NONE)
        }
        return beginRecovery()
    }

    @Synchronized
    fun onScreenOn(nowMillis: Long): Decision {
        if (!eligible || state == State.INACTIVE) {
            return Decision(false, RecoveryAction.NONE)
        }
        if (state == State.RECOVERING) {
            resetRecovery(State.READY)
            return Decision(true, RecoveryAction.CANCEL)
        }
        return currentDecision()
    }

    @Synchronized
    fun beginRecoveryAttempt(recoveryGeneration: Long): Boolean {
        if (
            !eligible ||
            state != State.RECOVERING ||
            activeRecoveryGeneration != recoveryGeneration ||
            recoveryAttempts >= maxRecoveryAttempts
        ) {
            return false
        }
        recoveryAttempts += 1
        return true
    }

    @Synchronized
    fun onRecoveryChecked(
        recoveryGeneration: Long,
        interactive: Boolean,
        nowMillis: Long
    ): Decision {
        if (
            !eligible ||
            state != State.RECOVERING ||
            activeRecoveryGeneration != recoveryGeneration
        ) {
            return currentDecision()
        }
        if (interactive) {
            resetRecovery(State.READY)
            return Decision(true, RecoveryAction.NONE)
        }
        if (recoveryAttempts < maxRecoveryAttempts) {
            return Decision(true, RecoveryAction.RETRY)
        }

        resetRecovery(State.READY)
        return Decision(true, RecoveryAction.CANCEL)
    }

    private fun beginRecovery(): Decision {
        nextRecoveryGeneration += 1
        activeRecoveryGeneration = nextRecoveryGeneration
        state = State.RECOVERING
        recoveryAttempts = 0
        return Decision(
            shouldHoldLease = true,
            recoveryAction = RecoveryAction.START,
            recoveryGeneration = activeRecoveryGeneration
        )
    }

    private fun resetRecovery(nextState: State) {
        state = nextState
        recoveryAttempts = 0
        activeRecoveryGeneration = null
    }

    private fun currentDecision(): Decision = Decision(
        shouldHoldLease = eligible,
        recoveryAction = RecoveryAction.NONE
    )

    companion object {
        internal const val MAX_RECOVERY_ATTEMPTS = 2

        fun shouldHold(
            configValid: Boolean,
            userOptIn: Boolean,
            plugged: Int
        ): Boolean = configValid && userOptIn && plugged != 0
    }
}
