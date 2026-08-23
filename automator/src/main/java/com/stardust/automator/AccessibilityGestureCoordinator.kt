package com.stardust.automator

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object AccessibilityGestureCoordinator {
    class Lease internal constructor(
        private val semaphore: Semaphore
    ) : AutoCloseable {
        private val released = AtomicBoolean(false)

        override fun close() {
            if (released.compareAndSet(false, true)) {
                semaphore.release()
            }
        }
    }

    class Completion internal constructor(
        private val lease: Lease,
        private val deadlineNanos: Long
    ) {
        private val completed = AtomicBoolean(false)
        private val result = AtomicReference<Boolean>()
        private val latch = CountDownLatch(1)

        fun complete(success: Boolean): Boolean {
            if (!completed.compareAndSet(false, true)) {
                return false
            }
            result.set(success && System.nanoTime() < deadlineNanos)
            lease.close()
            latch.countDown()
            return true
        }

        fun await(): Boolean {
            try {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos > 0L) {
                    latch.await(remainingNanos, TimeUnit.NANOSECONDS)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            complete(false)
            return result.get() == true
        }
    }

    class Owner {
        class Token internal constructor(
            internal val owner: Owner,
            internal val generation: Long
        )

        private val lock = Any()
        private var generation = 0L
        private var closed = false

        fun capture(): Token? = synchronized(lock) {
            if (closed) null else Token(this, generation)
        }

        fun isActive(token: Token): Boolean = synchronized(lock) {
            token.owner === this && !closed && generation == token.generation
        }

        fun runIfActive(token: Token, action: () -> Unit): Boolean = synchronized(lock) {
            if (token.owner !== this || closed || generation != token.generation) {
                return@synchronized false
            }
            action()
            true
        }

        fun close() = synchronized(lock) {
            if (!closed) {
                closed = true
                generation += 1L
            }
        }
    }

    private val semaphore = Semaphore(1, true)

    fun acquire(): Lease? = try {
        semaphore.acquire()
        Lease(semaphore)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    }

    fun tryAcquire(): Lease? =
        if (semaphore.tryAcquire()) Lease(semaphore) else null

    fun completion(lease: Lease, deadlineNanos: Long): Completion = Completion(lease, deadlineNanos)
}
