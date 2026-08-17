package org.autojs.autojs.guardian

import android.content.Context
import android.util.Log
import com.aiselp.autox.engine.NodeScriptSource
import com.stardust.autojs.AutoJs
import com.stardust.autojs.script.AutoFileSource
import com.stardust.autojs.script.JavaScriptFileSource
import com.stardust.autojs.script.ScriptSource
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

    private fun File.sameFileAs(other: File): Boolean = runCatching {
        canonicalPath == other.canonicalPath
    }.getOrDefault(false)
}

internal fun ScriptSource.sourceFileOrNull(): File? = when (this) {
    is JavaScriptFileSource -> file
    is NodeScriptSource -> file
    is AutoFileSource -> file
    else -> null
}
