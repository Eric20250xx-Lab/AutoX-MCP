package org.autojs.autojs.guardian

internal enum class ScriptGuardianWatchdogState(val wireValue: String) {
    STARTING("starting"),
    HEALTHY("healthy"),
    SUSPECT("suspect"),
    RESTORING("restoring"),
    UNRESPONSIVE("unresponsive")
}

internal enum class ScriptGuardianWatchdogAction {
    NONE,
    REQUEST_RESTORE,
    VERIFY_AND_RECOVER
}

internal data class ScriptGuardianWatchdogSnapshot(
    val state: ScriptGuardianWatchdogState = ScriptGuardianWatchdogState.STARTING,
    val consecutiveMisses: Int = 0,
    val consecutiveSuccesses: Int = 0
)

internal data class ScriptGuardianWatchdogDecision(
    val snapshot: ScriptGuardianWatchdogSnapshot,
    val action: ScriptGuardianWatchdogAction = ScriptGuardianWatchdogAction.NONE
)

internal fun reduceScriptGuardianWatchdog(
    previous: ScriptGuardianWatchdogSnapshot,
    success: Boolean
): ScriptGuardianWatchdogDecision {
    if (success) {
        val successes = (previous.consecutiveSuccesses + 1).coerceAtMost(2)
        val recovered = successes >= 2
        return ScriptGuardianWatchdogDecision(
            previous.copy(
                state = if (recovered) {
                    ScriptGuardianWatchdogState.HEALTHY
                } else {
                    previous.state
                },
                consecutiveMisses = 0,
                consecutiveSuccesses = successes
            )
        )
    }

    val misses = if (previous.consecutiveMisses == Int.MAX_VALUE) {
        Int.MAX_VALUE
    } else {
        previous.consecutiveMisses + 1
    }
    val state = when (misses) {
        1 -> ScriptGuardianWatchdogState.SUSPECT
        2 -> ScriptGuardianWatchdogState.RESTORING
        else -> ScriptGuardianWatchdogState.UNRESPONSIVE
    }
    val action = when (misses) {
        2 -> ScriptGuardianWatchdogAction.REQUEST_RESTORE
        3 -> ScriptGuardianWatchdogAction.VERIFY_AND_RECOVER
        else -> if (
            misses > 3 && misses != Int.MAX_VALUE &&
            (misses - 3) % PERSISTENT_RECOVERY_INTERVAL_MISSES == 0
        ) {
            ScriptGuardianWatchdogAction.VERIFY_AND_RECOVER
        } else {
            ScriptGuardianWatchdogAction.NONE
        }
    }
    return ScriptGuardianWatchdogDecision(
        ScriptGuardianWatchdogSnapshot(
            state = state,
            consecutiveMisses = misses,
            consecutiveSuccesses = 0
        ),
        action
    )
}

internal fun isScriptGuardianProbeReady(
    guardianState: String?,
    heartbeatAgeMillis: Long?
): Boolean = when (guardianState) {
    "starting", "running", "busy", "busy_warning" -> true
    "idle" -> heartbeatAgeMillis != null &&
        heartbeatAgeMillis <= GUARDIAN_IDLE_READINESS_MILLIS
    else -> false
}

internal fun scriptGuardianImmediateRecoveryAction(
    action: ScriptGuardianWatchdogAction
): ScriptGuardianImmediateRecoveryAction? = when (action) {
    ScriptGuardianWatchdogAction.NONE -> null
    ScriptGuardianWatchdogAction.REQUEST_RESTORE ->
        ScriptGuardianImmediateRecoveryAction.REQUEST_RESTORE

    ScriptGuardianWatchdogAction.VERIFY_AND_RECOVER ->
        ScriptGuardianImmediateRecoveryAction.VERIFY_AND_RECOVER
}

private const val GUARDIAN_IDLE_READINESS_MILLIS = 60_000L
internal const val PERSISTENT_RECOVERY_INTERVAL_MISSES = 15
