package org.autojs.autojs.guardian

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.preference.PreferenceManager
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

internal object ScriptGuardianPrewarmPrefs {
    const val KEY_ENABLED = "key_script_guardian_prewarm_enabled"
    const val KEY_TIMES = "key_script_guardian_prewarm_times"
    const val DEFAULT_TIMES = "08:15,17:45"
}

internal data class ScriptGuardianPrewarmSnapshot(
    val state: String = STATE_DISABLED,
    val nextTriggerAtMillis: Long = 0L,
    val lastScheduledAtMillis: Long = 0L,
    val lastReceivedAtMillis: Long = 0L,
    val lastRebuildAction: String = "",
    val lastRebuildAtMillis: Long = 0L
) {
    companion object {
        const val STATE_DISABLED = "DISABLED"
        const val STATE_GUARDIAN_DISABLED = "GUARDIAN_DISABLED"
        const val STATE_EMPTY_TIMES = "EMPTY_TIMES"
        const val STATE_INVALID_TIMES = "INVALID_TIMES"
        const val STATE_EXACT_ALARM_PERMISSION_REQUIRED =
            "EXACT_ALARM_PERMISSION_REQUIRED"
        const val STATE_SCHEDULED = "SCHEDULED"
        const val STATE_ERROR = "ERROR"
    }
}

internal data class ScriptGuardianPrewarmPlan(
    val state: String,
    val nextTriggerAtMillis: Long = 0L
)

internal val SCRIPT_GUARDIAN_PREWARM_ZONE_ID: ZoneId = ZoneId.of("Asia/Shanghai")

internal fun parseScriptGuardianPrewarmTimes(raw: String): Result<List<LocalTime>> = runCatching {
    val tokens = raw.trim()
        .split(PREWARM_TIME_SEPARATOR)
        .filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return@runCatching emptyList()

    tokens.map { token ->
        val match = PREWARM_TIME_PATTERN.matchEntire(token)
            ?: throw IllegalArgumentException("invalid prewarm time")
        LocalTime.of(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt()
        )
    }.distinct().sorted()
}

internal fun nextScriptGuardianPrewarmAt(
    times: List<LocalTime>,
    nowMillis: Long,
    zoneId: ZoneId
): Long {
    require(times.isNotEmpty()) { "prewarm times are empty" }
    val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
    val today = now.toLocalDate()
    times.sorted().forEach { time ->
        val candidate = today.atTime(time).atZone(zoneId).toInstant().toEpochMilli()
        if (candidate > nowMillis) return candidate
    }
    return today.plusDays(1)
        .atTime(times.minOrNull()!!)
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()
}

internal fun planScriptGuardianPrewarm(
    prewarmEnabled: Boolean,
    guardianEnabled: Boolean,
    rawTimes: String,
    exactAlarmAllowed: Boolean,
    nowMillis: Long,
    zoneId: ZoneId
): ScriptGuardianPrewarmPlan {
    if (!prewarmEnabled) {
        return ScriptGuardianPrewarmPlan(ScriptGuardianPrewarmSnapshot.STATE_DISABLED)
    }
    if (!guardianEnabled) {
        return ScriptGuardianPrewarmPlan(
            ScriptGuardianPrewarmSnapshot.STATE_GUARDIAN_DISABLED
        )
    }

    val times = parseScriptGuardianPrewarmTimes(rawTimes).getOrElse {
        return ScriptGuardianPrewarmPlan(
            ScriptGuardianPrewarmSnapshot.STATE_INVALID_TIMES
        )
    }
    if (times.isEmpty()) {
        return ScriptGuardianPrewarmPlan(ScriptGuardianPrewarmSnapshot.STATE_EMPTY_TIMES)
    }
    if (!exactAlarmAllowed) {
        return ScriptGuardianPrewarmPlan(
            ScriptGuardianPrewarmSnapshot.STATE_EXACT_ALARM_PERMISSION_REQUIRED
        )
    }

    return ScriptGuardianPrewarmPlan(
        state = ScriptGuardianPrewarmSnapshot.STATE_SCHEDULED,
        nextTriggerAtMillis = nextScriptGuardianPrewarmAt(times, nowMillis, zoneId)
    )
}

