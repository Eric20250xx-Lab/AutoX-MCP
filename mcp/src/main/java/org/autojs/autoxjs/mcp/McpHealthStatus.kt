package org.autojs.autoxjs.mcp

import android.os.SystemClock
import com.google.gson.annotations.SerializedName
import java.util.ArrayDeque

/**
 * Coarse health state for the embedded MCP HTTP server.
 *
 * This state describes only the in-process Ktor server. It does not run or inspect
 * AutoJs tools and must not be used as proof that accessibility automation works.
 */
enum class McpHealthState {
    STOPPED,
    STARTING,
    HEALTHY,
    DEGRADED,
    RECOVERING,
    FAILED
}

/**
 * Minimal, non-sensitive MCP runtime health that other code in the main process can read.
 */
data class McpHealthSnapshot(
    val state: McpHealthState,
    val generation: Long,
    val startedAtEpochMillis: Long?,
    val lastProbeAgeMillis: Long?,
    val consecutiveFailures: Int,
    val lastErrorCode: String?
)

/** Public read-only entry point for main-process probes and telemetry. */
object McpHealthStatus {
    private val supervisor = McpSelfHealthSupervisor(
        monotonicNowMillis = SystemClock::elapsedRealtime,
        wallNowMillis = System::currentTimeMillis,
        processEpoch = System.currentTimeMillis()
    )

    @JvmStatic
    fun snapshot(): McpHealthSnapshot = supervisor.snapshot()

    internal fun runtime(): McpSelfHealthSupervisor = supervisor
}

internal data class McpEngineIdentity(
    val processEpoch: Long,
    val generation: Long,
    val startedAt: Long
)

internal data class McpHealthPayload(
    @SerializedName("processEpoch")
    val processEpoch: Long,
    @SerializedName("generation")
    val generation: Long,
    @SerializedName("startedAt")
    val startedAt: Long
)

internal enum class McpRecoveryAction {
    NONE,
    RESTART_ENGINE,
    RATE_LIMITED
}

/**
 * Synchronized state machine shared by the server endpoint and the service-owned probe loop.
 */
