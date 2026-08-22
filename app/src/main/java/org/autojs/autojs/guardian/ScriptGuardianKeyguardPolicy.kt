package org.autojs.autojs.guardian

internal object ScriptGuardianKeyguardPolicy {
    enum class Action {
        WAIT_FOR_SCREEN,
        READY,
        SECURE_LOCKED,
        WAIT_FOR_ACCESSIBILITY,
        DISMISS
    }

    fun decide(
        interactive: Boolean,
        keyguardLocked: Boolean,
        deviceSecure: Boolean,
        accessibilityAvailable: Boolean
    ): Action = when {
        !interactive -> Action.WAIT_FOR_SCREEN
        !keyguardLocked -> Action.READY
        deviceSecure -> Action.SECURE_LOCKED
        !accessibilityAvailable -> Action.WAIT_FOR_ACCESSIBILITY
        else -> Action.DISMISS
    }

    fun isRecoveryComplete(action: Action): Boolean =
        action == Action.READY || action == Action.SECURE_LOCKED
}
