package org.autojs.autojs.guardian

import android.content.Context
import android.util.Log
import com.stardust.autojs.AutoJs
import com.stardust.autojs.execution.ScriptExecution
import com.stardust.autojs.script.ScriptSource
import com.stardust.autojs.script.sourceFileOrNull
import java.io.File

internal object ScriptGuardianExecutionGuard {
    private const val TAG = "ScriptGuardianGuard"
    private val managedLaunch = ThreadLocal<Boolean>()

    fun install(context: Context) {
        val guardedFile = resolveGuardedFile(ScriptGuardianPrefs.load(context.applicationContext))
        AutoJs.instance.scriptEngineService.setScriptExecutionGuard(guardFor(guardedFile))
    }

    fun configureAndSnapshot(file: File?): Collection<ScriptExecution> {
        val guardedFile = file?.canonicalFile
        return AutoJs.instance.scriptEngineService
            .configureScriptExecutionGuardAndSnapshot(guardFor(guardedFile))
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

    internal fun allows(source: ScriptSource, guardedFile: File?): Boolean {
        if (managedLaunch.get() == true || guardedFile == null) return true
        return source.sourceFileOrNull()?.sameFileAs(guardedFile) != true
    }

    private fun File.sameFileAs(other: File): Boolean = runCatching {
        canonicalPath == other.canonicalPath
    }.getOrDefault(false)

    private fun resolveGuardedFile(config: ScriptGuardianConfig?): File? = config
        ?.takeIf { candidate -> candidate.enabled }
        ?.resolveScriptFile()
        ?.getOrNull()
        ?.canonicalFile

    private fun guardFor(guardedFile: File?): ((ScriptSource) -> Boolean)? =
        guardedFile?.let { file ->
            { source ->
                val allowed = allows(source, file)
                if (!allowed) {
                    Log.i(TAG, "Blocked a second launch of the guarded script")
                }
                allowed
            }
        }
}