internal fun scriptGuardianPrewarmRescheduleBase(
    nowMillis: Long,
    scheduledAtMillis: Long
): Long {
    val afterScheduled = when {
        scheduledAtMillis <= 0L -> nowMillis
        scheduledAtMillis == Long.MAX_VALUE -> Long.MAX_VALUE
        else -> scheduledAtMillis + 1L
    }
    return maxOf(nowMillis, afterScheduled)
}

internal object ScriptGuardianPrewarmScheduler {
    const val ACTION_PREWARM = "org.autojs.autojs.guardian.action.PREWARM"
    internal const val EXTRA_SCHEDULED_AT = "scheduled_at"

    fun reconcile(
        context: Context,
        action: String? = null
    ): ScriptGuardianPrewarmSnapshot = reconcileAt(
        context = context,
        action = action,
        schedulingBaseMillis = System.currentTimeMillis()
    )

    internal fun reconcileAfterReceipt(
        context: Context,
        action: String?,
        scheduledAtMillis: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): ScriptGuardianPrewarmSnapshot = reconcileAt(
        context = context,
        action = action,
        schedulingBaseMillis = scriptGuardianPrewarmRescheduleBase(
            nowMillis = nowMillis,
            scheduledAtMillis = scheduledAtMillis
        )
    )

    private fun reconcileAt(
        context: Context,
        action: String?,
        schedulingBaseMillis: Long
    ): ScriptGuardianPrewarmSnapshot {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val settings = PreferenceManager.getDefaultSharedPreferences(appContext)
        val prewarmEnabled = settings.getBoolean(ScriptGuardianPrewarmPrefs.KEY_ENABLED, false)
        val guardianEnabled = settings.getBoolean(ScriptGuardianPrefs.KEY_ENABLED, false)
        val rawTimes = settings.getString(
            ScriptGuardianPrewarmPrefs.KEY_TIMES,
            ScriptGuardianPrewarmPrefs.DEFAULT_TIMES
        ).orEmpty()

        val plan = if (alarmManager == null && prewarmEnabled && guardianEnabled) {
            ScriptGuardianPrewarmPlan(ScriptGuardianPrewarmSnapshot.STATE_ERROR)
        } else {
            planScriptGuardianPrewarm(
                prewarmEnabled = prewarmEnabled,
                guardianEnabled = guardianEnabled,
                rawTimes = rawTimes,
                exactAlarmAllowed = alarmManager != null && canScheduleExactAlarms(alarmManager),
                nowMillis = schedulingBaseMillis,
                zoneId = SCRIPT_GUARDIAN_PREWARM_ZONE_ID
            )
        }

        var finalPlan = plan
        if (plan.state == ScriptGuardianPrewarmSnapshot.STATE_SCHEDULED) {
            try {
                alarmManager!!.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    plan.nextTriggerAtMillis,
                    pendingIntent(appContext, plan.nextTriggerAtMillis)
                )
            } catch (_: SecurityException) {
                alarmManager?.cancel(pendingIntent(appContext, 0L))
                finalPlan = ScriptGuardianPrewarmPlan(
                    ScriptGuardianPrewarmSnapshot.STATE_EXACT_ALARM_PERMISSION_REQUIRED
                )
            } catch (_: RuntimeException) {
                alarmManager?.cancel(pendingIntent(appContext, 0L))
                finalPlan = ScriptGuardianPrewarmPlan(
                    ScriptGuardianPrewarmSnapshot.STATE_ERROR
                )
            }
        } else {
            alarmManager?.cancel(pendingIntent(appContext, 0L))
        }

