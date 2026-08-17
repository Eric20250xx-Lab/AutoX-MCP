package org.autojs.autojs.guardian

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ScriptGuardianSupervisorTest {
    private lateinit var scope: CoroutineScope
    private lateinit var runner: FakeRunner
    private lateinit var waits: ManualWait
    private lateinit var supervisor: ScriptGuardianSupervisor

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        runner = FakeRunner()
        waits = ManualWait()
        supervisor = ScriptGuardianSupervisor(scope, runner, waits::await)
    }

    @After
    fun tearDown() {
        supervisor.closeNow()
        scope.cancel()
    }

    @Test
    fun repeatedConfigStartsOnlyOnce() = runBlocking {
        supervisor.reconcile(A)
        supervisor.reconcile(A)
        yield()

        assertEquals(listOf(A), runner.startedFiles)
    }

    @Test
    fun callbackBeforeStartReturnsStillRestartsOnce() = runBlocking {
        runner.finishBeforeReturn = true
        supervisor.reconcile(A)
        yield()

        assertEquals(1, runner.startAttempts)
        assertTrue(waits.hasPending(5_000L))

        waits.release(5_000L)
        yield()

        assertEquals(2, runner.startAttempts)
        assertEquals(listOf(A, A), runner.startedFiles)
    }

    @Test
    fun preEngineFailureAfterStartReturnsRestartsOnce() = runBlocking {
        supervisor.reconcile(A)
        yield()

        runner.starts.single().onFinished(IllegalStateException("engine creation failed"))
        yield()

        assertEquals(1, runner.startAttempts)
        assertTrue(waits.hasPending(5_000L))

        waits.release(5_000L)
        yield()

        assertEquals(2, runner.startAttempts)
        assertEquals(listOf(A, A), runner.startedFiles)
    }

    @Test
    fun latestPathWinsWhileOldExecutionStops() = runBlocking {
        supervisor.reconcile(A)
        val stopGate = CompletableDeferred<Boolean>()
        runner.stopGate = stopGate

        supervisor.reconcile(B)
        supervisor.reconcile(C)
        yield()
        assertEquals(listOf(A), runner.startedFiles)

        stopGate.complete(true)
        yield()

        assertEquals(listOf(A, C), runner.startedFiles)
        assertFalse(runner.startedFiles.contains(B))
    }

    @Test
    fun staleCallbackCannotStopReplacement() = runBlocking {
        supervisor.reconcile(A)
        val first = runner.starts.single()
        supervisor.reconcile(B)
        yield()
        assertEquals(listOf(A, B), runner.startedFiles)

        first.onFinished(null)
        yield()

        assertEquals(listOf(A, B), runner.startedFiles)
        assertFalse(waits.hasPending(5_000L))
    }

    @Test
    fun stopTimeoutDoesNotStartReplacement() = runBlocking {
        supervisor.reconcile(A)
        runner.stopResults.add(false)

        supervisor.reconcile(B)
        yield()

        assertEquals(listOf(A), runner.startedFiles)
        assertTrue(waits.hasPending(5_000L))
    }

    @Test
    fun disablingCancelsPendingRestart() = runBlocking {
        runner.startFailures = 1
        supervisor.reconcile(A)
        yield()
        assertTrue(waits.hasPending(5_000L))

        supervisor.reconcile(null)
        yield()

        assertFalse(waits.hasPending(5_000L))
        assertEquals(1, runner.startAttempts)
    }

    @Test
    fun existingMatchingExecutionsAreStoppedBeforeManagedStart() = runBlocking {
        runner.existing[A.canonicalPath] = mutableListOf(FakeExecution(90), FakeExecution(91))

        supervisor.reconcile(A)
        yield()

        assertEquals(2, runner.stopped.size)
        assertEquals(listOf(A), runner.startedFiles)
    }

    @Test
    fun retryBackoffGrowsAndCaps() = runBlocking {
        runner.startFailures = 6
        supervisor.reconcile(A)
        yield()

        listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L).forEach { delay ->
            assertTrue("missing retry delay $delay", waits.hasPending(delay))
            waits.release(delay)
            yield()
        }

        assertEquals(7, runner.startAttempts)
    }

    @Test
    fun closeStopsCurrentExecutionAndPreventsRestart() = runBlocking {
        supervisor.reconcile(A)
        supervisor.close()

        assertEquals(1, runner.stopped.size)
        runner.starts.single().onFinished(null)
        yield()
        assertFalse(waits.hasPending(5_000L))
    }

    private class FakeExecution(val id: Int) : ScriptGuardianExecution

    private data class StartRecord(
        val file: File,
        val execution: FakeExecution,
        val onFinished: (Throwable?) -> Unit
    )

    private class FakeRunner : ScriptGuardianRunner {
        val starts = mutableListOf<StartRecord>()
        val startedFiles: List<File>
            get() = starts.map { it.file }
        val stopped = mutableListOf<ScriptGuardianExecution>()
        val existing = mutableMapOf<String, MutableList<ScriptGuardianExecution>>()
        val stopResults = mutableListOf<Boolean>()
        var startAttempts = 0
        var startFailures = 0
        var finishBeforeReturn = false
        var stopGate: CompletableDeferred<Boolean>? = null

        override fun findRunning(file: File): List<ScriptGuardianExecution> =
            existing.remove(file.canonicalPath)?.toList().orEmpty()

        override fun start(
            file: File,
            onStarted: () -> Unit,
            onFinished: (Throwable?) -> Unit
        ): ScriptGuardianExecution {
            startAttempts += 1
            if (startFailures > 0) {
                startFailures -= 1
                throw IllegalStateException("start failed")
            }
            val execution = FakeExecution(startAttempts)
            starts += StartRecord(file, execution, onFinished)
            onStarted()
            if (finishBeforeReturn) {
                finishBeforeReturn = false
                onFinished(null)
            }
            return execution
        }

        override suspend fun stopAndAwait(
            execution: ScriptGuardianExecution,
            timeoutMillis: Long
        ): Boolean {
            stopped += execution
            stopGate?.let {
                stopGate = null
                return it.await()
            }
            return if (stopResults.isEmpty()) true else stopResults.removeAt(0)
        }

        override suspend fun awaitStopped(
            execution: ScriptGuardianExecution,
            timeoutMillis: Long
        ): Boolean = true

        override fun stopNow(execution: ScriptGuardianExecution) {
            stopped += execution
        }
    }

    private class ManualWait {
        private data class Pending(val delayMillis: Long, val gate: CompletableDeferred<Unit>)

        private val pending = mutableListOf<Pending>()

        suspend fun await(delayMillis: Long) {
            val item = Pending(delayMillis, CompletableDeferred())
            pending += item
            try {
                item.gate.await()
            } finally {
                pending.remove(item)
            }
        }

        fun hasPending(delayMillis: Long): Boolean =
            pending.any { it.delayMillis == delayMillis }

        fun release(delayMillis: Long) {
            pending.first { it.delayMillis == delayMillis }.gate.complete(Unit)
        }
    }

    companion object {
        private val A = File("/tmp/guardian-a.js").canonicalFile
        private val B = File("/tmp/guardian-b.js").canonicalFile
        private val C = File("/tmp/guardian-c.js").canonicalFile
    }
}
