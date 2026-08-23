package org.autojs.autojs.guardian

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.autojs.autoxjs.R
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * A lightweight, separate-process foreground service that only checks whether the main process can
 * answer a package-private Messenger probe. It never executes business work or kills a process.
 */
class ScriptGuardianWatchdogService : Service() {
    private data class ProbeResult(val success: Boolean, val errorCode: String = "")

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var probeJob: Job? = null
    @Volatile
    private var config: ScriptGuardianConfig? = null
    @Volatile
    private var snapshot = ScriptGuardianWatchdogSnapshot()
    private var lastProbeAtElapsed = 0L
    private var lastSuccessAtElapsed = 0L
    private var lastProbeError = ""
    private var lastRecoveryError = ""
    @Volatile
    private var plugged = false
    private var batteryReceiverRegistered = false
    @Volatile
    private var foregroundStarted = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val nextPlugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (plugged != nextPlugged) {
                plugged = nextPlugged
                restartProbeLoop()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.onSuccess { sticky ->
            batteryReceiverRegistered = true
            plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        }.onFailure { error ->
            Log.w(TAG, "Could not observe charging state", error)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nextConfig = intent?.readConfig()
        if (nextConfig == null || resolveRunnableGuardianScript(nextConfig).isFailure) {
            config = null
            probeJob?.cancel()
            probeJob = null
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundIfNeeded()
        config = nextConfig
        if (probeJob?.isActive != true) {
            startProbeLoop()
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        probeJob?.cancel()
        probeJob = null
        if (batteryReceiverRegistered) {
            runCatching { unregisterReceiver(batteryReceiver) }
            batteryReceiverRegistered = false
        }
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun restartProbeLoop() {
        if (config == null) return
        probeJob?.cancel()
        probeJob = null
        startProbeLoop()
    }

    private fun startProbeLoop() {
        probeJob = scope.launch {
            while (isActive) {
                performProbeCycle()
                updateNotification()
                delay(if (plugged) PLUGGED_INTERVAL_MILLIS else UNPLUGGED_INTERVAL_MILLIS)
            }
        }
    }

    private suspend fun performProbeCycle(): ScriptGuardianWatchdogDecision {
        val wakeLock = runCatching {
            getSystemService(PowerManager::class.java)
                .newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "$packageName:GuardianWatchdogProbe"
                ).apply {
                    setReferenceCounted(false)
                    acquire(PROBE_WAKE_LOCK_TIMEOUT_MILLIS)
                }
        }.getOrNull()

        val requestId = REQUEST_IDS.incrementAndGet()
        val completed = CompletableDeferred<ProbeResult>()
        val replyMessenger = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (
                message.what == ScriptGuardianProbeProtocol.MSG_PROBE_RESULT &&
                message.data.getLong(ScriptGuardianProbeProtocol.KEY_REQUEST_ID) == requestId
            ) {
                val data = message.data
                val heartbeatAgeMillis = if (
                    data.containsKey(ScriptGuardianProbeProtocol.KEY_GUARDIAN_HEARTBEAT_AGE)
                ) {
                    data.getLong(ScriptGuardianProbeProtocol.KEY_GUARDIAN_HEARTBEAT_AGE)
                } else {
                    null
                }
                val guardianReady = isScriptGuardianProbeReady(
                    guardianState = data.getString(
                        ScriptGuardianProbeProtocol.KEY_GUARDIAN_STATE
                    ),
                    heartbeatAgeMillis = heartbeatAgeMillis
                )
                completed.complete(
                    if (guardianReady) {
                        ProbeResult(true)
                    } else {
                        ProbeResult(false, ERROR_GUARDIAN_NOT_READY)
                    }
                )
                true
            } else {
                false
            }
        })
        val remoteMessenger = AtomicReference<Messenger?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    completed.complete(ProbeResult(false, ERROR_NULL_BINDER))
                    return
                }
                val messenger = Messenger(service)
                remoteMessenger.set(messenger)
                val request = Message.obtain(null, ScriptGuardianProbeProtocol.MSG_PROBE).apply {
                    data = diagnosticsBundle().apply {
                        putLong(ScriptGuardianProbeProtocol.KEY_REQUEST_ID, requestId)
                    }
                    replyTo = replyMessenger
                }
                try {
                    messenger.send(request)
                } catch (_: RemoteException) {
                    completed.complete(ProbeResult(false, ERROR_SEND_FAILED))
                } catch (_: RuntimeException) {
                    completed.complete(ProbeResult(false, ERROR_SEND_FAILED))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                completed.complete(ProbeResult(false, ERROR_DISCONNECTED))
            }

            override fun onBindingDied(name: ComponentName?) {
                completed.complete(ProbeResult(false, ERROR_BINDING_DIED))
            }

            override fun onNullBinding(name: ComponentName?) {
                completed.complete(ProbeResult(false, ERROR_NULL_BINDER))
            }
        }