        val previous = snapshot(appContext)
        val next = previous.copy(
            state = finalPlan.state,
            nextTriggerAtMillis = finalPlan.nextTriggerAtMillis,
            lastScheduledAtMillis = if (
                finalPlan.state == ScriptGuardianPrewarmSnapshot.STATE_SCHEDULED
            ) {
                now
            } else {
                previous.lastScheduledAtMillis
            },
            lastRebuildAction = action.orEmpty(),
            lastRebuildAtMillis = now
        )
        writeSnapshot(appContext, next)
        return next
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        val alarmManager = context.applicationContext
            .getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return false
        return canScheduleExactAlarms(alarmManager)
    }

    fun snapshot(context: Context): ScriptGuardianPrewarmSnapshot {
        val prefs = diagnosticPreferences(context.applicationContext)
        return ScriptGuardianPrewarmSnapshot(
            state = prefs.getString(KEY_STATE, null)
                ?: ScriptGuardianPrewarmSnapshot.STATE_DISABLED,
            nextTriggerAtMillis = prefs.getLong(KEY_NEXT_TRIGGER_AT, 0L),
            lastScheduledAtMillis = prefs.getLong(KEY_LAST_SCHEDULED_AT, 0L),
            lastReceivedAtMillis = prefs.getLong(KEY_LAST_RECEIVED_AT, 0L),
            lastRebuildAction = prefs.getString(KEY_LAST_REBUILD_ACTION, "").orEmpty(),
            lastRebuildAtMillis = prefs.getLong(KEY_LAST_REBUILD_AT, 0L)
        )
    }

    internal fun recordReceived(context: Context, receivedAtMillis: Long = System.currentTimeMillis()) {
        val appContext = context.applicationContext
        writeSnapshot(
            appContext,
            snapshot(appContext).copy(
                nextTriggerAtMillis = 0L,
                lastReceivedAtMillis = receivedAtMillis
            )
        )
    }

    private fun canScheduleExactAlarms(alarmManager: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun pendingIntent(context: Context, scheduledAtMillis: Long): PendingIntent {
        val intent = Intent(context, ScriptGuardianPrewarmReceiver::class.java).apply {
            action = ACTION_PREWARM
            `package` = context.packageName
            putExtra(EXTRA_SCHEDULED_AT, scheduledAtMillis)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun diagnosticPreferences(context: Context) =
        context.getSharedPreferences(DIAGNOSTIC_PREFS_NAME, Context.MODE_PRIVATE)

    private fun writeSnapshot(context: Context, snapshot: ScriptGuardianPrewarmSnapshot) {
        diagnosticPreferences(context).edit()
            .putString(KEY_STATE, snapshot.state)
            .putLong(KEY_NEXT_TRIGGER_AT, snapshot.nextTriggerAtMillis)
            .putLong(KEY_LAST_SCHEDULED_AT, snapshot.lastScheduledAtMillis)
            .putLong(KEY_LAST_RECEIVED_AT, snapshot.lastReceivedAtMillis)
            .putString(KEY_LAST_REBUILD_ACTION, snapshot.lastRebuildAction)
            .putLong(KEY_LAST_REBUILD_AT, snapshot.lastRebuildAtMillis)
            .commit()
    }

    private const val DIAGNOSTIC_PREFS_NAME = "script_guardian_prewarm_diagnostics"
    private const val REQUEST_CODE = 27192
    private const val KEY_STATE = "state"
    private const val KEY_NEXT_TRIGGER_AT = "next_trigger_at"
    private const val KEY_LAST_SCHEDULED_AT = "last_scheduled_at"
    private const val KEY_LAST_RECEIVED_AT = "last_received_at"
    private const val KEY_LAST_REBUILD_ACTION = "last_rebuild_action"
    private const val KEY_LAST_REBUILD_AT = "last_rebuild_at"
}

private val PREWARM_TIME_SEPARATOR = Regex("[,，;；\\s]+")
private val PREWARM_TIME_PATTERN = Regex("([01]\\d|2[0-3])[:.]([0-5]\\d)")
