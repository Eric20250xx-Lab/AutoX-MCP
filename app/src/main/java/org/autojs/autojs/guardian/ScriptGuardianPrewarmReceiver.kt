package org.autojs.autojs.guardian

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal fun shouldHandleScriptGuardianPrewarm(action: String?): Boolean =
    action == ScriptGuardianPrewarmScheduler.ACTION_PREWARM

class ScriptGuardianPrewarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!shouldHandleScriptGuardianPrewarm(intent.action)) return

        ScriptGuardianPrewarmScheduler.recordReceived(context)
        ScriptGuardianService.restore(context)
        ScriptGuardianPrewarmScheduler.reconcileAfterReceipt(
            context = context,
            action = intent.action,
            scheduledAtMillis = intent.getLongExtra(
                ScriptGuardianPrewarmScheduler.EXTRA_SCHEDULED_AT,
                0L
            )
        )
    }
}
