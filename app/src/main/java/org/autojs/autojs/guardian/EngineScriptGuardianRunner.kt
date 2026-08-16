package org.autojs.autojs.guardian

import com.stardust.autojs.AutoJs
import com.stardust.autojs.execution.ScriptExecution
import com.stardust.autojs.servicecomponents.BinderScriptListener
import com.stardust.autojs.servicecomponents.EngineController
import com.stardust.autojs.servicecomponents.TaskInfo
import java.io.File

internal class EngineScriptGuardianRunner : ScriptGuardianRunner {
    private data class ExecutionHandle(val value: ScriptExecution) : ScriptGuardianExecution

    override fun findRunning(file: File): List<ScriptGuardianExecution> {
        val expectedPath = file.canonicalPath
        return AutoJs.instance.scriptEngineService.scriptExecutions
            .toList()
            .filter { execution ->
                execution.source.sourceFileOrNull()?.canonicalPath == expectedPath
            }
            .map(::ExecutionHandle)
    }

    override fun start(
        file: File,
        onStarted: () -> Unit,
        onFinished: (Throwable?) -> Unit
    ): ScriptGuardianExecution {
        require(file.isFile) { "script file does not exist: ${file.path}" }
        val listener = object : BinderScriptListener {
            override fun onStart(taskInfo: TaskInfo) = onStarted()

            override fun onSuccess(taskInfo: TaskInfo) = onFinished(null)

            override fun onException(taskInfo: TaskInfo, e: Throwable) = onFinished(e)
        }
        return ExecutionHandle(
            ScriptGuardianExecutionGuard.runManagedLaunch {
                EngineController.runScriptLocalTracked(file, listener)
            }
        )
    }

    override suspend fun stopAndAwait(
        execution: ScriptGuardianExecution,
        timeoutMillis: Long
    ): Boolean = EngineController.stopTrackedScriptAndAwait(
        execution.requireHandle(),
        timeoutMillis
    )

    override suspend fun awaitStopped(
        execution: ScriptGuardianExecution,
        timeoutMillis: Long
    ): Boolean = EngineController.awaitTrackedScriptStopped(
        execution.requireHandle(),
        timeoutMillis
    )

    override fun stopNow(execution: ScriptGuardianExecution) {
        execution.requireHandle().engine?.let { engine ->
            if (!engine.isDestroyed) {
                engine.forceStop()
            }
        }
    }

    private fun ScriptGuardianExecution.requireHandle(): ScriptExecution =
        (this as ExecutionHandle).value

}