        var bound = false
        return try {
            bound = runCatching {
                bindService(
                    Intent(this, ScriptGuardianProcessProbeService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE
                )
            }.getOrDefault(false)
            val result = if (!bound) {
                ProbeResult(false, ERROR_BIND_FAILED)
            } else {
                withTimeoutOrNull(PROBE_TIMEOUT_MILLIS) { completed.await() }
                    ?: ProbeResult(false, ERROR_TIMEOUT)
            }
            val decision = recordProbeResult(result)
            requestRecovery(decision.action)
            remoteMessenger.get()?.let(::sendUpdatedDiagnostics)
            decision
        } finally {
            if (bound) runCatching { unbindService(connection) }
            if (wakeLock?.isHeld == true) runCatching { wakeLock.release() }
        }
    }

    private fun recordProbeResult(result: ProbeResult): ScriptGuardianWatchdogDecision {
        val now = SystemClock.elapsedRealtime()
        lastProbeAtElapsed = now
        if (result.success) {
            lastSuccessAtElapsed = now
            lastProbeError = ""
            lastRecoveryError = ""
        } else {
            lastProbeError = result.errorCode.take(80)
        }
        return reduceScriptGuardianWatchdog(snapshot, result.success).also {
            snapshot = it.snapshot
        }
    }

    private fun sendUpdatedDiagnostics(remote: Messenger) {
        val message = Message.obtain(null, ScriptGuardianProbeProtocol.MSG_DIAGNOSTICS).apply {
            data = diagnosticsBundle()
        }
        runCatching { remote.send(message) }
            .onFailure { Log.d(TAG, "Could not publish updated watchdog diagnostics", it) }
    }

    private fun requestRecovery(action: ScriptGuardianWatchdogAction) {
        val recoveryAction = scriptGuardianImmediateRecoveryAction(action) ?: return
        val scheduled = ScriptGuardianPrewarmScheduler.requestImmediateRecovery(
            applicationContext,
            recoveryAction
        )
        if (scheduled) {
            lastRecoveryError = ""
        } else {
            lastRecoveryError = ERROR_EXACT_RECOVERY_UNAVAILABLE
            Log.w(TAG, "Could not schedule exact Guardian recovery: $recoveryAction")
        }
    }

    private fun diagnosticsBundle() = Bundle().apply {
        putString(ScriptGuardianProbeProtocol.KEY_WATCHDOG_STATE, snapshot.state.wireValue)
        putInt(ScriptGuardianProbeProtocol.KEY_WATCHDOG_MISSES, snapshot.consecutiveMisses)
        putInt(
            ScriptGuardianProbeProtocol.KEY_WATCHDOG_SUCCESSES,
            snapshot.consecutiveSuccesses
        )
        putLong(ScriptGuardianProbeProtocol.KEY_WATCHDOG_LAST_PROBE_AT, lastProbeAtElapsed)
        putLong(ScriptGuardianProbeProtocol.KEY_WATCHDOG_LAST_SUCCESS_AT, lastSuccessAtElapsed)
        putString(
            ScriptGuardianProbeProtocol.KEY_WATCHDOG_LAST_ERROR,
            lastRecoveryError.ifBlank { lastProbeError }
        )
    }

    private fun startForegroundIfNeeded() {
        if (foregroundStarted) return
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        foregroundStarted = true
    }

