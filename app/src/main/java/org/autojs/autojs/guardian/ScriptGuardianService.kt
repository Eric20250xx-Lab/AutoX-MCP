package org.autojs.autojs.guardian

import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Path
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.autojs.autoxjs.R
import com.stardust.automator.AccessibilityGestureCoordinator
import com.stardust.view.accessibility.AccessibilityService as AutoXAccessibilityService
import java.io.File

internal fun resolveRunnableGuardianScript(config: ScriptGuardianConfig): Result<File> =
    config.resolveScriptFile().mapCatching { file ->
        require(file.isFile) { "script file does not exist: ${file.path}" }
        file
    }

internal fun shouldDispatchGuardianKeyguardGesture(
    destroyed: Boolean,
    recoveryActive: Boolean,
    recoveryJobActive: Boolean,
    guardianAllowsGesture: Boolean
): Boolean = !destroyed && recoveryActive && recoveryJobActive && guardianAllowsGesture

class ScriptGuardianService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var supervisor: ScriptGuardianSupervisor
    private lateinit var runner: EngineScriptGuardianRunner
    private lateinit var diagnostics: ScriptGuardianDiagnostics
    private lateinit var wakeLockLease: ScriptGuardianWakeLockLease
    private lateinit var screenWakeLockLease: ScriptGuardianWakeLockLease
    private lateinit var screenWakeupFactory: ScriptGuardianWakeLockFactory
    private lateinit var powerManager: PowerManager
    private lateinit var keyguardManager: KeyguardManager
    private val screenWakePolicy = ScriptGuardianScreenWakePolicy()
    private var screenRecoveryJob: Job? = null
    private var batteryReceiverRegistered = false
    private var screenReceiverRegistered = false
    private var plugged = 0
    private var screenWakeConfigValid = false
    private var keepScreenOnWhileCharging = false
    private val heartbeatSink: (ScriptGuardianHeartbeatReport) -> Boolean = { report ->
        if (canAwaitScriptGuardianBridgeAck() && ::supervisor.isInitialized) {
            supervisor.reportHeartbeat(report)
        } else {
            false
        }
    }
    private val heartbeatExpectationSink: (String) -> Boolean = { sessionId ->
        if (canAwaitScriptGuardianBridgeAck() && ::supervisor.isInitialized) {
            supervisor.expectHeartbeat(sessionId)
        } else {
            false
        }
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            updateScreenWakeLock()
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val decision = when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> screenWakePolicy.onScreenOff(
                    SystemClock.elapsedRealtime()
                )

                Intent.ACTION_SCREEN_ON -> screenWakePolicy.onScreenOn(
                    SystemClock.elapsedRealtime()
                )
                else -> return
            }
            applyScreenWakeDecision(decision, intent.action.orEmpty())
        }
    }

    @Volatile
    private var foregroundStarted = false
    @Volatile
    private var destroyed = false
    private var stopping = false
    private var configEnabled = false
    private var invalidConfigReason: String? = null
    private var pendingConfig: ScriptGuardianConfig? = null

    override fun onCreate() {
        super.onCreate()
        destroyed = false
        startForegroundIfNeeded(getString(R.string.script_guardian_status_waiting))
        diagnostics = ScriptGuardianDiagnostics(applicationContext)
        runner = EngineScriptGuardianRunner(applicationContext)
        supervisor = createSupervisor()
        wakeLockLease = ScriptGuardianWakeLockLease(
            scope = serviceScope,
            factory = ScriptGuardianWakeLockLease.androidFactory(applicationContext),
            onHeldChanged = { held -> diagnostics.recordWakeLock(held) },
            onFailure = { error ->
                Log.w("ScriptGuardianService", "Script Guardian wake lock failure", error)
                diagnostics.recordWakeLock(wakeLockLease.isHeld(), error)
            }
        )
        powerManager = getSystemService(PowerManager::class.java)
        keyguardManager = getSystemService(KeyguardManager::class.java)
        screenWakeLockLease = ScriptGuardianWakeLockLease(
            scope = serviceScope,
            factory = ScriptGuardianWakeLockLease.androidFactory(
                applicationContext,
                PowerManager.SCREEN_DIM_WAKE_LOCK,
                "$packageName:ScriptGuardianScreen"
            ),
            onFailure = { error ->
                Log.w("ScriptGuardianService", "Script Guardian screen wake lock failure", error)
            }
        )
        screenWakeupFactory = ScriptGuardianWakeLockLease.androidFactory(
            applicationContext,
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "$packageName:ScriptGuardianScreenRecovery"
        )
        runCatching {
            registerReceiver(
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                }
            )
        }.onSuccess {
            screenReceiverRegistered = true
        }.onFailure { error ->
            Log.w("ScriptGuardianService", "Could not observe screen state", error)
        }
        runCatching {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.onSuccess { stickyBatteryIntent ->
            batteryReceiverRegistered = true
            plugged = stickyBatteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            updateScreenWakeLock()
        }.onFailure { error ->
            Log.w("ScriptGuardianService", "Could not observe charging state", error)
        }
        ScriptGuardianHeartbeat.bind(heartbeatSink)
        ScriptGuardianHeartbeat.bindExpectation(heartbeatExpectationSink)
    }

    private fun createSupervisor() = ScriptGuardianSupervisor(
        scope = serviceScope,
        runner = runner,
        onStatus = ::renderStatus
    )

    private fun canAwaitScriptGuardianBridgeAck(): Boolean =
        shouldAwaitScriptGuardianBridgeAck(
            isMainThread = Looper.myLooper() == Looper.getMainLooper()
        )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundIfNeeded(getString(R.string.script_guardian_status_waiting))
        if (intent?.action == ACTION_STOP) {
            pendingConfig = null
            processConfig = null
            stopGuardian()
            return START_NOT_STICKY
        }

        val watchdogRecoveryRequest = intent?.action == ACTION_VERIFY_AND_RECOVER
        val config = when {
            watchdogRecoveryRequest -> {
                val current = ScriptGuardianPrefs.load(this)
                if (!current.enabled) {
                    pendingConfig = null
                    processConfig = null
                    stopGuardian()
                    return START_NOT_STICKY
                }
                current
            }

            intent?.action == ACTION_APPLY_CONFIG ->
                intent.readConfig() ?: ScriptGuardianPrefs.load(this)

            else -> processConfig ?: ScriptGuardianPrefs.load(this)
        }
        if (intent?.action == ACTION_VERIFY_AND_RECOVER) {
            ScriptGuardianRuntimeDiagnostics.recordRestore(intent.action)
        }
        processConfig = config
        val runnableConfig = resolveRunnableGuardianScript(config).isSuccess
        if (stopping) {
            if (runnableConfig) {
                pendingConfig = config
            } else {
                pendingConfig = null
            }
            return if (runnableConfig) START_STICKY else START_NOT_STICKY
        }
        applyConfig(config)
        return if (runnableConfig) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        cancelScreenRecovery()
        mainHandler.removeCallbacksAndMessages(null)
        ScriptGuardianHeartbeat.unbind(heartbeatSink)
        ScriptGuardianHeartbeat.unbindExpectation(heartbeatExpectationSink)
        if (::wakeLockLease.isInitialized) {
            wakeLockLease.stop()
        }
        if (::screenWakeLockLease.isInitialized) {
            screenWakeLockLease.stop()
        }
        if (batteryReceiverRegistered) {
            runCatching { unregisterReceiver(batteryReceiver) }
            batteryReceiverRegistered = false
        }
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            screenReceiverRegistered = false
        }
        if (::supervisor.isInitialized) {
            supervisor.closeNow()
        }
        if (::runner.isInitialized) {
            runner.close()
        }
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun applyConfig(config: ScriptGuardianConfig) {
        configEnabled = config.enabled
        keepScreenOnWhileCharging = config.keepScreenOnWhileCharging
        if (!config.enabled) {
            screenWakeConfigValid = false
            updateScreenWakeLock()
            processConfig = null
            stopGuardian()
            return
        }

        resolveRunnableGuardianScript(config).fold(
            onSuccess = { file ->
                invalidConfigReason = null
                screenWakeConfigValid = true
                updateScreenWakeLock()
                wakeLockLease.start()
                supervisor.reconcile(file)
            },
            onFailure = { error ->
                screenWakeConfigValid = false
                updateScreenWakeLock()
                invalidConfigReason = error.message ?: error.javaClass.simpleName
                updateNotification(getString(R.string.script_guardian_status_waiting))
                pendingConfig = null
                processConfig = null
                stopGuardian()
            }
        )
    }

    private fun stopGuardian() {
        if (stopping) return
        stopping = true
        configEnabled = false
        screenWakeConfigValid = false
        updateScreenWakeLock()
        wakeLockLease.stop()
        serviceScope.launch {
            supervisor.close()
            releaseGuardUntilSuccessful()
            mainHandler.post {
                val nextConfig = pendingConfig
                pendingConfig = null
                if (nextConfig?.enabled == true) {
                    stopping = false
                    supervisor = createSupervisor()
                    processConfig = nextConfig
                    applyConfig(nextConfig)
                } else {
                    runner.close()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    foregroundStarted = false
                    stopSelf()
                }
            }
        }
    }

    private suspend fun releaseGuardUntilSuccessful() {
        while (serviceJob.isActive) {
            try {
                runner.releaseGuard()
                return
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Keep the foreground service alive until the remote process acknowledges release.
            }
            updateNotification(getString(R.string.script_guardian_status_waiting))
            delay(GUARD_RELEASE_RETRY_MILLIS)
        }
    }

    private fun updateScreenWakeLock() {
        if (!::screenWakeLockLease.isInitialized || !::powerManager.isInitialized) return
        val eligible = ScriptGuardianScreenWakePolicy.shouldHold(
            configValid = configEnabled && screenWakeConfigValid,
            userOptIn = keepScreenOnWhileCharging,
            plugged = plugged
        )
        val decision = screenWakePolicy.updateEligibility(
            eligible = eligible,
            recovered = currentKeyguardAction().let(
                ScriptGuardianKeyguardPolicy::isRecoveryComplete
            ),
            nowMillis = SystemClock.elapsedRealtime()
        )
        applyScreenWakeDecision(decision, "eligibility")
    }

    private fun applyScreenWakeDecision(
        decision: ScriptGuardianScreenWakePolicy.Decision,
        reason: String
    ) {
        if (!::screenWakeLockLease.isInitialized) return
        if (decision.shouldHoldLease) {
            screenWakeLockLease.start()
        } else {
            screenWakeLockLease.stop()
        }
        when (decision.recoveryAction) {
            ScriptGuardianScreenWakePolicy.RecoveryAction.START -> {
                val recoveryGeneration = decision.recoveryGeneration
                if (recoveryGeneration == null) {
                    Log.w(
                        "ScriptGuardianService",
                        "Screen wake $reason missing recovery generation"
                    )
                } else {
                    scheduleScreenRecovery(reason, recoveryGeneration)
                }
            }

            ScriptGuardianScreenWakePolicy.RecoveryAction.CANCEL -> {
                cancelScreenRecovery()
            }

            ScriptGuardianScreenWakePolicy.RecoveryAction.NONE,
            ScriptGuardianScreenWakePolicy.RecoveryAction.RETRY -> Unit
        }
        if (decision.recoveryAction != ScriptGuardianScreenWakePolicy.RecoveryAction.NONE) {
            Log.i(
                "ScriptGuardianService",
                "Screen wake $reason: state=${screenWakePolicy.state}, " +
                    "action=${decision.recoveryAction}"
            )
        }
    }

    private fun scheduleScreenRecovery(reason: String, recoveryGeneration: Long) {
        if (destroyed) return
        cancelScreenRecovery()
        screenRecoveryJob = serviceScope.launch {
            delay(SCREEN_RECOVERY_INITIAL_DELAY_MILLIS)
            while (serviceJob.isActive) {
                val initialKeyguardAction = currentKeyguardActionOnMain()
                val waitedForAccessibility =
                    initialKeyguardAction ==
                        ScriptGuardianKeyguardPolicy.Action.WAIT_FOR_ACCESSIBILITY
                var keyguardAction = if (waitedForAccessibility) {
                    awaitAccessibilityIfNeeded(recoveryGeneration, initialKeyguardAction)
                } else {
                    initialKeyguardAction
                }
                if (ScriptGuardianKeyguardPolicy.isRecoveryComplete(keyguardAction)) {
                    screenWakePolicy.onRecoveryChecked(
                        recoveryGeneration = recoveryGeneration,
                        recovered = true,
                        nowMillis = SystemClock.elapsedRealtime()
                    )
                    return@launch
                }
                if (!screenWakePolicy.beginRecoveryAttempt(recoveryGeneration)) return@launch

                val attempt = screenWakePolicy.recoveryAttempts
                val refreshed = if (
                    keyguardAction == ScriptGuardianKeyguardPolicy.Action.WAIT_FOR_SCREEN
                ) {
                    screenWakeLockLease.refreshNow(screenWakeupFactory)
                } else {
                    false
                }
                Log.i(
                    "ScriptGuardianService",
                    "Screen wake $reason generation=$recoveryGeneration " +
                        "attempt=$attempt leaseRefreshed=$refreshed"
                )
                if (keyguardAction == ScriptGuardianKeyguardPolicy.Action.WAIT_FOR_SCREEN) {
                    delay(SCREEN_RECOVERY_CONFIRM_DELAY_MILLIS)
                }

                keyguardAction = currentKeyguardActionOnMain()
                if (!waitedForAccessibility) {
                    keyguardAction = awaitAccessibilityIfNeeded(
                        recoveryGeneration,
                        keyguardAction
                    )
                }
                val gestureAccepted = if (
                    keyguardAction == ScriptGuardianKeyguardPolicy.Action.DISMISS
                ) {
                    dispatchNonSecureKeyguardSwipe(recoveryGeneration)
                } else {
                    false
                }
                if (gestureAccepted) {
                    delay(KEYGUARD_DISMISS_CONFIRM_DELAY_MILLIS)
                }
                val finalKeyguardAction = currentKeyguardActionOnMain()
                val recovered = ScriptGuardianKeyguardPolicy.isRecoveryComplete(
                    finalKeyguardAction
                )

                val decision = screenWakePolicy.onRecoveryChecked(
                    recoveryGeneration = recoveryGeneration,
                    recovered = recovered,
                    nowMillis = SystemClock.elapsedRealtime()
                )
                Log.i(
                    "ScriptGuardianService",
                    "Screen wake $reason checked: state=${screenWakePolicy.state}, " +
                        "keyguardAction=$finalKeyguardAction, " +
                        "gestureAccepted=$gestureAccepted, " +
                        "action=${decision.recoveryAction}"
                )
                if (decision.recoveryAction != ScriptGuardianScreenWakePolicy.RecoveryAction.RETRY) {
                    return@launch
                }
                delay(KEYGUARD_RETRY_DELAY_MILLIS)
            }
        }
    }

    private fun currentKeyguardAction(): ScriptGuardianKeyguardPolicy.Action {
        if (!::powerManager.isInitialized || !::keyguardManager.isInitialized) {
            return ScriptGuardianKeyguardPolicy.Action.WAIT_FOR_SCREEN
        }
        return ScriptGuardianKeyguardPolicy.decide(
            interactive = powerManager.isInteractive,
            keyguardLocked = keyguardManager.isKeyguardLocked,
            deviceSecure = keyguardManager.isDeviceSecure,
            accessibilityAvailable = AutoXAccessibilityService.instance != null
        )
    }

    private suspend fun currentKeyguardActionOnMain(): ScriptGuardianKeyguardPolicy.Action {
        val result = CompletableDeferred<ScriptGuardianKeyguardPolicy.Action>()
        val posted = mainHandler.post {
            result.complete(currentKeyguardAction())
        }
        if (!posted) return ScriptGuardianKeyguardPolicy.Action.WAIT_FOR_SCREEN
        return withTimeoutOrNull(KEYGUARD_STATE_TIMEOUT_MILLIS) {
            result.await()
        } ?: ScriptGuardianKeyguardPolicy.Action.WAIT_FOR_SCREEN
    }

    private suspend fun awaitAccessibilityIfNeeded(
        recoveryGeneration: Long,
        initialAction: ScriptGuardianKeyguardPolicy.Action
    ): ScriptGuardianKeyguardPolicy.Action {
        var action = initialAction
        val deadline = SystemClock.elapsedRealtime() + KEYGUARD_ACCESSIBILITY_WAIT_MILLIS
        while (
            action == ScriptGuardianKeyguardPolicy.Action.WAIT_FOR_ACCESSIBILITY &&
            screenWakePolicy.isRecoveryActive(recoveryGeneration)
        ) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) break
            delay(minOf(KEYGUARD_ACCESSIBILITY_POLL_MILLIS, remaining))
            action = currentKeyguardActionOnMain()
        }
        return action
    }

    private suspend fun dispatchNonSecureKeyguardSwipe(recoveryGeneration: Long): Boolean {
        val gestureLease = acquireKeyguardGestureLease(recoveryGeneration) ?: return false
        val result = CompletableDeferred<Boolean>()
        val dispatch = Runnable {
            if (!shouldDispatchGuardianKeyguardGesture(
                    destroyed = destroyed,
                    recoveryActive = screenWakePolicy.isRecoveryActive(recoveryGeneration),
                    recoveryJobActive = screenRecoveryJob?.isActive == true,
                    guardianAllowsGesture = supervisor.allowsBackgroundKeyguardGesture()
                )
            ) {
                gestureLease.close()
                result.complete(false)
                return@Runnable
            }
            val action = currentKeyguardAction()
            val accessibilityService = AutoXAccessibilityService.instance
            if (
                action != ScriptGuardianKeyguardPolicy.Action.DISMISS ||
                accessibilityService == null
            ) {
                gestureLease.close()
                result.complete(false)
                return@Runnable
            }

            val metrics = resources.displayMetrics
            val centerX = metrics.widthPixels / 2f
            val path = Path().apply {
                moveTo(centerX, metrics.heightPixels * KEYGUARD_SWIPE_START_HEIGHT_RATIO)
                lineTo(centerX, metrics.heightPixels * KEYGUARD_SWIPE_END_HEIGHT_RATIO)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0L,
                        KEYGUARD_SWIPE_DURATION_MILLIS
                    )
                )
                .build()
            val callback = object : android.accessibilityservice.AccessibilityService
                .GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    gestureLease.close()
                    result.complete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    gestureLease.close()
                    result.complete(false)
                }
            }
            val accepted = runCatching {
                accessibilityService.dispatchGesture(gesture, callback, mainHandler)
            }.getOrElse { error ->
                Log.w(
                    "ScriptGuardianService",
                    "Could not dispatch non-secure keyguard swipe",
                    error
                )
                false
            }
            if (!accepted) {
                gestureLease.close()
                result.complete(false)
            }
        }
        return try {
            currentCoroutineContext().ensureActive()
            if (!mainHandler.post(dispatch)) return false
            withTimeoutOrNull(KEYGUARD_GESTURE_TIMEOUT_MILLIS) {
                result.await()
            } ?: false
        } finally {
            mainHandler.removeCallbacks(dispatch)
            gestureLease.close()
        }
    }

    private suspend fun acquireKeyguardGestureLease(
        recoveryGeneration: Long
    ): AccessibilityGestureCoordinator.Lease? {
        while (!destroyed && screenWakePolicy.isRecoveryActive(recoveryGeneration)) {
            currentCoroutineContext().ensureActive()
            AccessibilityGestureCoordinator.tryAcquire()?.let { lease ->
                return if (
                    !destroyed &&
                    screenWakePolicy.isRecoveryActive(recoveryGeneration) &&
                    screenRecoveryJob?.isActive == true
                ) {
                    lease
                } else {
                    lease.close()
                    null
                }
            }
            delay(KEYGUARD_GESTURE_LEASE_POLL_MILLIS)
        }
        return null
    }

    private fun cancelScreenRecovery() {
        screenRecoveryJob?.cancel()
        screenRecoveryJob = null
    }

    private fun renderStatus(status: ScriptGuardianStatus) {
        diagnostics.recordStatus(status)
        ScriptGuardianRuntimeDiagnostics.updateGuardian(status)
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

            is ScriptGuardianStatus.Running -> when (status.heartbeat?.state) {
                ScriptGuardianHeartbeatState.IDLE -> getString(
                    R.string.script_guardian_status_idle,
                    status.file.name
                )

                ScriptGuardianHeartbeatState.BUSY -> getString(
                    R.string.script_guardian_status_busy,
                    status.file.name
                )

                null -> getString(
                    R.string.script_guardian_status_running,
                    status.file.name
                )
            }

            is ScriptGuardianStatus.BusyWarning -> getString(
                R.string.script_guardian_status_busy_warning,
                status.file.name
            )

            is ScriptGuardianStatus.Stopping -> getString(
                R.string.script_guardian_status_stopping,
                status.file.name
            )

            is ScriptGuardianStatus.Retrying -> getString(
                R.string.script_guardian_status_retrying,
                status.delayMillis / 1_000,
                status.file.name,
                status.reason.replace('\n', ' ').replace('\r', ' ').take(80)
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
        private const val CHANNEL_ID = "script_guardian"
        private const val NOTIFICATION_ID = 27191
        private const val ACTION_APPLY_CONFIG =
            "org.autojs.autojs.guardian.action.APPLY_CONFIG"
        internal const val ACTION_VERIFY_AND_RECOVER =
            "org.autojs.autojs.guardian.action.VERIFY_AND_RECOVER"
        private const val ACTION_STOP = "org.autojs.autojs.guardian.action.STOP"
        private const val EXTRA_ENABLED = "enabled"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_SCRIPT_ROOT = "script_root"
        private const val EXTRA_KEEP_SCREEN_ON_WHILE_CHARGING =
            "keep_screen_on_while_charging"
        private const val GUARD_RELEASE_RETRY_MILLIS = 5_000L
        private const val SCREEN_RECOVERY_INITIAL_DELAY_MILLIS = 1_000L
        private const val SCREEN_RECOVERY_CONFIRM_DELAY_MILLIS = 2_000L
        private const val KEYGUARD_DISMISS_CONFIRM_DELAY_MILLIS = 750L
        private const val KEYGUARD_ACCESSIBILITY_WAIT_MILLIS = 15_000L
        private const val KEYGUARD_ACCESSIBILITY_POLL_MILLIS = 500L
        private const val KEYGUARD_GESTURE_TIMEOUT_MILLIS = 1_500L
        private const val KEYGUARD_GESTURE_LEASE_POLL_MILLIS = 50L
        private const val KEYGUARD_RETRY_DELAY_MILLIS = 500L
        private const val KEYGUARD_STATE_TIMEOUT_MILLIS = 1_000L
        private const val KEYGUARD_SWIPE_DURATION_MILLIS = 350L
        private const val KEYGUARD_SWIPE_START_HEIGHT_RATIO = 0.8f
        private const val KEYGUARD_SWIPE_END_HEIGHT_RATIO = 0.2f

        @Volatile
        private var processConfig: ScriptGuardianConfig? = null

        internal fun applyConfig(context: Context, config: ScriptGuardianConfig) {
            ScriptGuardianWatchdogService.applyConfig(context, config)
            startWithConfig(context, config, ACTION_APPLY_CONFIG)
        }

        internal fun restore(context: Context) {
            val config = ScriptGuardianPrefs.load(context)
            if (config.enabled) {
                applyConfig(context, config)
            }
        }

        internal fun verifyAndRecover(context: Context) {
            val config = ScriptGuardianPrefs.load(context)
            if (!config.enabled) return
            ScriptGuardianWatchdogService.applyConfig(context, config)
            ScriptGuardianRuntimeDiagnostics.recordRestore(ACTION_VERIFY_AND_RECOVER)
            startWithConfig(context, config, ACTION_VERIFY_AND_RECOVER)
        }

        internal fun stop(context: Context) {
            ScriptGuardianWatchdogService.stop(context)
            val intent = Intent(context, ScriptGuardianService::class.java).apply {
                action = ACTION_STOP
            }
            startServiceSafely(context, intent)
        }

        private fun startWithConfig(
            context: Context,
            config: ScriptGuardianConfig,
            requestAction: String
        ) {
            val intent = Intent(context, ScriptGuardianService::class.java).apply {
                action = requestAction
                putExtra(EXTRA_ENABLED, config.enabled)
                putExtra(EXTRA_PATH, config.relativePath)
                putExtra(EXTRA_SCRIPT_ROOT, config.scriptRoot)
                putExtra(
                    EXTRA_KEEP_SCREEN_ON_WHILE_CHARGING,
                    config.keepScreenOnWhileCharging
                )
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

internal fun shouldAwaitScriptGuardianBridgeAck(isMainThread: Boolean): Boolean = !isMainThread
