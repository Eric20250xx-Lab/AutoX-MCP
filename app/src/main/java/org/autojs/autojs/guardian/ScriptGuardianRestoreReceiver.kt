package org.autojs.autojs.guardian

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
private val SCRIPT_GUARDIAN_RESTORE_ACTIONS = setOf(
    Intent.ACTION_BOOT_COMPLETED,
    ACTION_QUICKBOOT_POWERON,
    Intent.ACTION_USER_UNLOCKED,
    Intent.ACTION_MY_PACKAGE_REPLACED
)
private val SCRIPT_GUARDIAN_PREWARM_REBUILD_ACTIONS = SCRIPT_GUARDIAN_RESTORE_ACTIONS + setOf(
    Intent.ACTION_TIME_CHANGED,
    Intent.ACTION_TIMEZONE_CHANGED,
    AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
)

internal fun shouldRestoreScriptGuardianFromDedicatedReceiver(action: String?): Boolean =
    action in SCRIPT_GUARDIAN_RESTORE_ACTIONS

internal fun shouldRebuildScriptGuardianPrewarmFromDedicatedReceiver(action: String?): Boolean =
    action in SCRIPT_GUARDIAN_PREWARM_REBUILD_ACTIONS

/**
 * Main-process lifecycle receiver dedicated to restoring Script Guardian.
 *
 * The legacy receiver remains in the script process because it also dispatches user-created
 * intent tasks. Keeping this receiver separate avoids making that broad task surface public and
 * gives lifecycle recovery a minimal main-process entry point.
 */
class ScriptGuardianRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val shouldRestore = shouldRestoreScriptGuardianFromDedicatedReceiver(action)
        val shouldRebuildPrewarm =
            shouldRebuildScriptGuardianPrewarmFromDedicatedReceiver(action)
        if (!shouldRestore && !shouldRebuildPrewarm) return

        if (shouldRestore) {
            ScriptGuardianDiagnostics.recordRestoreAction(context, action)
            ScriptGuardianService.restore(context)
        }
        if (shouldRebuildPrewarm) {
            ScriptGuardianPrewarmScheduler.reconcile(context, action)
        }
    }
}
