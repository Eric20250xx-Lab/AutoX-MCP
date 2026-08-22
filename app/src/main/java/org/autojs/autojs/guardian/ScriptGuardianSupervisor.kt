package org.autojs.autojs.guardian

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

internal interface ScriptGuardianExecution

internal interface ScriptGuardianRunner {
    suspend fun findRunning(file: File): List<ScriptGuardianExecution>

    fun start(
        file: File,
        sessionId: String,
        onStarted: () -> Unit,
        onFinished: (Throwable?) -> Unit
    ): ScriptGuardianExecution

    suspend fun stopAndAwait(
        execution: ScriptGuardianExecution,
        timeoutMillis: Long = STOP_TIMEOUT_MILLIS
    ): Boolean

    suspend fun awaitStopped(
        execution: ScriptGuardianExecution,
        timeoutMillis: Long = STOP_TIMEOUT_MILLIS
    ): Boolean

    fun stopNow(execution: ScriptGuardianExecution)

    suspend fun releaseGuard()

    fun close()

    companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

internal sealed interface ScriptGuardianStatus {
    data object Disabled : ScriptGuardianStatus
    data class Starting(val file: File) : ScriptGuardianStatus
    data class Running(
        val file: File,
        val heartbeat: ScriptGuardianHeartbeatReport? = null
    ) : ScriptGuardianStatus
    data class BusyWarning(val file: File, val commandId: String) : ScriptGuardianStatus
    data class Stopping(val file: File) : ScriptGuardianStatus
    data class Retrying(val file: File, val delayMillis: Long, val reason: String) :
        ScriptGuardianStatus
}

/**
 * Owns one long-running script. Commands and execution callbacks are serialized through one queue,
 * while potentially slow engine shutdowns happen outside that queue.
 */
internal class ScriptGuardianSupervisor(
    private val scope: CoroutineScope,
    private val runner: ScriptGuardianRunner,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val onStatus: (ScriptGuardianStatus) -> Unit = {},
    private val bridgeAckTimeoutMillis: Long = BRIDGE_ACK_TIMEOUT_MILLIS,
    private val onBridgeRequestClaimed: () -> Unit = {}
) {
    private data class Desired(val generation: Long, val file: File)

    private data class Slot(
        val id: Long,
        val generation: Long,
        val file: File,
        val sessionId: String,
        val execution: ScriptGuardianExecution
    )

    private data class StoppingSlot(
        val slot: Slot,
        val unexpected: Boolean,
        val retryReason: String? = null
    )

    private enum class BridgeRequestState {
        PENDING,
        CLAIMED,
        CANCELLED
    }

    private class BridgeRequest {
        private val state = AtomicReference(BridgeRequestState.PENDING)
        val result = CompletableDeferred<Boolean>()

        fun claim(): Boolean =
            state.compareAndSet(BridgeRequestState.PENDING, BridgeRequestState.CLAIMED)

        fun cancelPending(): Boolean =
            state.compareAndSet(BridgeRequestState.PENDING, BridgeRequestState.CANCELLED)

        fun isClaimed(): Boolean = state.get() == BridgeRequestState.CLAIMED
    }

    private sealed interface Command {
        data class Apply(val file: File?) : Command
        data class Started(val slotId: Long) : Command
        data class Finished(val slotId: Long, val error: Throwable?) : Command
        data class ExpectHeartbeat(
            val sessionId: String,
            val request: BridgeRequest
        ) : Command
        data class Heartbeat(
            val report: ScriptGuardianHeartbeatReport,
            val request: BridgeRequest
        ) : Command
        data class FirstHeartbeatExpired(val slotId: Long) : Command
        data class HeartbeatExpired(val slotId: Long, val sequence: Long) : Command
        data class BusyWarning(val slotId: Long, val sequence: Long) : Command
        data class TakeoverCompleted(
            val generation: Long,
            val success: Boolean,
            val reason: String
        ) : Command
        data class StopCompleted(val slotId: Long, val success: Boolean) : Command
        data class RetryStop(val slotId: Long) : Command
        data class RetryStart(val generation: Long) : Command
        data class Healthy(val slotId: Long) : Command
        data class Close(val completed: CompletableDeferred<Unit>) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val loopJob = scope.launch {
        for (command in commands) {
            actorThread = Thread.currentThread()
            try {
                handle(command)
            } finally {
                actorThread = null
            }
        }
    }

    private var generation = 0L
    private var nextSlotId = 0L
    private var desired: Desired? = null
    private var active: Slot? = null
    private var stopping: StoppingSlot? = null
    private var takeoverGeneration: Long? = null
    private var takeoverJob: Job? = null
    private var stopJob: Job? = null
    private var retryJob: Job? = null
    private var healthyJob: Job? = null
    private var firstHeartbeatJob: Job? = null
    private var heartbeatJob: Job? = null
    private var busyWarningJob: Job? = null
    private var heartbeatState: ScriptGuardianHeartbeatReport? = null
    private var heartbeatRequired = false
    private var backoffIndex = 0
    private var closing: CompletableDeferred<Unit>? = null

    @Volatile
    private var currentExecution: ScriptGuardianExecution? = null

    @Volatile
    private var actorThread: Thread? = null

    @Volatile
    private var currentHeartbeatState: ScriptGuardianHeartbeatState? = null

    @Volatile
    private var busyHeartbeatPending = false

    fun reconcile(file: File?) {
        commands.trySend(Command.Apply(file?.canonicalFile))
    }

    fun reportHeartbeat(report: ScriptGuardianHeartbeatReport): Boolean {
        return awaitBridgeAcknowledgement { request ->
            Command.Heartbeat(report, request)
        }
    }

    fun expectHeartbeat(sessionId: String): Boolean {
        val normalizedSessionId = sessionId.trim()
        if (normalizedSessionId.isEmpty()) return false
        return awaitBridgeAcknowledgement { request ->
            Command.ExpectHeartbeat(normalizedSessionId, request)
        }
    }

    fun allowsBackgroundKeyguardGesture(): Boolean =
        currentHeartbeatState == ScriptGuardianHeartbeatState.IDLE && !busyHeartbeatPending

    suspend fun close() {
        val completed = CompletableDeferred<Unit>()
        commands.send(Command.Close(completed))
        completed.await()
        loopJob.join()
    }

    fun closeNow() {
        retryJob?.cancel()
        healthyJob?.cancel()
        firstHeartbeatJob?.cancel()
        heartbeatJob?.cancel()
        busyWarningJob?.cancel()
        takeoverJob?.cancel()
        stopJob?.cancel()
        currentExecution?.let { execution ->
            runCatching { runner.stopNow(execution) }
        }
        commands.close()
        loopJob.cancel()
    }

    private fun handle(command: Command) {
        when (command) {
            is Command.Apply -> apply(command.file)
            is Command.Started -> {
                active?.takeIf { it.id == command.slotId }?.let {
                    onStatus(ScriptGuardianStatus.Running(it.file))
                }
            }

            is Command.Finished -> executionFinished(command)
            is Command.ExpectHeartbeat -> handleExpectedHeartbeat(command)
            is Command.Heartbeat -> handleHeartbeat(command)
            is Command.FirstHeartbeatExpired -> firstHeartbeatExpired(command.slotId)
            is Command.HeartbeatExpired -> heartbeatExpired(command)
            is Command.BusyWarning -> busyWarning(command)
            is Command.TakeoverCompleted -> takeoverCompleted(command)
            is Command.StopCompleted -> stopCompleted(command)
            is Command.RetryStop -> retryStop(command.slotId)
            is Command.RetryStart -> {
                retryJob = null
                if (desired?.generation == command.generation) {
                    reconcileCurrent()
                }
            }

            is Command.Healthy -> {
                if (active?.id == command.slotId) {
                    backoffIndex = 0
                }
            }

            is Command.Close -> beginClose(command.completed)
        }
    }

    private fun apply(file: File?) {
        if (closing != null) return
        if (desired?.file == file || desired == null && file == null) return

        generation += 1
        desired = file?.let { Desired(generation, it) }
        backoffIndex = 0
        retryJob?.cancel()
        retryJob = null
        healthyJob?.cancel()
        healthyJob = null
        clearHeartbeatState()

        active?.let {
            beginStop(it, unexpected = false)
            return
        }
        if (stopping == null && takeoverGeneration == null) {
            reconcileCurrent()
        }
    }

    private fun reconcileCurrent() {
        if (closing != null || active != null || stopping != null || takeoverGeneration != null) {
            return
        }
        val target = desired
        if (target == null) {
            onStatus(ScriptGuardianStatus.Disabled)
            return
        }

        takeoverGeneration = target.generation
        onStatus(ScriptGuardianStatus.Stopping(target.file))
        takeoverJob = scope.launch {
            val result = runCatching {
                val existing = runner.findRunning(target.file)
                for (execution in existing) {
                    check(runner.stopAndAwait(execution)) {
                        "existing script did not stop"
                    }
                }
            }
            commands.send(
                Command.TakeoverCompleted(
                    generation = target.generation,
                    success = result.isSuccess,
                    reason = result.exceptionOrNull()?.message ?: "existing script did not stop"
                )
            )
        }
    }

    private fun takeoverCompleted(command: Command.TakeoverCompleted) {
        if (takeoverGeneration != command.generation) return
        takeoverGeneration = null
        takeoverJob = null
        if (closing != null) {
            finishCloseIfIdle()
            return
        }
        val target = desired
        if (target == null || target.generation != command.generation) {
            reconcileCurrent()
        } else if (command.success) {
            start(target)
        } else {
            scheduleRetry(target, command.reason)
        }
    }

    private fun start(target: Desired) {
        if (desired != target || closing != null) return
        onStatus(ScriptGuardianStatus.Starting(target.file))
        val slotId = ++nextSlotId
        val sessionId = UUID.randomUUID().toString()
        try {
            val execution = runner.start(
                target.file,
                sessionId,
                onStarted = { commands.trySend(Command.Started(slotId)) },
                onFinished = { error -> commands.trySend(Command.Finished(slotId, error)) }
            )
            val slot = Slot(slotId, target.generation, target.file, sessionId, execution)
            active = slot
            currentExecution = execution
            onStatus(ScriptGuardianStatus.Running(target.file))
            healthyJob?.cancel()
            healthyJob = scope.launch {
                wait(HEALTHY_RESET_MILLIS)
                commands.send(Command.Healthy(slotId))
            }
        } catch (error: Throwable) {
            scheduleRetry(target, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun executionFinished(command: Command.Finished) {
        val slot = active?.takeIf { it.id == command.slotId } ?: return
        active = null
        healthyJob?.cancel()
        healthyJob = null
        val reason = command.error?.let(::describeError) ?: "script exited"
        clearHeartbeatState()
        stopping = StoppingSlot(slot, unexpected = true, retryReason = reason)
        onStatus(ScriptGuardianStatus.Stopping(slot.file))
        stopJob = scope.launch {
            val success = runCatching { runner.awaitStopped(slot.execution) }.getOrDefault(false)
            commands.send(Command.StopCompleted(slot.id, success))
        }
    }

    private fun beginStop(slot: Slot, unexpected: Boolean, retryReason: String? = null) {
        if (active?.id == slot.id) active = null
        healthyJob?.cancel()
        healthyJob = null
        clearHeartbeatState()
        stopping = StoppingSlot(slot, unexpected, retryReason)
        currentExecution = slot.execution
        onStatus(ScriptGuardianStatus.Stopping(slot.file))
        stopJob = scope.launch {
            val success = runCatching { runner.stopAndAwait(slot.execution) }.getOrDefault(false)
            commands.send(Command.StopCompleted(slot.id, success))
        }
    }

    private fun stopCompleted(command: Command.StopCompleted) {
        val stopped = stopping?.takeIf { it.slot.id == command.slotId } ?: return
        stopJob = null
        if (!command.success) {
            scheduleStopRetry(stopped)
            return
        }

        stopping = null
        currentExecution = null
        if (closing != null) {
            finishCloseIfIdle()
            return
        }
        val target = desired
        if (stopped.unexpected && target?.generation == stopped.slot.generation &&
            target.file == stopped.slot.file
        ) {
            scheduleRetry(target, stopped.retryReason ?: "script exited")
        } else {
            reconcileCurrent()
        }
    }

    private fun scheduleStopRetry(stopped: StoppingSlot) {
        onStatus(ScriptGuardianStatus.Stopping(stopped.slot.file))
        stopJob = scope.launch {
            wait(STOP_RETRY_MILLIS)
            commands.send(Command.RetryStop(stopped.slot.id))
        }
    }

    private fun retryStop(slotId: Long) {
        val stopped = stopping?.takeIf { it.slot.id == slotId } ?: return
        stopJob = scope.launch {
            val success = runCatching {
                runner.stopAndAwait(stopped.slot.execution)
            }.getOrDefault(false)
            commands.send(Command.StopCompleted(slotId, success))
        }
    }

    private fun scheduleRetry(target: Desired, reason: String) {
        if (desired != target || closing != null) return
        retryJob?.cancel()
        val delayMillis = BACKOFF_MILLIS[min(backoffIndex, BACKOFF_MILLIS.lastIndex)]
        backoffIndex = min(backoffIndex + 1, BACKOFF_MILLIS.lastIndex)
        onStatus(ScriptGuardianStatus.Retrying(target.file, delayMillis, reason))
        retryJob = scope.launch {
            wait(delayMillis)
            commands.send(Command.RetryStart(target.generation))
        }
    }

    private fun beginClose(completed: CompletableDeferred<Unit>) {
        if (closing != null) {
            completed.complete(Unit)
            return
        }
        closing = completed
        desired = null
        retryJob?.cancel()
        retryJob = null
        healthyJob?.cancel()
        healthyJob = null
        clearHeartbeatState()
        active?.let {
            beginStop(it, unexpected = false)
            return
        }
        finishCloseIfIdle()
    }

    private fun finishCloseIfIdle() {
        val completed = closing ?: return
        if (active != null || stopping != null || takeoverGeneration != null) return
        onStatus(ScriptGuardianStatus.Disabled)
        completed.complete(Unit)
        commands.close()
    }

    private fun handleExpectedHeartbeat(command: Command.ExpectHeartbeat) {
        if (!command.request.claim()) {
            command.request.result.complete(false)
            return
        }
        val accepted = runCatching {
            onBridgeRequestClaimed()
            heartbeatExpected(command.sessionId)
        }.getOrDefault(false)
        command.request.result.complete(accepted)
    }

    private fun handleHeartbeat(command: Command.Heartbeat) {
        if (!command.request.claim()) {
            command.request.result.complete(false)
            return
        }
        val status = runCatching {
            onBridgeRequestClaimed()
            acceptHeartbeat(command.report)
        }.getOrNull()
        command.request.result.complete(status != null)
        status?.let { acceptedStatus ->
            runCatching { onStatus(acceptedStatus) }
        }
    }

    private fun acceptHeartbeat(
        report: ScriptGuardianHeartbeatReport
    ): ScriptGuardianStatus.Running? {
        if (report.sequence <= 0L) return null
        if (
            report.state == ScriptGuardianHeartbeatState.BUSY &&
            report.commandId.isNullOrBlank()
        ) {
            return null
        }
        val slot = active?.takeIf { it.sessionId == report.sessionId } ?: return null
        val previous = heartbeatState
        if (previous != null && report.sequence <= previous.sequence) return null

        heartbeatRequired = true
        firstHeartbeatJob?.cancel()
        firstHeartbeatJob = null
        heartbeatState = report
        currentHeartbeatState = report.state
        busyHeartbeatPending = report.state == ScriptGuardianHeartbeatState.BUSY
        heartbeatJob?.cancel()
        heartbeatJob = null
        busyWarningJob?.cancel()
        busyWarningJob = null
        when (report.state) {
            ScriptGuardianHeartbeatState.IDLE -> {
                heartbeatJob = scope.launch {
                    wait(IDLE_HEARTBEAT_TIMEOUT_MILLIS)
                    commands.send(Command.HeartbeatExpired(slot.id, report.sequence))
                }
            }

            ScriptGuardianHeartbeatState.BUSY -> {
                busyWarningJob = scope.launch {
                    wait(BUSY_WARNING_MILLIS)
                    commands.send(Command.BusyWarning(slot.id, report.sequence))
                }
            }
        }
        return ScriptGuardianStatus.Running(slot.file, report)
    }

    private fun heartbeatExpected(sessionId: String): Boolean {
        val slot = active?.takeIf { it.sessionId == sessionId } ?: return false
        if (heartbeatRequired) return true
        heartbeatRequired = true
        if (heartbeatState != null) return true
        firstHeartbeatJob?.cancel()
        firstHeartbeatJob = scope.launch {
            wait(FIRST_HEARTBEAT_TIMEOUT_MILLIS)
            commands.send(Command.FirstHeartbeatExpired(slot.id))
        }
        return true
    }

    private fun awaitBridgeAcknowledgement(
        command: (BridgeRequest) -> Command
    ): Boolean {
        if (Thread.currentThread() === actorThread || bridgeAckTimeoutMillis <= 0L) return false
        val request = BridgeRequest()
        if (!commands.trySend(command(request)).isSuccess) return false
        val resultBeforeTimeout = runBlocking {
            withTimeoutOrNull(bridgeAckTimeoutMillis) {
                request.result.await()
            }
        }
        if (resultBeforeTimeout != null) return resultBeforeTimeout
        if (request.cancelPending()) return false
        if (!request.isClaimed()) return false
        return runBlocking { request.result.await() }
    }

    private fun firstHeartbeatExpired(slotId: Long) {
        firstHeartbeatJob = null
        if (!heartbeatRequired || heartbeatState != null) return
        val slot = active?.takeIf { it.id == slotId } ?: return
        beginStop(
            slot,
            unexpected = true,
            retryReason =
                "first heartbeat timed out after ${FIRST_HEARTBEAT_TIMEOUT_MILLIS / 1_000}s"
        )
    }

    private fun heartbeatExpired(command: Command.HeartbeatExpired) {
        heartbeatJob = null
        val report = heartbeatState?.takeIf {
            it.sequence == command.sequence && it.state == ScriptGuardianHeartbeatState.IDLE
        } ?: return
        val slot = active?.takeIf {
            it.id == command.slotId && it.sessionId == report.sessionId
        } ?: return
        beginStop(
            slot,
            unexpected = true,
            retryReason = "idle heartbeat timed out after ${IDLE_HEARTBEAT_TIMEOUT_MILLIS / 1_000}s"
        )
    }

    private fun busyWarning(command: Command.BusyWarning) {
        busyWarningJob = null
        val report = heartbeatState?.takeIf {
            it.sequence == command.sequence && it.state == ScriptGuardianHeartbeatState.BUSY
        } ?: return
        val slot = active?.takeIf {
            it.id == command.slotId && it.sessionId == report.sessionId
        } ?: return
        onStatus(ScriptGuardianStatus.BusyWarning(slot.file, report.commandId.orEmpty()))
    }

    private fun clearHeartbeatState() {
        firstHeartbeatJob?.cancel()
        firstHeartbeatJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        busyWarningJob?.cancel()
        busyWarningJob = null
        heartbeatState = null
        heartbeatRequired = false
        currentHeartbeatState = null
        busyHeartbeatPending = false
    }

    private fun describeError(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() }
            ?.let { "${error.javaClass.simpleName}: $it" }
            ?: error.javaClass.simpleName

    companion object {
        private val BACKOFF_MILLIS = longArrayOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L)
        private const val HEALTHY_RESET_MILLIS = 120_000L
        private const val STOP_RETRY_MILLIS = 5_000L
        internal const val FIRST_HEARTBEAT_TIMEOUT_MILLIS = 30_000L
        internal const val IDLE_HEARTBEAT_TIMEOUT_MILLIS = 45_000L
        internal const val BUSY_WARNING_MILLIS = 300_000L
        internal const val BRIDGE_ACK_TIMEOUT_MILLIS = 1_000L
    }
}