internal class McpSelfHealthSupervisor(
    private val monotonicNowMillis: () -> Long,
    private val wallNowMillis: () -> Long,
    private val processEpoch: Long,
    private val failuresBeforeRestart: Int = 2,
    private val maxRestarts: Int = 3,
    private val restartWindowMillis: Long = 10 * 60_000L
) {
    private val restartTimes = ArrayDeque<Long>()
    private var state = McpHealthState.STOPPED
    private var generation = 0L
    private var startedAtEpochMillis: Long? = null
    private var currentIdentity: McpEngineIdentity? = null
    private var lastProbeAtElapsedMillis: Long? = null
    private var consecutiveFailures = 0
    private var recoverySuccessStreak = 0
    private var lastErrorCode: String? = null

    @Synchronized
    fun startSession() {
        state = McpHealthState.STARTING
        startedAtEpochMillis = null
        currentIdentity = null
        lastProbeAtElapsedMillis = null
        consecutiveFailures = 0
        recoverySuccessStreak = 0
        lastErrorCode = null
        restartTimes.clear()
    }

    @Synchronized
    fun beginEngineStart(): McpEngineIdentity {
        generation += 1
        val identity = McpEngineIdentity(
            processEpoch = processEpoch,
            generation = generation,
            startedAt = wallNowMillis()
        )
        currentIdentity = identity
        startedAtEpochMillis = identity.startedAt
        recoverySuccessStreak = 0
        if (state != McpHealthState.RECOVERING) {
            state = McpHealthState.STARTING
        }
        return identity
    }

    @Synchronized
    fun engineStartSucceeded(identity: McpEngineIdentity) {
        if (currentIdentity != identity) {
            return
        }
        startedAtEpochMillis = identity.startedAt
        consecutiveFailures = 0
        if (state != McpHealthState.RECOVERING) {
            state = McpHealthState.HEALTHY
            lastErrorCode = null
        }
    }

    @Synchronized
    fun engineStartFailed(identity: McpEngineIdentity) {
        if (currentIdentity != identity) {
            return
        }
        state = McpHealthState.FAILED
        startedAtEpochMillis = null
        recoverySuccessStreak = 0
        lastErrorCode = ERROR_ENGINE_START
    }

    @Synchronized
    fun recordProbeSuccess(identity: McpEngineIdentity) {
        if (currentIdentity != identity) {
            return
        }
        lastProbeAtElapsedMillis = monotonicNowMillis()
        consecutiveFailures = 0
        if (state == McpHealthState.HEALTHY && lastErrorCode == null) {
            recoverySuccessStreak = 0
            return
        }
        recoverySuccessStreak += 1
        if (recoverySuccessStreak >= SUCCESSES_TO_RECOVER) {
            recoverySuccessStreak = 0
            lastErrorCode = null
            state = McpHealthState.HEALTHY
        } else {
            state = McpHealthState.RECOVERING
        }
    }

    @Synchronized
    fun recordProbeFailure(identity: McpEngineIdentity, errorCode: String): McpRecoveryAction {
        if (currentIdentity != identity) {
            return McpRecoveryAction.NONE
        }
        val now = monotonicNowMillis()
        lastProbeAtElapsedMillis = now
        consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(failuresBeforeRestart)
        recoverySuccessStreak = 0
        lastErrorCode = sanitizeErrorCode(errorCode)

        if (consecutiveFailures < failuresBeforeRestart) {
            if (state != McpHealthState.FAILED) {
                state = McpHealthState.DEGRADED
            }
            return McpRecoveryAction.NONE
        }

        pruneRestartHistory(now)
        if (restartTimes.size >= maxRestarts) {
            state = McpHealthState.FAILED
            lastErrorCode = ERROR_RESTART_RATE_LIMITED
            return McpRecoveryAction.RATE_LIMITED
        }

        restartTimes.addLast(now)
        state = McpHealthState.RECOVERING
        return McpRecoveryAction.RESTART_ENGINE
    }

    @Synchronized
    fun stopSession() {
        state = McpHealthState.STOPPED
        startedAtEpochMillis = null
        currentIdentity = null
        lastProbeAtElapsedMillis = null
        consecutiveFailures = 0
        recoverySuccessStreak = 0
        lastErrorCode = null
        restartTimes.clear()
    }

    @Synchronized
    fun expectedIdentity(): McpEngineIdentity? = currentIdentity

    @Synchronized
    fun payload(identity: McpEngineIdentity): McpHealthPayload = McpHealthPayload(
        processEpoch = identity.processEpoch,
        generation = identity.generation,
        startedAt = identity.startedAt
    )

    @Synchronized
    fun snapshot(): McpHealthSnapshot {
        val now = monotonicNowMillis()
        val probeAge = lastProbeAtElapsedMillis?.let { (now - it).coerceAtLeast(0L) }
        return McpHealthSnapshot(
            state = state,
            generation = generation,
            startedAtEpochMillis = startedAtEpochMillis,
            lastProbeAgeMillis = probeAge,
            consecutiveFailures = consecutiveFailures,
            lastErrorCode = lastErrorCode
        )
    }

    private fun pruneRestartHistory(now: Long) {
        while (restartTimes.isNotEmpty() && now - restartTimes.first() >= restartWindowMillis) {
            restartTimes.removeFirst()
        }
    }

    private fun sanitizeErrorCode(errorCode: String): String {
        return if (errorCode in ALLOWED_ERROR_CODES) errorCode else ERROR_PROBE_UNKNOWN
    }

    companion object {
        private const val SUCCESSES_TO_RECOVER = 2
        const val ERROR_ENGINE_START = "ENGINE_START_FAILED"
        const val ERROR_PROBE_TIMEOUT = "PROBE_TIMEOUT"
        const val ERROR_PROBE_CONNECT = "PROBE_CONNECT_FAILED"
        const val ERROR_PROBE_HTTP = "PROBE_HTTP_STATUS"
        const val ERROR_PROBE_PAYLOAD = "PROBE_INVALID_PAYLOAD"
        const val ERROR_PROBE_MISMATCH = "PROBE_IDENTITY_MISMATCH"
        const val ERROR_PROBE_IO = "PROBE_IO_FAILED"
        const val ERROR_PROBE_UNKNOWN = "PROBE_UNKNOWN_FAILED"
        const val ERROR_RESTART_RATE_LIMITED = "RESTART_RATE_LIMITED"

        private val ALLOWED_ERROR_CODES = setOf(
            ERROR_ENGINE_START,
            ERROR_PROBE_TIMEOUT,
            ERROR_PROBE_CONNECT,
            ERROR_PROBE_HTTP,
            ERROR_PROBE_PAYLOAD,
            ERROR_PROBE_MISMATCH,
            ERROR_PROBE_IO,
            ERROR_PROBE_UNKNOWN,
            ERROR_RESTART_RATE_LIMITED
        )
    }
}
