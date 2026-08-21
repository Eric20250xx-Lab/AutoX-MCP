package org.autojs.autojs.guardian

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ScriptGuardianWakeLockLeaseTest {
    private lateinit var scope: CoroutineScope
    private lateinit var waits: ManualWait

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        waits = ManualWait()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun acquiresTenMinuteLeaseAndRenewsAfterFiveMinutesWithoutGap() = runBlocking {
        val events = mutableListOf<String>()
        val first = FakeWakeLock("first", events)
        val second = FakeWakeLock("second", events)
        val handles = ArrayDeque(listOf(first, second))
        val lease = ScriptGuardianWakeLockLease(
            scope,
            ScriptGuardianWakeLockFactory { handles.removeFirst() },
            waits::await
        )

        lease.start()
        yield()

        assertEquals(listOf("first:acquire:600000"), events)
        assertTrue(waits.hasPending(ScriptGuardianWakeLockLease.RENEW_MILLIS))

        waits.release(ScriptGuardianWakeLockLease.RENEW_MILLIS)
        yield()

        assertEquals(
            listOf(
                "first:acquire:600000",
                "second:acquire:600000",
                "first:release"
            ),
            events
        )
        assertTrue(second.isHeld)
        lease.stop()
        assertEquals("second:release", events.last())
        assertFalse(lease.isHeld())
    }

    @Test
    fun failedAcquireRetriesWhileKeepingCurrentLease() = runBlocking {
        val events = mutableListOf<String>()
        val first = FakeWakeLock("first", events)
        val second = FakeWakeLock("second", events)
        var createCount = 0
        val failures = mutableListOf<Throwable>()
        val lease = ScriptGuardianWakeLockLease(
            scope,
            ScriptGuardianWakeLockFactory {
                createCount += 1
                when (createCount) {
                    1 -> first
                    2 -> throw IllegalStateException("temporary failure")
                    else -> second
                }
            },
            waits::await,
            onFailure = failures::add
        )

        lease.start()
        yield()
        waits.release(ScriptGuardianWakeLockLease.RENEW_MILLIS)
        yield()

        assertTrue(first.isHeld)
        assertEquals(1, failures.size)
        assertTrue(waits.hasPending(ScriptGuardianWakeLockLease.FAILURE_RETRY_MILLIS))

        waits.release(ScriptGuardianWakeLockLease.FAILURE_RETRY_MILLIS)
        yield()

        assertFalse(first.isHeld)
        assertTrue(second.isHeld)
        lease.stop()
    }

    @Test
    fun refreshNowWhileRunningImmediatelyReplacesCurrentLease() = runBlocking {
        val events = mutableListOf<String>()
        val first = FakeWakeLock("first", events)
        val second = FakeWakeLock("second", events)
        val handles = ArrayDeque(listOf(first, second))
        val lease = ScriptGuardianWakeLockLease(
            scope,
            ScriptGuardianWakeLockFactory { handles.removeFirst() },
            waits::await
        )

        lease.start()
        yield()

        assertTrue(lease.refreshNow())
        assertEquals(
            listOf(
                "first:acquire:600000",
                "second:acquire:600000",
                "first:release"
            ),
            events
        )
        assertTrue(second.isHeld)
        assertTrue(waits.hasPending(ScriptGuardianWakeLockLease.RENEW_MILLIS))

        lease.stop()
        assertEquals("second:release", events.last())
    }

    @Test
    fun refreshNowWhileStoppedDoesNotCreateOrAcquireALock() {
        var createCount = 0
        val lease = ScriptGuardianWakeLockLease(
            scope,
            ScriptGuardianWakeLockFactory {
                createCount += 1
                FakeWakeLock("unused", mutableListOf())
            },
            waits::await
        )

        assertFalse(lease.refreshNow())
        assertEquals(0, createCount)
        assertFalse(lease.isHeld())
    }

    @Test
    fun stopRejectsARefreshThatFinishesAcquiringAfterStop() = runBlocking {
        val backgroundJob = SupervisorJob()
        val backgroundScope = CoroutineScope(backgroundJob + Dispatchers.Default)
        val acquireStarted = CountDownLatch(1)
        val allowAcquire = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val heldEvents = CopyOnWriteArrayList<Boolean>()
        val first = FakeWakeLock("first", events)
        val replacement = BlockingAcquireWakeLock(
            "replacement",
            events,
            acquireStarted,
            allowAcquire
        )
        val lease = ScriptGuardianWakeLockLease(
            backgroundScope,
            ScriptGuardianWakeLockFactory { first },
            wait = { CompletableDeferred<Unit>().await() },
            onHeldChanged = heldEvents::add
        )

        try {
            lease.start()
            withTimeout(5_000L) {
                while (!lease.isHeld()) yield()
            }

            val refreshResult = async(Dispatchers.Default) {
                lease.refreshNow(ScriptGuardianWakeLockFactory { replacement })
            }
            assertTrue(acquireStarted.await(5L, TimeUnit.SECONDS))

            lease.stop()
            assertFalse(lease.isHeld())

            allowAcquire.countDown()
            assertFalse(withTimeout(5_000L) { refreshResult.await() })
            assertEquals(
                listOf(
                    "first:acquire:600000",
                    "replacement:acquire:600000",
                    "first:release",
                    "replacement:release"
                ),
                events.toList()
            )
            assertEquals(listOf(true, false), heldEvents.toList())
            assertFalse(lease.isHeld())
        } finally {
            allowAcquire.countDown()
            lease.stop()
            backgroundScope.cancel()
        }
    }

    @Test
    fun stopPreventsInFlightRenewalFromPublishingHeldAgain() = runBlocking {
        val backgroundJob = SupervisorJob()
        val backgroundScope = CoroutineScope(backgroundJob + Dispatchers.Default)
        val releaseStarted = CountDownLatch(1)
        val allowRelease = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val heldEvents = CopyOnWriteArrayList<Boolean>()
        val first = BlockingReleaseWakeLock(
            "first",
            events,
            releaseStarted,
            allowRelease
        )
        val second = FakeWakeLock("second", events)
        val handles = ArrayDeque(listOf(first, second))
        val renewalWaiting = CompletableDeferred<Unit>()
        val renewNow = CompletableDeferred<Unit>()
        val lease = ScriptGuardianWakeLockLease(
            backgroundScope,
            ScriptGuardianWakeLockFactory { handles.removeFirst() },
            wait = {
                renewalWaiting.complete(Unit)
                renewNow.await()
            },
            onHeldChanged = heldEvents::add
        )

        try {
            lease.start()
            withTimeout(5_000L) { renewalWaiting.await() }
            renewNow.complete(Unit)
            assertTrue(releaseStarted.await(5L, TimeUnit.SECONDS))

            lease.stop()
            assertEquals(listOf(true, false), heldEvents.toList())

            allowRelease.countDown()
            withTimeout(5_000L) {
                backgroundJob.children.toList().forEach { it.join() }
            }
            assertEquals(listOf(true, false), heldEvents.toList())
            assertFalse(lease.isHeld())
        } finally {
            allowRelease.countDown()
            lease.stop()
            backgroundScope.cancel()
        }
    }

    private class FakeWakeLock(
        private val name: String,
        private val events: MutableList<String>
    ) : ScriptGuardianWakeLockHandle {
        override var isHeld = false
            private set

        override fun acquire(timeoutMillis: Long) {
            events += "$name:acquire:$timeoutMillis"
            isHeld = true
        }

        override fun release() {
            events += "$name:release"
            isHeld = false
        }
    }

    private class BlockingReleaseWakeLock(
        private val name: String,
        private val events: MutableList<String>,
        private val releaseStarted: CountDownLatch,
        private val allowRelease: CountDownLatch
    ) : ScriptGuardianWakeLockHandle {
        @Volatile
        override var isHeld = false
            private set

        override fun acquire(timeoutMillis: Long) {
            events += "$name:acquire:$timeoutMillis"
            isHeld = true
        }

        override fun release() {
            events += "$name:release"
            releaseStarted.countDown()
            allowRelease.await()
            isHeld = false
        }
    }

    private class BlockingAcquireWakeLock(
        private val name: String,
        private val events: MutableList<String>,
        private val acquireStarted: CountDownLatch,
        private val allowAcquire: CountDownLatch
    ) : ScriptGuardianWakeLockHandle {
        @Volatile
        override var isHeld = false
            private set

        override fun acquire(timeoutMillis: Long) {
            events += "$name:acquire:$timeoutMillis"
            acquireStarted.countDown()
            allowAcquire.await()
            isHeld = true
        }

        override fun release() {
            events += "$name:release"
            isHeld = false
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
}
