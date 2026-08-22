package com.stardust.automator

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

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
}
