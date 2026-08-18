package com.stardust.autojs.servicecomponents

import android.util.Log
import com.aiselp.autox.engine.NodeScriptEngine
import com.stardust.autojs.AutoJs
import com.stardust.autojs.ScriptExecutionRejectedException
import com.stardust.autojs.engine.ScriptEngine
import com.stardust.autojs.execution.ExecutionConfig
import com.stardust.autojs.execution.ScriptExecution
import com.stardust.autojs.project.ProjectConfig
import com.stardust.autojs.script.JavaScriptSource
import com.stardust.autojs.script.ScriptFile
import com.stardust.autojs.script.ScriptSource
import com.stardust.autojs.servicecomponents.ScriptServiceConnection.Companion.GlobalConnection
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

object EngineController {
    val scope = CoroutineScope(Dispatchers.Default)
    private val serviceConnection: ScriptServiceConnection
        get() = GlobalConnection

    private val globalScriptListener: CopyOnWriteArraySet<BinderScriptListener> by lazy {
        val listeners = CopyOnWriteArraySet<BinderScriptListener>()
        scope.launch {
            serviceConnection.registerGlobalScriptListener(
                object : BinderScriptListener {
                    override fun onStart(taskInfo: TaskInfo) {
                        for (l in listeners) {
                            l.onStart(taskInfo)
                        }
                    }

                    override fun onSuccess(taskInfo: TaskInfo) {
                        for (l in listeners) {
                            l.onSuccess(taskInfo)
                        }
                    }

                    override fun onException(taskInfo: TaskInfo, e: Throwable) {
                        for (l in listeners) {
                            l.onException(taskInfo, e)
                        }
                    }

                })
        }
        return@lazy listeners
    }

    fun runScript(
        taskInfo: TaskInfo,
        listener: BinderScriptListener? = null,
        config: ExecutionConfig? = null
    ) = scope.launch {
        try {
            AutoJs.instance
            val source: ScriptSource = ScriptFile(taskInfo.sourcePath).toSource()
            AutoJs.instance.scriptEngineService.execute(
                source, listener?.toScriptExecutionListener(),
                ExecutionConfig(workingDirectory = taskInfo.workerDirectory)
            )
        } catch (e: ScriptExecutionRejectedException) {
            Log.i(TAG, "Rejected guarded script: ${taskInfo.sourcePath}")
            listener?.onException(taskInfo, e)
        } catch (e: Throwable) {
            serviceConnection.runScript(taskInfo, listener, config)
        }
    }

    fun launchProject(projectConfig: ProjectConfig, listener: BinderScriptListener? = null) =
        scope.launch {
            runScript(
                File(projectConfig.projectDirectory, projectConfig.mainScript ?: "main.js"),
                listener
            )
        }


    fun runScript(
        file: File,
        listener: BinderScriptListener? = null,
        config: ExecutionConfig? = null
    ) {
        scope.launch {
            val engineName = when (file.extension) {
                "mjs" -> NodeScriptEngine.ID
                else -> JavaScriptSource.ENGINE
            }
            runScript(object : TaskInfo {
                override val id: Int = 0
                override val name: String = file.name
                override val desc: String = file.path
                override val engineName: String = engineName
                override val workerDirectory: String = file.parent ?: "/"
                override val sourcePath: String = file.path
                override val isRunning: Boolean = false
            }, listener, config)
        }
    }

    /**
     * Starts a script in the current process and returns the execution that owns its engine.
     *
     * Callers that need precise cancellation must retain this handle instead of routing the stop
     * request through [serviceConnection], which controls the independent script process.
     */
    fun runScriptLocalTracked(
        file: File,
        listener: BinderScriptListener? = null,
        config: ExecutionConfig? = null
    ): ScriptExecution {
        val source: ScriptSource = ScriptFile(file.path).toSource()
        return AutoJs.instance.scriptEngineService.execute(
            source,
            listener?.toScriptExecutionListener(),
            config ?: ExecutionConfig(workingDirectory = file.parent ?: "/")
        )
    }

