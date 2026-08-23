package org.autojs.autojs.guardian

import android.content.Context
import com.stardust.autojs.execution.ScriptExecution
import com.stardust.autojs.execution.ExecutionConfig
import com.stardust.autojs.script.sourceFileOrNull
import com.stardust.autojs.servicecomponents.BinderScriptListener
import com.stardust.autojs.servicecomponents.EngineController
import com.stardust.autojs.servicecomponents.ScriptServiceConnection
import com.stardust.autojs.servicecomponents.TaskInfo
import java.io.File

internal interface RemoteScriptGateway {
    suspend fun configureGuardAndList(file: File?): List<TaskInfo>

    suspend fun stopAndAwait(id: Int, timeoutMillis: Long): Boolean

    fun close()
}

private class BinderRemoteScriptGateway(context: Context) : RemoteScriptGateway {
    private val applicationContext = context.applicationContext
    private val connection = ScriptServiceConnection(registerConsoleListenerOnConnect = false)

    override suspend fun configureGuardAndList(file: File?): List<TaskInfo> {
        connection.bind(applicationContext)
        return connection.configureScriptGuardAndList(file?.canonicalPath)
    }

    override suspend fun stopAndAwait(id: Int, timeoutMillis: Long): Boolean {
        connection.bind(applicationContext)
        return connection.stopScriptAndAwait(id, timeoutMillis)
    }

    override fun close() {
        connection.unbind()
    }
}

internal class EngineScriptGuardianRunner(
    context: Context,
    private val remoteGateway: RemoteScriptGateway = BinderRemoteScriptGateway(context)
) : ScriptGuardianRunner {
    private data class LocalExecutionHandle(val value: ScriptExecution) : ScriptGuardianExecution
    private data class RemoteExecutionHandle(val id: Int) : ScriptGuardianExecution

    override suspend fun findRunning(file: File): List<ScriptGuardianExecution> {
        val expectedPath = file.canonicalPath
        val local = ScriptGuardianExecutionGuard.configureAndSnapshot(file)
            .filter { execution ->
                execution.source.sourceFileOrNull()?.canonicalPath == expectedPath
            }
            .map(::LocalExecutionHandle)
        val remote = remoteGateway.configureGuardAndList(file)
            .filter { task ->
                runCatching { File(task.sourcePath).canonicalPath }.getOrNull() == expectedPath
            }
            .map { task -> RemoteExecutionHandle(task.id) }
        return local + remote
    }

    override fun start(
        file: File,
        sessionId: String,
        onStarted: () -> Unit,
        onFinished: (Throwable?) -> Unit
    ): ScriptGuardianExecution {
        require(file.isFile) { "script file does not exist: ${file.path}" }
        val listener = object : BinderScriptListener {
            override fun onStart(taskInfo: TaskInfo) = onStarted()

            override fun onSuccess(taskInfo: TaskInfo) = onFinished(null)

            override fun onException(taskInfo: TaskInfo, e: Throwable) = onFinished(e)
        }
        return LocalExecutionHandle(
            ScriptGuardianExecutionGuard.runManagedLaunch {
                EngineController.runScriptLocalTracked(
                    file,
                    listener,
                    ExecutionConfig(workingDirectory = file.parent ?: "/").apply {
                        setArgument(ScriptGuardianHeartbeat.SESSION_ARGUMENT, sessionId)
                    }
                )
            }
        )
    }

    override suspend fun stopAndAwait(
        execution: ScriptGuardianExecution,
        timeoutMillis: Long
    ): Boolean = when (execution) {
        is LocalExecutionHandle -> EngineController.stopTrackedScriptAndAwait(
            execution.value,
            timeoutMillis
        )

        is RemoteExecutionHandle -> remoteGateway.stopAndAwait(execution.id, timeoutMillis)
        else -> error("Unknown Script Guardian execution")
    }

    override suspend fun awaitStopped(
        execution: ScriptGuardianExecution,
        timeoutMillis: Long
    ): Boolean = when (execution) {
        is LocalExecutionHandle -> EngineController.awaitTrackedScriptStopped(
            execution.value,
            timeoutMillis
        )

        is RemoteExecutionHandle -> remoteGateway.stopAndAwait(execution.id, timeoutMillis)
        else -> error("Unknown Script Guardian execution")
    }

    override fun stopNow(execution: ScriptGuardianExecution) {
        val local = execution as? LocalExecutionHandle ?: return
        local.value.engine?.let { engine ->
            if (!engine.isDestroyed) {
                engine.forceStop()
            }
        }
    }

    override suspend fun releaseGuard() {
        ScriptGuardianExecutionGuard.configureAndSnapshot(null)
        remoteGateway.configureGuardAndList(null)
    }

    override fun close() {
        remoteGateway.close()
    }
}
