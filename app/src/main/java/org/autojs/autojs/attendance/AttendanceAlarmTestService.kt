package org.autojs.autojs.attendance

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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.autojs.autoxjs.R

class AttendanceAlarmTestService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var foregroundStarted = false
    private var activeStartId = 0
    private var activePlannedAtMillis = 0L
    private var activePlannedElapsedRealtimeMillis = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val plannedAtMillis = intent
            ?.takeIf { it.action == ACTION_RUN_DELIVERY_TEST }
            ?.getLongExtra(EXTRA_PLANNED_AT, 0L)
            ?: 0L
        val plannedElapsedRealtimeMillis = intent
            ?.takeIf { it.action == ACTION_RUN_DELIVERY_TEST }
            ?.getLongExtra(EXTRA_PLANNED_ELAPSED, 0L)
            ?: 0L
        if (!startForegroundIfNeeded()) {
            AttendanceAlarmStore.recordServiceStartFailure(
                this,
                plannedAtMillis,
                plannedElapsedRealtimeMillis
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (
            plannedAtMillis <= 0L ||
            plannedElapsedRealtimeMillis <= 0L ||
            !AttendanceAlarmStore.recordServiceStarted(
                this,
                plannedAtMillis,
                plannedElapsedRealtimeMillis,
                System.currentTimeMillis(),
                SystemClock.elapsedRealtime()
            )
        ) {
            finishAndStop(startId)
            return START_NOT_STICKY
        }
        activeStartId = startId
        activePlannedAtMillis = plannedAtMillis
        activePlannedElapsedRealtimeMillis = plannedElapsedRealtimeMillis

        mainHandler.postDelayed(
            {
                finishAndStop(
                    startId,
                    plannedAtMillis,
                    plannedElapsedRealtimeMillis
                )
            },
            FINISH_DELAY_MILLIS
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int) {
        finishAndStop(
            startId,
            activePlannedAtMillis,
            activePlannedElapsedRealtimeMillis
        )
    }

    private fun finishAndStop(
        startId: Int,
        plannedAtMillis: Long = 0L,
        plannedElapsedRealtimeMillis: Long = 0L
    ) {
        if (plannedAtMillis > 0L && plannedElapsedRealtimeMillis > 0L) {
            AttendanceAlarmStore.recordServiceFinished(
                this,
                plannedAtMillis,
                plannedElapsedRealtimeMillis,
                System.currentTimeMillis(),
                SystemClock.elapsedRealtime()
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        if (activeStartId == startId) {
            activeStartId = 0
            activePlannedAtMillis = 0L
            activePlannedElapsedRealtimeMillis = 0L
        }
        stopSelf(startId)
    }

    private fun startForegroundIfNeeded(): Boolean {
        if (foregroundStarted) return true
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            foregroundStarted = true
            true
        } catch (error: RuntimeException) {
            Log.w(
                "AttendanceAlarmTest",
                "Could not enter foreground for exact-alarm delivery test",
                error
            )
            false
        }
    }

    private fun buildNotification(): Notification {
        ensureNotificationChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.attendance_alarm_test_notification_title))
            .setContentText(getString(R.string.attendance_alarm_test_notification_text))
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
            getString(R.string.attendance_alarm_test_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = getString(R.string.attendance_alarm_test_channel_description)
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "attendance_alarm_delivery_test"
        private const val NOTIFICATION_ID = 27197
        private const val ACTION_RUN_DELIVERY_TEST =
            "org.autojs.autojs.attendance.action.RUN_EXACT_ALARM_DELIVERY_TEST"
        private const val EXTRA_PLANNED_AT = "planned_at"
        private const val EXTRA_PLANNED_ELAPSED = "planned_elapsed"
        private const val FINISH_DELAY_MILLIS = 750L

        internal fun start(
            context: Context,
            plannedAtMillis: Long,
            plannedElapsedRealtimeMillis: Long
        ): Boolean {
            val intent = Intent(context, AttendanceAlarmTestService::class.java).apply {
                action = ACTION_RUN_DELIVERY_TEST
                putExtra(EXTRA_PLANNED_AT, plannedAtMillis)
                putExtra(EXTRA_PLANNED_ELAPSED, plannedElapsedRealtimeMillis)
            }
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (error: RuntimeException) {
                Log.w(
                    "AttendanceAlarmTest",
                    "Could not start exact-alarm delivery test service",
                    error
                )
                false
            }
        }
    }
}
