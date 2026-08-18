package com.stardust.autojs.servicecomponents

import com.stardust.autojs.engine.ScriptEngine
import com.stardust.autojs.execution.ExecutionConfig
import com.stardust.autojs.execution.ScriptExecution
import com.stardust.autojs.execution.ScriptExecutionListener
import com.stardust.autojs.script.ScriptSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackedScriptExecutionLifecycleTest {

    @Test
    fun missingEngineIsStoppedOnlyAfterExecutionLeavesRegistry() = runBlocking {
        val execution = MutableExecution()
        var registrationChecks = 0

        val result = TrackedScriptExecutionLifecycle.awaitStopped(
            execution,
            isRegistered = {
                registrationChecks++
                registrationChecks == 1
            },
            timeoutMillis = 100L,
            pollIntervalMillis = 1L
        )

        assertTrue(result)
        assertEquals(2, registrationChecks)
    }

    @Test
    fun registeredExecutionWaitsForEngineThenStopsThatEngine() = runBlocking {
        val execution = MutableExecution()
        val engine = RecordingEngine()
        var registrationChecks = 0

        val result = TrackedScriptExecutionLifecycle.stopAndAwait(
            execution,
            isRegistered = {
                registrationChecks++
                if (registrationChecks == 2) {
                    execution.currentEngine = engine
                }
                true
            },
            timeoutMillis = 100L,
            pollIntervalMillis = 1L
        )

        assertTrue(result)
        assertTrue(registrationChecks >= 2)
        assertEquals(1, engine.forceStopCalls)
        assertTrue(engine.isDestroyed)
    }

    private class MutableExecution : ScriptExecution {
        @Volatile
        var currentEngine: ScriptEngine<*>? = null

        override fun getEngine(): ScriptEngine<*>? = currentEngine
        override fun getSource(): ScriptSource = SOURCE
        override fun getListener(): ScriptExecutionListener = LISTENER
        override fun getConfig(): ExecutionConfig = ExecutionConfig()
        override fun getId(): Int = 42
    }

    private class RecordingEngine : ScriptEngine.AbstractScriptEngine<ScriptSource>() {
        var forceStopCalls = 0

        override fun put(name: String, value: Any?) = Unit
        override fun execute(scriptSource: ScriptSource): Any? = null
        override fun forceStop() {
            forceStopCalls++
            destroy()
        }

        override fun init() = Unit
    }

    companion object {
        private val SOURCE = object : ScriptSource("tracked") {
            override val engineName = "tracked"
        }
        private val LISTENER = object : ScriptExecutionListener {
            override fun onStart(execution: ScriptExecution) = Unit
            override fun onSuccess(execution: ScriptExecution, result: Any?) = Unit
            override fun onException(execution: ScriptExecution, e: Throwable) = Unit
        }
    }
}
