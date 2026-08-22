package org.autojs.autojs.guardian

internal object ScriptGuardianProbeProtocol {
    const val MSG_PROBE = 1
    const val MSG_PROBE_RESULT = 2
    const val MSG_DIAGNOSTICS = 3

    const val KEY_REQUEST_ID = "request_id"
    const val KEY_PROCESS_EPOCH = "process_epoch"
    const val KEY_PROCESS_STARTED_AT = "process_started_at"
    const val KEY_GUARDIAN_STATE = "guardian_state"
    const val KEY_GUARDIAN_HEARTBEAT_AGE = "guardian_heartbeat_age"
    const val KEY_WATCHDOG_STATE = "watchdog_state"
    const val KEY_WATCHDOG_MISSES = "watchdog_misses"
    const val KEY_WATCHDOG_SUCCESSES = "watchdog_successes"
    const val KEY_WATCHDOG_LAST_PROBE_AT = "watchdog_last_probe_at"
    const val KEY_WATCHDOG_LAST_SUCCESS_AT = "watchdog_last_success_at"
    const val KEY_WATCHDOG_LAST_ERROR = "watchdog_last_error"
}
