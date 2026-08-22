package org.autojs.autojs.guardian

import java.util.concurrent.atomic.AtomicReference

internal enum class ScriptGuardianHeartbeatState {
    IDLE,
    BUSY
}

internal data class ScriptGuardianHeartbeatReport(
    val sessionId: String,
    val sequence: Long,
    val state: ScriptGuardianHeartbeatState,
    val commandId: String? = null
)

/**
 * Process-local bridge used by a guarded Rhino script to prove that its receive loop is alive.
 * Scripts should treat a false return as an unavailable bridge and continue their legacy loop.
 */
object ScriptGuardianHeartbeat {
    const val SESSION_ARGUMENT = "scriptGuardianSessionId"

    private val sink = AtomicReference<((ScriptGuardianHeartbeatReport) -> Boolean)?>(null)
    private val expectationSink = AtomicReference<((String) -> Boolean)?>(null)

    /**
     * Opts the current guarded script into heartbeat enforcement. Heartbeat-aware scripts must
     * call this before doing network or business work so a failed startup is retried safely.
     */
    @JvmStatic
    fun expect(sessionId: String?): Boolean {
        val normalizedSessionId = sessionId?.trim().orEmpty()
        if (normalizedSessionId.isEmpty()) return false
        return runCatching {
            expectationSink.get()?.invoke(normalizedSessionId) == true
        }.getOrDefault(false)
    }

    @JvmStatic
    fun reportIdle(sessionId: String?, sequence: Long): Boolean = publish(
        sessionId = sessionId,
        sequence = sequence,
        state = ScriptGuardianHeartbeatState.IDLE,
        commandId = null
    )

    @JvmStatic
    fun reportBusy(sessionId: String?, sequence: Long, commandId: String?): Boolean = publish(
        sessionId = sessionId,
        sequence = sequence,
        state = ScriptGuardianHeartbeatState.BUSY,
        commandId = commandId
    )

    internal fun bind(listener: (ScriptGuardianHeartbeatReport) -> Boolean) {
        sink.set(listener)
    }

    internal fun unbind(listener: (ScriptGuardianHeartbeatReport) -> Boolean) {
        sink.compareAndSet(listener, null)
    }

    internal fun bindExpectation(listener: (String) -> Boolean) {
        expectationSink.set(listener)
    }

    internal fun unbindExpectation(listener: (String) -> Boolean) {
        expectationSink.compareAndSet(listener, null)
    }

    private fun publish(
        sessionId: String?,
        sequence: Long,
        state: ScriptGuardianHeartbeatState,
        commandId: String?
    ): Boolean {
        val normalizedSessionId = sessionId?.trim().orEmpty()
        val normalizedCommandId = commandId?.trim()
        if (normalizedSessionId.isEmpty() || sequence <= 0L) return false
        if (state == ScriptGuardianHeartbeatState.BUSY && normalizedCommandId.isNullOrEmpty()) {
            return false
        }
        val report = ScriptGuardianHeartbeatReport(
            sessionId = normalizedSessionId,
            sequence = sequence,
            state = state,
            commandId = normalizedCommandId
        )
        return runCatching { sink.get()?.invoke(report) == true }.getOrDefault(false)
    }
}
