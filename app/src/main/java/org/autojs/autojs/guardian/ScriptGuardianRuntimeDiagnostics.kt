package org.autojs.autojs.guardian

import android.os.Bundle
import android.os.SystemClock
import org.autojs.autoxjs.mcp.McpHealthStatus
import org.json.JSONObject
import java.util.UUID

/**
 * Compact, process-local health snapshot for the guarded command-agent script.
 * It deliberately excludes preferences, network identity, script paths, and command arguments.
 */
object ScriptGuardianRuntimeDiagnostics {
    internal data class GuardianProbeSnapshot(
        val state: String,
        val heartbeatAgeMillis: Long?
    )

    private data class State(
        val initialized: Boolean = false,
        val appProcessEpoch: String = "",
        val processStartedAtElapsed: Long = 0L,
        val nativePulseAtElapsed: Long = 0L,
        val guardianState: String = "unknown",
        val guardianSessionId: String = "",
        val guardianHeartbeatAtElapsed: Long = 0L,
        val guardianRestartReason: String = "",
        val commandPhase: String = "unknown",
        val commandId: String = "",
        val watchdogState: String = "starting",
        val watchdogMisses: Int = 0,
        val watchdogSuccesses: Int = 0,
        val watchdogLastProbeAtElapsed: Long = 0L,
        val watchdogLastSuccessAtElapsed: Long = 0L,
        val watchdogLastError: String = "",
        val lastRestore: String = "",
        val lastRestoreAtElapsed: Long = 0L
    )

    private val lock = Any()
    private var state = State()

