package org.autojs.autojs.guardian

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log

/** Minimal main-process endpoint used only by the package-private Guardian watchdog. */
class ScriptGuardianProcessProbeService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            ScriptGuardianProbeProtocol.MSG_PROBE -> {
                ScriptGuardianRuntimeDiagnostics.updateWatchdog(message.data)
                val (epoch, startedAt) = ScriptGuardianRuntimeDiagnostics.recordNativePulse()
                val guardian = ScriptGuardianRuntimeDiagnostics.guardianProbeSnapshot()
                val response = Message.obtain(
                    null,
                    ScriptGuardianProbeProtocol.MSG_PROBE_RESULT
                ).apply {
                    data = Bundle().apply {
                        putLong(
                            ScriptGuardianProbeProtocol.KEY_REQUEST_ID,
                            message.data.getLong(ScriptGuardianProbeProtocol.KEY_REQUEST_ID)
                        )
                        putString(ScriptGuardianProbeProtocol.KEY_PROCESS_EPOCH, epoch)
                        putLong(ScriptGuardianProbeProtocol.KEY_PROCESS_STARTED_AT, startedAt)
                        putString(
                            ScriptGuardianProbeProtocol.KEY_GUARDIAN_STATE,
                            guardian.state
                        )
                        guardian.heartbeatAgeMillis?.let {
                            putLong(
                                ScriptGuardianProbeProtocol.KEY_GUARDIAN_HEARTBEAT_AGE,
                                it
                            )
                        }
                    }
                }
                runCatching { message.replyTo?.send(response) }
                    .onFailure { Log.w(TAG, "Could not reply to Guardian watchdog probe", it) }
                true
            }

            ScriptGuardianProbeProtocol.MSG_DIAGNOSTICS -> {
                ScriptGuardianRuntimeDiagnostics.updateWatchdog(message.data)
                true
            }

            else -> false
        }
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    companion object {
        private const val TAG = "GuardianProcessProbe"
    }
}
