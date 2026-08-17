package com.stardust.autojs

import android.content.ContextWrapper
import com.stardust.autojs.engine.ScriptEngine
import com.stardust.autojs.engine.ScriptEngineManager
import com.stardust.autojs.execution.ExecutionConfig
import com.stardust.autojs.execution.RunnableScriptExecution
import com.stardust.autojs.execution.ScriptExecution
import com.stardust.autojs.execution.ScriptExecutionListener
import com.stardust.autojs.execution.ScriptExecutionTask
import com.stardust.autojs.script.ScriptSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScriptExecutionFailureTest {

    @Test
    fun missingEngineNotifiesOnceAndLeavesRegistry() {
        val listener = RecordingListener()
        val source = object : ScriptSource("missing-engine") {
            override val engineName = "missing-engine"
        }
        val execution = object : RunnableScriptExecution(
            ScriptEngineManager(ContextWrapper(null)),
            ScriptExecutionTask(source, listener, ExecutionConfig())
        ) {
            override fun onException(engine: ScriptEngine<*>?, e: Throwable) {
                listener.onException(this, e)
            }
        }
        val registry = ScriptExecutionRegistry().apply { register(execution) }

        runTrackedScriptExecution(execution, registry)

        assertEquals(0, listener.starts)
        assertEquals(0, listener.successes)
        assertEquals(1, listener.exceptions)
        assertNull(execution.engine)
        assertNull(registry.get(execution.id))
    }

    private class RecordingListener : ScriptExecutionListener {
        var starts = 0
        var successes = 0
        var exceptions = 0

        override fun onStart(execution: ScriptExecution) {
            starts++
        }

        override fun onSuccess(execution: ScriptExecution, result: Any?) {
            successes++
        }

        override fun onException(execution: ScriptExecution, e: Throwable) {
            exceptions++
        }
    }
}
