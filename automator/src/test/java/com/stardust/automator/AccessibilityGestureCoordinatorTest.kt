package com.stardust.automator

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

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
}