    /** Stops only [execution]'s engine, or succeeds once it exits before creating one. */
    suspend fun stopTrackedScriptAndAwait(
        execution: ScriptExecution,
        timeoutMillis: Long = 5_000L
    ): Boolean = TrackedScriptExecutionLifecycle.stopAndAwait(
        execution,
        isRegistered = { tracked ->
            AutoJs.instance.scriptEngineService.getScriptExecution(tracked.id) === tracked
        },
        timeoutMillis = timeoutMillis
    )

    /** Waits for [execution]'s engine to be destroyed, or for an engine-less exit. */
    suspend fun awaitTrackedScriptStopped(
        execution: ScriptExecution,
        timeoutMillis: Long = 5_000L
    ): Boolean = TrackedScriptExecutionLifecycle.awaitStopped(
        execution,
        isRegistered = { tracked ->
            AutoJs.instance.scriptEngineService.getScriptExecution(tracked.id) === tracked
        },
        timeoutMillis = timeoutMillis
    )

    fun getAllScriptTasks(): Deferred<MutableList<TaskInfo>> = scope.async {
        return@async serviceConnection.getAllScriptTasks()
    }

    fun stopScript(id: Int) = scope.launch {
        serviceConnection.stopScript(id)
    }

    fun stopAllScript() = scope.launch {
        serviceConnection.stopAllScript()
    }

    /**
     * 通知AutoJs子进程退出
     */
    fun appExit() = scope.launch {
        serviceConnection.appExit()
    }

    fun registerGlobalConsoleListener(
        listener: BinderConsoleListener,
        scheduler: Scheduler = Schedulers.newThread()
    ): Disposable {
        return serviceConnection.binderConsoleListener.logPublish.observeOn(scheduler).subscribe(
            listener::onPrintln
        )
    }

    fun registerGlobalScriptExecutionListener(listener: BinderScriptListener) =
        globalScriptListener.add(listener)

    fun unregisterGlobalScriptExecutionListener(listener: BinderScriptListener) =
        globalScriptListener.remove(listener)

    private val TAG = "EngineController"
}

internal object TrackedScriptExecutionLifecycle {
    private const val POLL_INTERVAL_MILLIS = 25L

    suspend fun stopAndAwait(
        execution: ScriptExecution,
        isRegistered: (ScriptExecution) -> Boolean,
        timeoutMillis: Long,
        pollIntervalMillis: Long = POLL_INTERVAL_MILLIS
    ): Boolean = withTimeoutOrNull(timeoutMillis) {
        val engine = awaitEngine(execution, isRegistered, pollIntervalMillis)
            ?: return@withTimeoutOrNull true
        if (!engine.isDestroyed) {
            engine.forceStop()
        }
        awaitEngineStopped(engine, pollIntervalMillis)
        true
    } ?: false

    suspend fun awaitStopped(
        execution: ScriptExecution,
        isRegistered: (ScriptExecution) -> Boolean,
        timeoutMillis: Long,
        pollIntervalMillis: Long = POLL_INTERVAL_MILLIS
    ): Boolean = withTimeoutOrNull(timeoutMillis) {
        val engine = awaitEngine(execution, isRegistered, pollIntervalMillis)
            ?: return@withTimeoutOrNull true
        awaitEngineStopped(engine, pollIntervalMillis)
        true
    } ?: false

    private suspend fun awaitEngine(
        execution: ScriptExecution,
        isRegistered: (ScriptExecution) -> Boolean,
        pollIntervalMillis: Long
    ): ScriptEngine<*>? {
        while (true) {
            execution.engine?.let { return it }
            if (!isRegistered(execution)) {
                return null
            }
            delay(pollIntervalMillis)
        }
    }

    private suspend fun awaitEngineStopped(
        engine: ScriptEngine<*>,
        pollIntervalMillis: Long
    ) {
        while (!engine.isDestroyed) {
            delay(pollIntervalMillis)
        }
    }
}
