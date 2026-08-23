package org.autojs.autojs.guardian

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal enum class ScriptGuardianPrewarmReceiverAction {
    SCHEDULED_PREWARM,
    WATCHDOG_REQUEST_RESTORE,
    WATCHDOG_VERIFY_AND_RECOVER
}

internal fun scriptGuardianPrewarmReceiverAction(
    action: String?
): ScriptGuardianPrewarmReceiverAction? = when (action) {
    ScriptGuardianPrewarmScheduler.ACTION_PREWARM ->
        ScriptGuardianPrewarmReceiverAction.SCHEDULED_PREWARM

    ScriptGuardianPrewarmScheduler.ACTION_WATCHDOG_REQUEST_RESTORE ->
        ScriptGuardianPrewarmReceiverAction.WATCHDOG_REQUEST_RESTORE

    ScriptGuardianPrewarmScheduler.ACTION_WATCHDOG_VERIFY_AND_RECOVER ->
        ScriptGuardianPrewarmReceiverAction.WATCHDOG_VERIFY_AND_RECOVER

    else -> null
}

internal enum class ScriptGuardianPrewarmReceiptAction {
    NONE,
    VERIFY_AND_RECOVER
}

internal fun scriptGuardianPrewarmReceiptAction(
    flow: ScriptGuardianPrewarmReceiverFlow
): ScriptGuardianPrewarmReceiptAction = if (flow.shouldRestore) {
    ScriptGuardianPrewarmReceiptAction.VERIFY_AND_RECOVER
} else {
    ScriptGuardianPrewarmReceiptAction.NONE
}

class ScriptGuardianPrewarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (scriptGuardianPrewarmReceiverAction(intent.action)) {
            ScriptGuardianPrewarmReceiverAction.WATCHDOG_REQUEST_RESTORE -> {
                ScriptGuardianRuntimeDiagnostics.recordRestore(intent.action)
                ScriptGuardianService.restore(context)
                return
            }

            ScriptGuardianPrewarmReceiverAction.WATCHDOG_VERIFY_AND_RECOVER -> {
                ScriptGuardianService.verifyAndRecover(context)
                return
            }

            ScriptGuardianPrewarmReceiverAction.SCHEDULED_PREWARM -> Unit
            null -> return
        }

        val scheduledAtMillis = intent.getLongExtra(
            ScriptGuardianPrewarmScheduler.EXTRA_SCHEDULED_AT,
            0L
        )
        val flow = ScriptGuardianPrewarmScheduler.recordReceived(
            context = context,
            scheduledAtMillis = scheduledAtMillis,
            kind = ScriptGuardianPrewarmScheduler.RECEIPT_KIND_ALARM
        )
        if (
            scriptGuardianPrewarmReceiptAction(flow) ==
            ScriptGuardianPrewarmReceiptAction.VERIFY_AND_RECOVER
        ) {
            ScriptGuardianService.verifyAndRecover(context)
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