    private fun updateNotification() {
        if (!foregroundStarted) return
        mainHandler.post {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        ensureNotificationChannel()
        val status = when (snapshot.state) {
            ScriptGuardianWatchdogState.STARTING -> R.string.script_guardian_watchdog_starting
            ScriptGuardianWatchdogState.HEALTHY -> R.string.script_guardian_watchdog_healthy
            ScriptGuardianWatchdogState.SUSPECT -> R.string.script_guardian_watchdog_suspect
            ScriptGuardianWatchdogState.RESTORING -> R.string.script_guardian_watchdog_restoring
            ScriptGuardianWatchdogState.UNRESPONSIVE ->
                R.string.script_guardian_watchdog_unresponsive
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.script_guardian_watchdog_title))
            .setContentText(getString(status))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.script_guardian_watchdog_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.script_guardian_watchdog_channel_description)
            }
        )
    }

    private fun Intent.readConfig(): ScriptGuardianConfig? {
        if (
            !hasExtra(EXTRA_ENABLED) ||
            !hasExtra(EXTRA_PATH) ||
            !hasExtra(EXTRA_SCRIPT_ROOT) ||
            !hasExtra(EXTRA_KEEP_SCREEN_ON_WHILE_CHARGING)
        ) {
            return null
        }
        return ScriptGuardianConfig(
            enabled = getBooleanExtra(EXTRA_ENABLED, false),
            relativePath = getStringExtra(EXTRA_PATH).orEmpty(),
            scriptRoot = getStringExtra(EXTRA_SCRIPT_ROOT).orEmpty(),
            keepScreenOnWhileCharging = getBooleanExtra(
                EXTRA_KEEP_SCREEN_ON_WHILE_CHARGING,
                false
            )
        )
    }

    companion object {
        private const val TAG = "GuardianWatchdog"
        private const val CHANNEL_ID = "script_guardian_watchdog"
        private const val NOTIFICATION_ID = 27193
        private const val PLUGGED_INTERVAL_MILLIS = 20_000L
        private const val UNPLUGGED_INTERVAL_MILLIS = 60_000L
        private const val PROBE_TIMEOUT_MILLIS = 3_000L
        private const val PROBE_WAKE_LOCK_TIMEOUT_MILLIS = 4_000L
        private const val ERROR_BIND_FAILED = "BIND_FAILED"
        private const val ERROR_NULL_BINDER = "NULL_BINDER"
        private const val ERROR_SEND_FAILED = "SEND_FAILED"
        private const val ERROR_DISCONNECTED = "DISCONNECTED"
        private const val ERROR_BINDING_DIED = "BINDING_DIED"
        private const val ERROR_TIMEOUT = "TIMEOUT"
        private const val ERROR_GUARDIAN_NOT_READY = "GUARDIAN_NOT_READY"
        private const val ERROR_EXACT_RECOVERY_UNAVAILABLE =
            "EXACT_RECOVERY_UNAVAILABLE"
        private const val EXTRA_ENABLED = "enabled"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_SCRIPT_ROOT = "script_root"
        private const val EXTRA_KEEP_SCREEN_ON_WHILE_CHARGING =
            "keep_screen_on_while_charging"
        private val REQUEST_IDS = AtomicLong()

        internal fun applyConfig(context: Context, config: ScriptGuardianConfig) {
            if (resolveRunnableGuardianScript(config).isFailure) {
                stop(context)
                return
            }
            val intent = Intent(context, ScriptGuardianWatchdogService::class.java).apply {
                putExtra(EXTRA_ENABLED, config.enabled)
                putExtra(EXTRA_PATH, config.relativePath)
                putExtra(EXTRA_SCRIPT_ROOT, config.scriptRoot)
                putExtra(
                    EXTRA_KEEP_SCREEN_ON_WHILE_CHARGING,
                    config.keepScreenOnWhileCharging
                )
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "Could not start Guardian watchdog", it) }
        }

        internal fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, ScriptGuardianWatchdogService::class.java))
            }.onFailure { Log.w(TAG, "Could not stop Guardian watchdog", it) }
        }
    }
}
