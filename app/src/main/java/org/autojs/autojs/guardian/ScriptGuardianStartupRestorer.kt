package org.autojs.autojs.guardian

import android.content.Context

internal const val SCRIPT_GUARDIAN_MAIN_PROCESS_START = "main_process_start"

internal class ScriptGuardianStartupRestorer(
    private val loadConfig: () -> ScriptGuardianConfig,
    private val recordRestore: () -> Unit,
    private val applyConfig: (ScriptGuardianConfig) -> Unit
) {
    constructor(context: Context) : this(
        loadConfig = { ScriptGuardianPrefs.load(context.applicationContext) },
        recordRestore = {
            ScriptGuardianDiagnostics.recordRestoreAction(
                context.applicationContext,
                SCRIPT_GUARDIAN_MAIN_PROCESS_START
            )
            ScriptGuardianRuntimeDiagnostics.recordRestore(
                SCRIPT_GUARDIAN_MAIN_PROCESS_START
            )
        },
        applyConfig = { config ->
            ScriptGuardianService.applyConfig(context.applicationContext, config)
        }
    )

    fun restoreIfEnabled(): Boolean {
        val config = loadConfig()
        if (!config.enabled) return false

        recordRestore()
        applyConfig(config)
        return true
    }
}
