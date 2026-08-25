package org.autojs.autojs.attendance

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

internal object AttendanceAlarmScheduler {
    internal const val ACTION_DELIVERY_TEST =
        "org.autojs.autojs.attendance.action.EXACT_ALARM_DELIVERY_TEST"
    internal const val EXTRA_PLANNED_AT = "planned_at"
    internal const val EXTRA_PLANNED_ELAPSED = "planned_elapsed"
    internal const val TEST_DELAY_MILLIS = 2 * 60_000L

    @Synchronized
    fun scheduleDeliveryTest(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
        elapsedRealtimeMillis: Long = SystemClock.elapsedRealtime()
    ): AttendanceAlarmSnapshot {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (alarmManager == null) {
            return storeNewSnapshot(
                appContext,
                AttendanceAlarmSnapshot(state = AttendanceAlarmSnapshot.STATE_ERROR)
            )
        }

        val planned = planAttendanceAlarmTest(
            exactAlarmAllowed = canScheduleExactAlarms(alarmManager),
            nowMillis = nowMillis,
            elapsedRealtimeMillis = elapsedRealtimeMillis
        )
        if (planned.state != AttendanceAlarmSnapshot.STATE_SCHEDULED) {
            alarmManager.cancel(pendingIntent(appContext, 0L))
            return storeNewSnapshot(appContext, planned)
        }
        if (!AttendanceAlarmStore.replace(appContext, planned)) {
            return AttendanceAlarmSnapshot(state = AttendanceAlarmSnapshot.STATE_ERROR)
        }

        return try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                planned.plannedAtMillis,
                pendingIntent(
                    appContext,
                    planned.plannedAtMillis,
                    planned.plannedElapsedRealtimeMillis
                )
            )
            planned
        } catch (_: SecurityException) {
            alarmManager.cancel(pendingIntent(appContext, 0L))
            storeNewSnapshot(
                appContext,
                AttendanceAlarmSnapshot(
                    state = AttendanceAlarmSnapshot.STATE_PERMISSION_REQUIRED
                )
            )
        } catch (_: RuntimeException) {
            alarmManager.cancel(pendingIntent(appContext, 0L))
            storeNewSnapshot(
                appContext,
                AttendanceAlarmSnapshot(state = AttendanceAlarmSnapshot.STATE_ERROR)
            )
        }
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        val alarmManager = context.applicationContext
            .getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return false
        return canScheduleExactAlarms(alarmManager)
    }

    fun snapshot(context: Context): AttendanceAlarmSnapshot =
        AttendanceAlarmStore.snapshot(context.applicationContext)

    private fun canScheduleExactAlarms(alarmManager: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun storeNewSnapshot(
        context: Context,
        snapshot: AttendanceAlarmSnapshot
    ): AttendanceAlarmSnapshot = if (AttendanceAlarmStore.replace(context, snapshot)) {
        snapshot
    } else {
        AttendanceAlarmSnapshot(state = AttendanceAlarmSnapshot.STATE_ERROR)
    }

    private fun pendingIntent(
        context: Context,
        plannedAtMillis: Long,
        plannedElapsedRealtimeMillis: Long = 0L
    ): PendingIntent {
        val intent = Intent(context, AttendanceAlarmReceiver::class.java).apply {
            action = ACTION_DELIVERY_TEST
            `package` = context.packageName
            putExtra(EXTRA_PLANNED_AT, plannedAtMillis)
            putExtra(EXTRA_PLANNED_ELAPSED, plannedElapsedRealtimeMillis)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private const val REQUEST_CODE = 27196
}
