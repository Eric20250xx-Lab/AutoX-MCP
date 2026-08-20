package org.autojs.autojs.guardian

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal interface ScriptGuardianWakeLockHandle {
    val isHeld: Boolean

    fun acquire(timeoutMillis: Long)

    fun release()
}

internal fun interface ScriptGuardianWakeLockFactory {
    fun create(): ScriptGuardianWakeLockHandle
}

internal class ScriptGuardianWakeLockLease(
    private val scope: CoroutineScope,
    private val factory: ScriptGuardianWakeLockFactory,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val onHeldChanged: (Boolean) -> Unit = {},
    private val onFailure: (Throwable) -> Unit = {}
) {
    private val lock = Any()

    @Volatile
    private var running = false
    private var generation = 0L
    private var loopJob: Job? = null
    private var current: ScriptGuardianWakeLockHandle? = null

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            val runGeneration = ++generation
            loopJob = scope.launch {
                var nextDelay = 0L
                while (isActive && running) {
                    if (nextDelay > 0L) wait(nextDelay)
                    if (!isActive || !running) break
                    nextDelay = if (renew(runGeneration)) {
                        RENEW_MILLIS
                    } else {
                        FAILURE_RETRY_MILLIS
                    }
                }
            }
        }
    }

    fun stop() {
        val job: Job?
        val held: ScriptGuardianWakeLockHandle?
        val stoppedGeneration: Long
        synchronized(lock) {
            if (!running && loopJob == null && current == null) return
            running = false
            stoppedGeneration = ++generation
            job = loopJob
            loopJob = null
            held = current
            current = null
        }
        job?.cancel()
        held.releaseSafely()
        synchronized(lock) {
            if (!running && generation == stoppedGeneration) {
                runCatching { onHeldChanged(false) }
            }
        }
    }

    internal fun isHeld(): Boolean = synchronized(lock) {
        current?.isHeld == true
    }

    private fun renew(runGeneration: Long): Boolean {
        val replacement = try {
            factory.create().also { it.acquire(LEASE_MILLIS) }
        } catch (error: Throwable) {
            runCatching { onFailure(error) }
            synchronized(lock) {
                if (running && generation == runGeneration && current?.isHeld != true) {
                    runCatching { onHeldChanged(false) }
                }
            }
            return false
        }

        var previous: ScriptGuardianWakeLockHandle? = null
        val accepted = synchronized(lock) {
            if (!running || generation != runGeneration) {
                false
            } else {
                previous = current
                current = replacement
                true
            }
        }
        if (!accepted) {
            replacement.releaseSafely()
            return false
        }
        previous.releaseSafely()
        return synchronized(lock) {
            if (running &&
                generation == runGeneration &&
                current === replacement &&
                replacement.isHeld
            ) {
                runCatching { onHeldChanged(true) }
                true
            } else {
                false
            }
        }
    }

    private fun ScriptGuardianWakeLockHandle?.releaseSafely() {
        if (this == null) return
        runCatching {
            if (isHeld) release()
        }.onFailure { error -> runCatching { onFailure(error) } }
    }

    companion object {
        internal const val LEASE_MILLIS = 600_000L
        internal const val RENEW_MILLIS = 300_000L
        internal const val FAILURE_RETRY_MILLIS = 30_000L

        fun androidFactory(context: Context): ScriptGuardianWakeLockFactory {
            val appContext = context.applicationContext
            val powerManager = appContext.getSystemService(PowerManager::class.java)
            return ScriptGuardianWakeLockFactory {
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "${appContext.packageName}:ScriptGuardian"
                ).apply {
                    setReferenceCounted(false)
                }
                object : ScriptGuardianWakeLockHandle {
                    override val isHeld: Boolean
                        get() = wakeLock.isHeld

                    override fun acquire(timeoutMillis: Long) {
                        wakeLock.acquire(timeoutMillis)
                    }

                    override fun release() {
                        wakeLock.release()
                    }
                }
            }
        }
    }
}
