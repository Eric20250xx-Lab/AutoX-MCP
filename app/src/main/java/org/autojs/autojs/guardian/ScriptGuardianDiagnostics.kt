package org.autojs.autojs.guardian

import android.content.Context
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

private val PROCESS_INSTANCE_OWNER_ID = UUID.randomUUID().toString()

internal data class ScriptGuardianDiagnosticSnapshot(
    val state: String = STATE_STOPPED,
    val detail: String = "",
    val lastHeartbeatAt: Long = 0L,
    val sessionId: String = "",
    val sequence: Long = 0L,
    val wakeLockHeld: Boolean = false,
    val wakeLockUpdatedAt: Long = 0L,
    val wakeLockOwnerId: String = "",
    val lastRestoreAction: String = "",
    val lastRestoreAt: Long = 0L
) {
    fun hasFreshWakeLock(
        now: Long = System.currentTimeMillis(),
        ownerId: String = PROCESS_INSTANCE_OWNER_ID
    ): Boolean {
        val age = now - wakeLockUpdatedAt
        return wakeLockHeld &&
            wakeLockOwnerId == ownerId &&
            age in 0..ScriptGuardianWakeLockLease.LEASE_MILLIS
    }

    companion object {
        const val STATE_STOPPED = "STOPPED"
        const val STATE_STARTING = "STARTING"
        const val STATE_RUNNING = "RUNNING"
        const val STATE_IDLE = "IDLE"
        const val STATE_BUSY = "BUSY"
        const val STATE_STOPPING = "STOPPING"
        const val STATE_RETRYING = "RETRYING"
        const val STATE_BUSY_WARNING = "BUSY_WARNING"
    }
}

