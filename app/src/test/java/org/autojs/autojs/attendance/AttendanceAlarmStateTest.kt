package org.autojs.autojs.attendance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttendanceAlarmStateTest {
    @Test
    fun planRequiresExactAlarmAccessAndUsesOneTwoMinuteOccurrence() {
        assertEquals(
            AttendanceAlarmSnapshot(state = AttendanceAlarmSnapshot.STATE_PERMISSION_REQUIRED),
            planAttendanceAlarmTest(
                exactAlarmAllowed = false,
                nowMillis = 1_000L,
                elapsedRealtimeMillis = 2_000L
            )
        )

        assertEquals(
            AttendanceAlarmSnapshot(
                state = AttendanceAlarmSnapshot.STATE_SCHEDULED,
                plannedAtMillis = 121_000L,
                plannedElapsedRealtimeMillis = 122_000L
            ),
            planAttendanceAlarmTest(
                exactAlarmAllowed = true,
                nowMillis = 1_000L,
                elapsedRealtimeMillis = 2_000L
            )
        )
    }

    @Test
    fun matchingReceiptRecordsSignedDelayAndStartsServiceOnce() {
        val plannedAt = 120_000L
        val scheduled = AttendanceAlarmSnapshot(
            state = AttendanceAlarmSnapshot.STATE_SCHEDULED,
            plannedAtMillis = plannedAt,
            plannedElapsedRealtimeMillis = 220_000L
        )

        val first = receiveAttendanceAlarm(
            previous = scheduled,
            plannedAtMillis = plannedAt,
            plannedElapsedRealtimeMillis = 220_000L,
            receivedAtMillis = 991_500L,
            receivedElapsedRealtimeMillis = 221_500L
        )

        assertTrue(first.shouldStartService)
        assertEquals(AttendanceAlarmSnapshot.STATE_RECEIVED, first.snapshot.state)
        assertEquals(991_500L, first.snapshot.receivedAtMillis)
        assertEquals(1_500L, first.snapshot.delayMillis)

        val duplicate = receiveAttendanceAlarm(
            previous = first.snapshot,
            plannedAtMillis = plannedAt,
            plannedElapsedRealtimeMillis = 220_000L,
            receivedAtMillis = 122_000L,
            receivedElapsedRealtimeMillis = 222_000L
        )
        assertFalse(duplicate.shouldStartService)
        assertEquals(first.snapshot, duplicate.snapshot)
    }

    @Test
    fun staleOrMalformedReceiptNeverStartsService() {
        val scheduled = AttendanceAlarmSnapshot(
            state = AttendanceAlarmSnapshot.STATE_SCHEDULED,
            plannedAtMillis = 120_000L,
            plannedElapsedRealtimeMillis = 220_000L
        )

        listOf(0L, 119_999L, 120_001L).forEach { receivedPlan ->
            val result = receiveAttendanceAlarm(
                previous = scheduled,
                plannedAtMillis = receivedPlan,
                plannedElapsedRealtimeMillis = 220_000L,
                receivedAtMillis = 121_000L,
                receivedElapsedRealtimeMillis = 221_000L
            )
            assertFalse(result.shouldStartService)
            assertEquals(scheduled, result.snapshot)
        }
        val mismatchedElapsedPlan = receiveAttendanceAlarm(
            previous = scheduled,
            plannedAtMillis = 120_000L,
            plannedElapsedRealtimeMillis = 219_999L,
            receivedAtMillis = 121_000L,
            receivedElapsedRealtimeMillis = 221_000L
        )
        assertFalse(mismatchedElapsedPlan.shouldStartService)
        assertEquals(scheduled, mismatchedElapsedPlan.snapshot)
    }

    @Test
    fun serviceLifecycleRequiresThePersistedReceiptAndSameOccurrence() {
        val received = AttendanceAlarmSnapshot(
            state = AttendanceAlarmSnapshot.STATE_RECEIVED,
            plannedAtMillis = 120_000L,
            plannedElapsedRealtimeMillis = 220_000L,
            receivedAtMillis = 121_000L,
            receivedElapsedRealtimeMillis = 221_000L,
            delayMillis = 1_000L
        )

        assertEquals(
            received,
            startAttendanceAlarmService(
                received,
                119_000L,
                220_000L,
                121_100L,
                221_100L
            )
        )

        val started = startAttendanceAlarmService(
            received,
            120_000L,
            220_000L,
            121_100L,
            221_100L
        )
        assertEquals(AttendanceAlarmSnapshot.STATE_SERVICE_STARTED, started.state)
        assertEquals(121_100L, started.serviceStartedAtMillis)
        assertEquals(221_100L, started.serviceStartedElapsedRealtimeMillis)
        assertEquals(
            started,
            startAttendanceAlarmService(
                started,
                120_000L,
                220_000L,
                121_200L,
                221_200L
            )
        )

        assertEquals(
            started,
            finishAttendanceAlarmService(
                started,
                119_000L,
                220_000L,
                121_300L,
                221_300L
            )
        )
        val finished = finishAttendanceAlarmService(
            started,
            120_000L,
            220_000L,
            121_300L,
            221_300L
        )
        assertEquals(AttendanceAlarmSnapshot.STATE_FINISHED, finished.state)
        assertEquals(121_300L, finished.serviceFinishedAtMillis)
        assertEquals(221_300L, finished.serviceFinishedElapsedRealtimeMillis)
    }

    @Test
    fun serviceStartFailureOnlyMarksTheMatchingReceivedOccurrence() {
        val received = AttendanceAlarmSnapshot(
            state = AttendanceAlarmSnapshot.STATE_RECEIVED,
            plannedAtMillis = 120_000L,
            plannedElapsedRealtimeMillis = 220_000L,
            receivedAtMillis = 121_000L,
            receivedElapsedRealtimeMillis = 221_000L
        )

        assertEquals(
            received,
            failAttendanceAlarmServiceStart(received, 119_000L, 220_000L)
        )
        assertEquals(
            AttendanceAlarmSnapshot.STATE_SERVICE_START_FAILED,
            failAttendanceAlarmServiceStart(received, 120_000L, 220_000L).state
        )
    }

    @Test
    fun receiverAcceptsOnlyItsPrivateDeliveryTestAction() {
        assertTrue(
            isAttendanceAlarmDeliveryTestAction(
                AttendanceAlarmScheduler.ACTION_DELIVERY_TEST
            )
        )
        assertFalse(isAttendanceAlarmDeliveryTestAction(null))
        assertFalse(isAttendanceAlarmDeliveryTestAction("android.intent.action.BOOT_COMPLETED"))
    }
}
