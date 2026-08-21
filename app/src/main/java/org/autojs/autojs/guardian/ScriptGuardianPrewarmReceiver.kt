package org.autojs.autojs.guardian

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal fun shouldHandleScriptGuardianPrewarm(action: String?): Boolean =
    action == ScriptGuardianPrewarmScheduler.ACTION_PREWARM

class ScriptGuardianPrewarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!shouldHandleScriptGuardianPrewarm(intent.action)) return

        val scheduledAtMillis = intent.getLongExtra(
            ScriptGuardianPrewarmScheduler.EXTRA_SCHEDULED_AT,
            0L
        )
        val flow = ScriptGuardianPrewarmScheduler.recordReceived(
            context = context,
            scheduledAtMillis = scheduledAtMillis,
            kind = ScriptGuardianPrewarmScheduler.RECEIPT_KIND_ALARM
        )
        if (flow.shouldRestore) {
            ScriptGuardianService.restore(context)
        }
        if (flow.shouldReconcile) {
            ScriptGuardianPrewarmScheduler.reconcileAfterReceipt(
                context = context,
                action = intent.action,
                scheduledAtMillis = scheduledAtMillis
            )
        }
    }
}
