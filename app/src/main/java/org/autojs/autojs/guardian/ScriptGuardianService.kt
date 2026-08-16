package org.autojs.autojs.guardian

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.autojs.autoxjs.R

class ScriptGuardianService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var supervisor: ScriptGuardianSupervisor

    @Volatile
    private var foregroundStarted = false
    private var stopping = false
    private var configEnabled = false
    private var invalidConfigReason: String? = null
    private var pendingConfig: ScriptGuardianConfig? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundIfNeeded(getString(R.string.script_guardian_status_waiting))
        supervisor = createSupervisor()
    }

    private fun createSupervisor() = ScriptGuardianSupervisor(
        scope = serviceScope,
        runner = EngineScriptGuardianRunner(),
        onStatus = ::renderStatus
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundIfNeeded(getString(R.string.script_guardian_status_waiting))
        if (intent?.action == ACTION_STOP) {
            ScriptGuardianExecutionGuard.updateConfig(null)
            pendingConfig = null
            processConfig = null
            stopGuardian()
            return START_NOT_STICKY
        }

        val config = if (intent?.action == ACTION_APPLY_CONFIG) {
            intent.readConfig() ?: ScriptGuardianPrefs.load(this)
        } else {
            processConfig ?: ScriptGuardianPrefs.load(this)
        }
        processConfig = config
        ScriptGuardianExecutionGuard.updateConfig(config)
        if (stopping) {
            if (config.enabled) {
                pendingConfig = config
            }
            return if (config.enabled) START_STICKY else START_NOT_STICKY
        }
        applyConfig(config)
        return if (config.enabled) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        if (::supervisor.isInitialized) {
            supervisor.closeNow()
        }
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun applyConfig(config: ScriptGuardianConfig) {
        configEnabled = config.enabled
        if (!config.enabled) {
            processConfig = null
            stopGuardian()
            return
        }

        config.resolveScriptFile().fold(
            onSuccess = { file ->
                invalidConfigReason = null
                supervisor.reconcile(file)
            },
            onFailure = { error ->
                invalidConfigReason = error.message ?: error.javaClass.simpleName
                supervisor.reconcile(null)
                updateNotification(getString(R.string.script_guardian_status_waiting))
            }
        )
    }

    private fun stopGuardian() {
        if (stopping) return
        stopping = true
        configEnabled = false
        serviceScope.launch {
            supervisor.close()
            mainHandler.post {
                val nextConfig = pendingConfig
                pendingConfig = null
                if (nextConfig?.enabled == true) {
                    stopping = false
                    supervisor = createSupervisor()
                    processConfig = nextConfig
                    applyConfig(nextConfig)
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    foregroundStarted = false
                    stopSelf()
                }
            }
        }
    }

    private fun renderStatus(status: ScriptGuardianStatus) {
        val text = when (status) {
            ScriptGuardianStatus.Disabled -> {
                if (configEnabled && invalidConfigReason != null) {
                    getString(R.string.script_guardian_status_waiting)
                } else {
                    getString(R.string.script_guardian_status_stopped)
                }
            }

            is ScriptGuardianStatus.Starting -> getString(
                R.string.script_guardian_status_starting,
                status.file.name
            )

            is ScriptGuardianStatus.Running -> getString(
                R.string.script_guardian_status_running,
                status.file.name
            )

            is ScriptGuardianStatus.Stopping -> getString(
                R.string.script_guardian_status_stopping,
                status.file.name
            )

            is ScriptGuardianStatus.Retrying -> getString(
                R.string.script_guardian_status_retrying,
                status.delayMillis / 1_000,
                status.file.name
            )
        }
        updateNotification(text)
    }

    private fun startForegroundIfNeeded(text: String) {
        if (foregroundStarted) return
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), type)
        foregroundStarted = true
    }

    private fun updateNotification(text: String) {
        if (!foregroundStarted) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        ensureNotificationChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.script_guardian_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.script_guardian_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = getString(R.string.script_guardian_notification_channel_description)
        manager.createNotificationChannel(channel)
    }

    private fun Intent.readConfig(): ScriptGuardianConfig? {
        if (!hasExtra(EXTRA_ENABLED) || !hasExtra(EXTRA_PATH) || !hasExtra(EXTRA_SCRIPT_ROOT)) {
            return null
        }
        return ScriptGuardianConfig(
            enabled = getBooleanExtra(EXTRA_ENABLED, false),
            relativePath = getStringExtra(EXTRA_PATH).orEmpty(),
            scriptRoot = getStringExtra(EXTRA_SCRIPT_ROOT).orEmpty()
        )
    }

    companion object {
        private const val CHANNEL_ID = "script_guardian"
        private const val NOTIFICATION_ID = 27191
        private const val ACTION_APPLY_CONFIG =
            "org.autojs.autojs.guardian.action.APPLY_CONFIG"
        private const val ACTION_STOP = "org.autojs.autojs.guardian.action.STOP"
        private const val EXTRA_ENABLED = "enabled"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_SCRIPT_ROOT = "script_root"

        @Volatile
        private var processConfig: ScriptGuardianConfig? = null

        internal fun applyConfig(context: Context, config: ScriptGuardianConfig) {
            val intent = Intent(context, ScriptGuardianService::class.java).apply {
                action = ACTION_APPLY_CONFIG
                putExtra(EXTRA_ENABLED, config.enabled)
                putExtra(EXTRA_PATH, config.relativePath)
                putExtra(EXTRA_SCRIPT_ROOT, config.scriptRoot)
            }
            startServiceSafely(context, intent)
        }

        internal fun restore(context: Context) {
            val config = ScriptGuardianPrefs.load(context)
            if (config.enabled) {
                applyConfig(context, config)
            }
        }

        internal fun stop(context: Context) {
            val intent = Intent(context, ScriptGuardianService::class.java).apply {
                action = ACTION_STOP
            }
            startServiceSafely(context, intent)
        }

        private fun startServiceSafely(context: Context, intent: Intent): Boolean = try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (error: RuntimeException) {
            Log.w("ScriptGuardianService", "Could not start Script Guardian service", error)
            false
        }
    }
}