    internal fun initializeMainProcess() {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (!state.initialized) {
                state = State(
                    initialized = true,
                    appProcessEpoch = UUID.randomUUID().toString(),
                    processStartedAtElapsed = now,
                    nativePulseAtElapsed = now
                )
            }
        }
    }

    internal fun recordNativePulse(): Pair<String, Long> {
        initializeMainProcess()
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            state = state.copy(nativePulseAtElapsed = now)
            return state.appProcessEpoch to state.processStartedAtElapsed
        }
    }

    internal fun updateWatchdog(bundle: Bundle) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (!state.initialized) return
            state = state.copy(
                watchdogState = bundle.getString(
                    ScriptGuardianProbeProtocol.KEY_WATCHDOG_STATE,
                    state.watchdogState
                ).take(24),
                watchdogMisses = bundle.getInt(
                    ScriptGuardianProbeProtocol.KEY_WATCHDOG_MISSES,
                    state.watchdogMisses
                ).coerceAtLeast(0),
                watchdogSuccesses = bundle.getInt(
                    ScriptGuardianProbeProtocol.KEY_WATCHDOG_SUCCESSES,
                    state.watchdogSuccesses
                ).coerceAtLeast(0),
                watchdogLastProbeAtElapsed = bundle.getLong(
                    ScriptGuardianProbeProtocol.KEY_WATCHDOG_LAST_PROBE_AT,
                    state.watchdogLastProbeAtElapsed
                ).coerceIn(0L, now),
                watchdogLastSuccessAtElapsed = bundle.getLong(
                    ScriptGuardianProbeProtocol.KEY_WATCHDOG_LAST_SUCCESS_AT,
                    state.watchdogLastSuccessAtElapsed
                ).coerceIn(0L, now),
                watchdogLastError = bundle.getString(
                    ScriptGuardianProbeProtocol.KEY_WATCHDOG_LAST_ERROR,
                    state.watchdogLastError
                ).take(80)
            )
        }
    }

    internal fun updateGuardian(status: ScriptGuardianStatus) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (!state.initialized) return
            state = when (status) {
                ScriptGuardianStatus.Disabled -> state.copy(
                    guardianState = "disabled",
                    guardianSessionId = "",
                    guardianHeartbeatAtElapsed = 0L,
                    commandPhase = "unknown",
                    commandId = ""
                )

                is ScriptGuardianStatus.Starting -> state.copy(
                    guardianState = "starting",
                    guardianSessionId = "",
                    guardianHeartbeatAtElapsed = 0L,
                    commandPhase = "unknown",
                    commandId = ""
                )

                is ScriptGuardianStatus.Running -> {
                    val heartbeat = status.heartbeat
                    if (heartbeat == null) {
                        state.copy(guardianState = "running", commandPhase = "unknown")
                    } else {
                        val busy = heartbeat.state == ScriptGuardianHeartbeatState.BUSY
                        state.copy(
                            guardianState = if (busy) "busy" else "idle",
                            guardianSessionId = heartbeat.sessionId.take(80),
                            guardianHeartbeatAtElapsed = now,
                            guardianRestartReason = "",
                            commandPhase = if (busy) "busy" else "idle",
                            commandId = heartbeat.commandId.orEmpty().take(80)
                        )
                    }
                }

                is ScriptGuardianStatus.BusyWarning -> state.copy(
                    guardianState = "busy_warning",
                    commandPhase = "busy",
                    commandId = status.commandId.take(80)
                )

                is ScriptGuardianStatus.Stopping -> state.copy(guardianState = "stopping")
                is ScriptGuardianStatus.Retrying -> state.copy(
                    guardianState = "retrying",
                    guardianSessionId = "",
                    guardianHeartbeatAtElapsed = 0L,
                    guardianRestartReason = guardianRestartCode(status.reason),
                    commandPhase = "unknown",
                    commandId = ""
                )
            }
        }
    }

    internal fun recordRestore(reason: String?) {
        synchronized(lock) {
            if (!state.initialized) return
            state = state.copy(
                lastRestore = reason.orEmpty().replace('\n', ' ').replace('\r', ' ').take(80),
                lastRestoreAtElapsed = SystemClock.elapsedRealtime()
            )
        }
    }

    internal fun guardianProbeSnapshot(): GuardianProbeSnapshot {
        val now = SystemClock.elapsedRealtime()
        val snapshot = synchronized(lock) { state }
        val heartbeatAge = snapshot.guardianHeartbeatAtElapsed
            .takeIf { it > 0L && it <= now }
            ?.let { now - it }
        return GuardianProbeSnapshot(snapshot.guardianState, heartbeatAge)
    }

    /** Returns versioned JSON; an uninitialized secondary process returns only the version. */
    @JvmStatic
    fun snapshotJson(): String {
        val snapshot = synchronized(lock) { state }
        val json = JSONObject().put("v", 1)
        if (!snapshot.initialized) return json.toString()

        val now = SystemClock.elapsedRealtime()
        json.put("appProcessEpoch", snapshot.appProcessEpoch)
            .put("processStartedAtElapsed", snapshot.processStartedAtElapsed)
            .put("nativePulseAtElapsed", snapshot.nativePulseAtElapsed)
            .put("guardianState", snapshot.guardianState)
            .put("commandPhase", snapshot.commandPhase)
            .put("watchdogState", snapshot.watchdogState)
            .put("watchdogMisses", snapshot.watchdogMisses)
            .put("watchdogSuccesses", snapshot.watchdogSuccesses)

        putIfNotBlank(json, "guardianSessionId", snapshot.guardianSessionId)
        putAge(json, "guardianHeartbeatAgeMs", now, snapshot.guardianHeartbeatAtElapsed)
        putIfNotBlank(json, "guardianRestartReason", snapshot.guardianRestartReason)
        putIfNotBlank(json, "commandId", snapshot.commandId)
        putAge(json, "watchdogLastProbeAgeMs", now, snapshot.watchdogLastProbeAtElapsed)
        putAge(json, "watchdogLastSuccessAgeMs", now, snapshot.watchdogLastSuccessAtElapsed)
        putIfNotBlank(json, "watchdogLastError", snapshot.watchdogLastError)
        putIfNotBlank(json, "lastRestore", snapshot.lastRestore)
        putAge(json, "lastRestoreAgeMs", now, snapshot.lastRestoreAtElapsed)

        runCatching { McpHealthStatus.snapshot() }.getOrNull()?.let { mcp ->
            json.put("mcpState", mcp.state.name.lowercase())
                .put("mcpGeneration", mcp.generation)
                .put("mcpFailures", mcp.consecutiveFailures)
            mcp.startedAtEpochMillis?.let { json.put("mcpStartedAt", it) }
            mcp.lastProbeAgeMillis?.let { json.put("mcpProbeAgeMs", it) }
            putIfNotBlank(json, "mcpErrorCode", mcp.lastErrorCode.orEmpty())
        }
        return json.toString()
    }

    private fun putIfNotBlank(json: JSONObject, key: String, value: String) {
        if (value.isNotBlank()) json.put(key, value)
    }

    private fun putAge(json: JSONObject, key: String, now: Long, then: Long) {
        if (then > 0L && then <= now) json.put(key, now - then)
    }

    private fun guardianRestartCode(reason: String): String = when {
        reason.startsWith("first heartbeat timed out") -> "FIRST_HEARTBEAT_TIMEOUT"
        reason.startsWith("idle heartbeat timed out") -> "IDLE_HEARTBEAT_TIMEOUT"
        reason == "script exited" -> "SCRIPT_EXITED"
        reason == "existing script did not stop" -> "TAKEOVER_STOP_TIMEOUT"
        else -> "GUARDIAN_RETRY"
    }
}