internal class ScriptGuardianDiagnostics(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var lastPersistedHeartbeatAt = 0L
    private var lastPersistedHeartbeatState = ""
    private var lastPersistedHeartbeatSessionId = ""

    init {
        if (latest.get() == null) latest.compareAndSet(null, read(prefs))
    }

    @Synchronized
    fun recordStatus(status: ScriptGuardianStatus) {
        val previous = snapshot(appContext)
        val now = System.currentTimeMillis()
        val next = when (status) {
            ScriptGuardianStatus.Disabled -> previous.copy(
                state = ScriptGuardianDiagnosticSnapshot.STATE_STOPPED,
                detail = ""
            )

            is ScriptGuardianStatus.Starting -> previous.copy(
                state = ScriptGuardianDiagnosticSnapshot.STATE_STARTING,
                detail = status.file.name
            )

            is ScriptGuardianStatus.Running -> status.heartbeat?.let { heartbeat ->
                previous.copy(
                    state = heartbeat.state.name,
                    detail = heartbeat.commandId.orEmpty().sanitize(),
                    lastHeartbeatAt = now,
                    sessionId = heartbeat.sessionId,
                    sequence = heartbeat.sequence
                )
            } ?: previous.copy(
                state = ScriptGuardianDiagnosticSnapshot.STATE_RUNNING,
                detail = status.file.name
            )

            is ScriptGuardianStatus.BusyWarning -> previous.copy(
                state = ScriptGuardianDiagnosticSnapshot.STATE_BUSY_WARNING,
                detail = status.commandId.sanitize()
            )

            is ScriptGuardianStatus.Stopping -> previous.copy(
                state = ScriptGuardianDiagnosticSnapshot.STATE_STOPPING,
                detail = status.file.name
            )

            is ScriptGuardianStatus.Retrying -> previous.copy(
                state = ScriptGuardianDiagnosticSnapshot.STATE_RETRYING,
                detail = status.reason.sanitize()
            )
        }
        latest.set(next)

        val heartbeat = (status as? ScriptGuardianStatus.Running)?.heartbeat
        val shouldPersist = heartbeat == null || shouldPersistGuardianHeartbeat(
            lastPersistedAt = lastPersistedHeartbeatAt,
            lastPersistedState = lastPersistedHeartbeatState,
            lastPersistedSessionId = lastPersistedHeartbeatSessionId,
            now = now,
            state = heartbeat.state.name,
            sessionId = heartbeat.sessionId,
            persistIntervalMillis = HEARTBEAT_PERSIST_INTERVAL_MILLIS
        )
        if (shouldPersist) {
            write(prefs, next)
            if (heartbeat != null) {
                lastPersistedHeartbeatAt = now
                lastPersistedHeartbeatState = heartbeat.state.name
                lastPersistedHeartbeatSessionId = heartbeat.sessionId
            }
        }
    }

    @Synchronized
    fun recordWakeLock(held: Boolean, error: Throwable? = null) {
        val previous = snapshot(appContext)
        val next = previous.copy(
            wakeLockHeld = held,
            wakeLockUpdatedAt = System.currentTimeMillis(),
            wakeLockOwnerId = if (held) PROCESS_INSTANCE_OWNER_ID else "",
            detail = error?.let { describe(it).sanitize() } ?: previous.detail
        )
        latest.set(next)
        write(prefs, next)
    }

    companion object {
        private const val PREFS_NAME = "script_guardian_diagnostics"
        private const val HEARTBEAT_PERSIST_INTERVAL_MILLIS = 60_000L
        private const val MAX_DETAIL_LENGTH = 160
        private val latest = AtomicReference<ScriptGuardianDiagnosticSnapshot?>(null)

        fun snapshot(context: Context): ScriptGuardianDiagnosticSnapshot {
            latest.get()?.let { return it }
            val prefs = context.applicationContext.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            return read(prefs).also { latest.compareAndSet(null, it) }
        }

        fun recordRestoreAction(context: Context, action: String?) {
            val prefs = context.applicationContext.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            val next = snapshot(context).copy(
                lastRestoreAction = action.orEmpty().sanitize(),
                lastRestoreAt = System.currentTimeMillis()
            )
            latest.set(next)
            write(prefs, next, synchronous = true)
        }

        private fun read(prefs: android.content.SharedPreferences) =
            ScriptGuardianDiagnosticSnapshot(
                state = prefs.getString("state", null)
                    ?: ScriptGuardianDiagnosticSnapshot.STATE_STOPPED,
                detail = prefs.getString("detail", "").orEmpty(),
                lastHeartbeatAt = prefs.getLong("last_heartbeat_at", 0L),
                sessionId = prefs.getString("session_id", "").orEmpty(),
                sequence = prefs.getLong("sequence", 0L),
                wakeLockHeld = prefs.getBoolean("wake_lock_held", false),
                wakeLockUpdatedAt = prefs.getLong("wake_lock_updated_at", 0L),
                wakeLockOwnerId = prefs.getString("wake_lock_owner_id", "").orEmpty(),
                lastRestoreAction = prefs.getString("last_restore_action", "").orEmpty(),
                lastRestoreAt = prefs.getLong("last_restore_at", 0L)
            )

        private fun write(
            prefs: android.content.SharedPreferences,
            snapshot: ScriptGuardianDiagnosticSnapshot,
            synchronous: Boolean = false
        ) {
            val editor = prefs.edit()
                .putString("state", snapshot.state)
                .putString("detail", snapshot.detail)
                .putLong("last_heartbeat_at", snapshot.lastHeartbeatAt)
                .putString("session_id", snapshot.sessionId)
                .putLong("sequence", snapshot.sequence)
                .putBoolean("wake_lock_held", snapshot.wakeLockHeld)
                .putLong("wake_lock_updated_at", snapshot.wakeLockUpdatedAt)
                .putString("wake_lock_owner_id", snapshot.wakeLockOwnerId)
                .putString("last_restore_action", snapshot.lastRestoreAction)
                .putLong("last_restore_at", snapshot.lastRestoreAt)
            if (synchronous) editor.commit() else editor.apply()
        }

        private fun describe(error: Throwable): String =
            error.message?.takeIf { it.isNotBlank() }
                ?.let { "${error.javaClass.simpleName}: $it" }
                ?: error.javaClass.simpleName

        private fun String.sanitize(): String =
            replace('\n', ' ').replace('\r', ' ').take(MAX_DETAIL_LENGTH)
    }
}

internal fun shouldPersistGuardianHeartbeat(
    lastPersistedAt: Long,
    lastPersistedState: String,
    lastPersistedSessionId: String,
    now: Long,
    state: String,
    sessionId: String,
    persistIntervalMillis: Long
): Boolean = sessionId != lastPersistedSessionId ||
    state != lastPersistedState ||
    now - lastPersistedAt >= persistIntervalMillis
