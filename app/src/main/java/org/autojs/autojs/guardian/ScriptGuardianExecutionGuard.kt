package org.autojs.autojs.guardian

import android.content.Context
import android.util.Log
import com.aiselp.autox.engine.NodeScriptSource
import com.stardust.autojs.AutoJs
import com.stardust.autojs.execution.ScriptExecution
import com.stardust.autojs.script.AutoFileSource
import com.stardust.autojs.script.JavaScriptFileSource
import com.stardust.autojs.script.ScriptSource
import com.stardust.autojs.servicecomponents.EngineController
import java.io.File

internal object ScriptGuardianExecutionGuard {
    private const val TAG = "ScriptGuardianGuard"
    private val managedLaunch = ThreadLocal<Boolean>()
    @Volatile
    private var processGuardedFile: File? = null

    fun install(context: Context) {
        updateConfig(ScriptGuardianPrefs.load(context.applicationContext))
        AutoJs.instance.scriptEngineService.setScriptExecutionGuard { source ->
            val allowed = allows(source)
            if (!allowed) {
                Log.i(TAG, "Blocked a second launch of the guarded script")
            }
            allowed
        }
    }

    fun updateConfig(config: ScriptGuardianConfig?) {
        processGuardedFile = config
            ?.takeIf { candidate -> candidate.enabled }
            ?.resolveScriptFile()
            ?.getOrNull()
            ?.canonicalFile
    }

    fun <T> runManagedLaunch(block: () -> T): T {
        val previous = managedLaunch.get()
        managedLaunch.set(true)
        return try {
            block()
        } finally {
            if (previous == null) {
                managedLaunch.remove()
            } else {
                managedLaunch.set(previous)
            }
        }
    }

    internal fun allows(source: ScriptSource, guardedFile: File? = processGuardedFile): Boolean {
        if (managedLaunch.get() == true || guardedFile == null) return true
        return source.sourceFileOrNull()?.sameFileAs(guardedFile) != true
    }

    suspend fun stopExistingMainProcessExecutions(config: ScriptGuardianConfig): Boolean {
        val target = config.resolveScriptFile().getOrElse { return false }.canonicalFile
        repeat(MAIN_PROCESS_SCAN_ATTEMPTS) {
            val matches = (mainProcessExecutions() ?: return false)
                .filter { execution -> execution.source.sourceFileOrNull()?.sameFileAs(target) == true }
            if (matches.isEmpty()) {
                return true
            }
            for (execution in matches) {
                val stopped = runCatching {
                    EngineController.stopTrackedScriptAndAwait(execution)
                }.getOrDefault(false)
                if (!stopped) {
                    Log.w(TAG, "Timed out stopping an existing guarded script")
                    return false
                }
            }
        }
        return (mainProcessExecutions() ?: return false).none { execution ->
            execution.source.sourceFileOrNull()?.sameFileAs(target) == true
        }
    }

    private fun mainProcessExecutions(): List<ScriptExecution>? = runCatching {
        AutoJs.instance.scriptEngineService.scriptExecutions.toList()
    }.getOrElse { error ->
        Log.w(TAG, "Could not inspect main-process scripts", error)
        null
    }

    private fun File.sameFileAs(other: File): Boolean = runCatching {
        canonicalPath == other.canonicalPath
    }.getOrDefault(false)

    private const val MAIN_PROCESS_SCAN_ATTEMPTS = 3
}

internal fun ScriptSource.sourceFileOrNull(): File? = when (this) {
    is JavaScriptFileSource -> file
    is NodeScriptSource -> file
    is AutoFileSource -> file
    else -> null
}
