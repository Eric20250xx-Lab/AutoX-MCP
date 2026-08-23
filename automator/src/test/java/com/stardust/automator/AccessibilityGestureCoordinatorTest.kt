package com.stardust.automator

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AccessibilityGestureCoordinatorTest {
    @Test
    fun leaseExcludesOtherGesturesUntilItIsReleased() {
        val first = AccessibilityGestureCoordinator.tryAcquire()
        assertNotNull(first)
        assertNull(AccessibilityGestureCoordinator.tryAcquire())

        first!!.close()
        val second = AccessibilityGestureCoordinator.tryAcquire()
        assertNotNull(second)
        second!!.close()
    }

    @Test
    fun releasingLeaseMoreThanOnceDoesNotCreateExtraPermits() {
        val first = AccessibilityGestureCoordinator.tryAcquire()
        assertNotNull(first)
        first!!.close()
        first.close()

        val second = AccessibilityGestureCoordinator.tryAcquire()
        assertNotNull(second)
        assertNull(AccessibilityGestureCoordinator.tryAcquire())
        second!!.close()
    }

    @Test
    fun handlerBackedSynchronousWaitTimesOutWhenCallbackNeverArrives() {
        val lease = AccessibilityGestureCoordinator.tryAcquire()
        assertNotNull(lease)
        val completion = AccessibilityGestureCoordinator.completion(
            lease!!,
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20)
        )

        assertFalse(completion.await())
        assertFalse(completion.complete(true))

        AccessibilityGestureCoordinator.tryAcquire()!!.close()
    }

    @Test
    fun nullHandlerSynchronousWaitTimesOutWhenCallbackNeverArrives() {
        val lease = AccessibilityGestureCoordinator.tryAcquire()
        assertNotNull(lease)
        val completion = AccessibilityGestureCoordinator.completion(
            lease!!,
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20)
        )

        assertFalse(completion.await())
        assertFalse(completion.complete(true))

        AccessibilityGestureCoordinator.tryAcquire()!!.close()
    }

    @Test
    fun callbackAndTimeoutRaceCompletesAndReleasesOnlyOnce() {
        val lease = AccessibilityGestureCoordinator.tryAcquire()
        assertNotNull(lease)
        val completion = AccessibilityGestureCoordinator.completion(lease!!, Long.MAX_VALUE)

        assertTrue(completion.complete(true))
        assertFalse(completion.complete(false))
        assertTrue(completion.await())

        val next = AccessibilityGestureCoordinator.tryAcquire()
        assertNotNull(next)
        assertNull(AccessibilityGestureCoordinator.tryAcquire())
        next!!.close()
    }

    @Test
    fun closingOwnerCancelsQueuedGestureBeforeDispatchAndLeavesCoordinatorUsable() {
        val blockingLease = AccessibilityGestureCoordinator.tryAcquire()
        assertNotNull(blockingLease)
        val owner = AccessibilityGestureCoordinator.Owner()
        val token = owner.capture()
        assertNotNull(token)
        val waiting = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val dispatched = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()

        executor.execute {
            waiting.countDown()
            val queuedLease = AccessibilityGestureCoordinator.acquire()
            if (queuedLease != null) {
                try {
                    owner.runIfActive(token!!) {
                        dispatched.set(true)
                    }
                } finally {
                    queuedLease.close()
                }
            }
            finished.countDown()
        }

        assertTrue(waiting.await(1, TimeUnit.SECONDS))
        owner.close()
        blockingLease!!.close()
        assertTrue(finished.await(1, TimeUnit.SECONDS))
        executor.shutdownNow()

        assertFalse(dispatched.get())
        AccessibilityGestureCoordinator.tryAcquire()!!.close()
    }
}
