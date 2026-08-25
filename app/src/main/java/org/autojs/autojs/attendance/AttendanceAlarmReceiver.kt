package org.autojs.autojs.attendance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

internal fun isAttendanceAlarmDeliveryTestAction(action: String?): Boolean =
    action == AttendanceAlarmScheduler.ACTION_DELIVERY_TEST

class AttendanceAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!isAttendanceAlarmDeliveryTestAction(intent.action)) return
        val plannedAtMillis = intent.getLongExtra(
            AttendanceAlarmScheduler.EXTRA_PLANNED_AT,
            0L
        )
        val plannedElapsedRealtimeMillis = intent.getLongExtra(
            AttendanceAlarmScheduler.EXTRA_PLANNED_ELAPSED,
            0L
        )
        val shouldStartService = AttendanceAlarmStore.recordReceived(
            context = context,
            plannedAtMillis = plannedAtMillis,
            plannedElapsedRealtimeMillis = plannedElapsedRealtimeMillis,
            receivedAtMillis = System.currentTimeMillis(),
            receivedElapsedRealtimeMillis = SystemClock.elapsedRealtime()
        )
        if (!shouldStartService) return
        if (
            !AttendanceAlarmTestService.start(
                context,
                plannedAtMillis,
                plannedElapsedRealtimeMillis
            )
        ) {
            AttendanceAlarmStore.recordServiceStartFailure(
                context,
                plannedAtMillis,
                plannedElapsedRealtimeMillis
            )
        }
    }
}
