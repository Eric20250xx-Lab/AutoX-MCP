package com.stardust.autojs.script

import com.aiselp.autox.engine.NodeScriptSource
import com.stardust.autojs.engine.ScriptEngine
import com.stardust.autojs.execution.ExecutionConfig
import com.stardust.autojs.execution.ScriptExecution
import com.stardust.autojs.execution.ScriptExecutionListener
import com.stardust.autojs.servicecomponents.TaskInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ScriptSourceFileTest {

    @Test
    fun mapsSupportedFileBackedSources() {
        val javascriptFile = File("scripts/example.js")
        val nodeFile = File("scripts/example.mjs")
        val autoFile = File("scripts/example.auto")

        assertEquals(javascriptFile, JavaScriptFileSource(javascriptFile).sourceFileOrNull())
        assertEquals(nodeFile, NodeScriptSource(nodeFile).sourceFileOrNull())
        assertEquals(autoFile, AutoFileSource(autoFile).sourceFileOrNull())
    }

    @Test
    fun returnsNullForNonFileSource() {
        val source = object : ScriptSource("memory") {
            override val engineName = "memory"
        }

        assertNull(source.sourceFileOrNull())
    }

    @Test
    fun executionTaskInfoUsesBackingFilePath() {
        val source = NodeScriptSource(File("scripts/example.mjs"))

        val task = TaskInfo.ExecutionTaskInfo(FakeExecution(source))

        assertEquals(source.file.path, task.sourcePath)
    }

    private class FakeExecution(
        private val scriptSource: ScriptSource
    ) : ScriptExecution {
        override fun getEngine(): ScriptEngine<*>? = null
        override fun getSource(): ScriptSource = scriptSource
        override fun getListener(): ScriptExecutionListener = LISTENER
        override fun getConfig(): ExecutionConfig = ExecutionConfig(workingDirectory = "scripts")
        override fun getId(): Int = 7
    }

    companion object {
        private val LISTENER = object : ScriptExecutionListener {
            override fun onStart(execution: ScriptExecution) = Unit
            override fun onSuccess(execution: ScriptExecution, result: Any?) = Unit
            override fun onException(execution: ScriptExecution, e: Throwable) = Unit
        }
    }
}
