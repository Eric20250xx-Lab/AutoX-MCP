package org.autojs.autojs.guardian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScriptGuardianPrewarmSchedulerTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun parsesSupportedSeparatorsAndTimeFormatsThenDeduplicatesAndSorts() {
        val result = parseScriptGuardianPrewarmTimes(
            "17.45，08:15; 17:45；09.05\n06:30"
        ).getOrThrow()

        assertEquals(
            listOf(
                LocalTime.of(6, 30),
                LocalTime.of(8, 15),
                LocalTime.of(9, 5),
                LocalTime.of(17, 45)
            ),
            result
        )
    }

    @Test
    fun rejectsWholeConfigurationWhenAnyTimeIsInvalid() {
        listOf(
            "08:15,broken,17:45",
            "8:15",
            "24:00",
            "08:60",
            "08：15"
        ).forEach { raw ->
            assertTrue(
                "expected invalid prewarm times to fail closed: $raw",
                parseScriptGuardianPrewarmTimes(raw).isFailure
            )
        }
    }

    @Test
    fun treatsBlankOrSeparatorOnlyConfigurationAsEmpty() {
        listOf("", "  ", "，;；,\n").forEach { raw ->
            assertEquals(emptyList<LocalTime>(), parseScriptGuardianPrewarmTimes(raw).getOrThrow())
        }
    }

    @Test
    fun choosesNextTimeTodayThenRollsOverToTomorrow() {
        val times = listOf(LocalTime.of(8, 15), LocalTime.of(17, 45))

        assertEquals(
            timestamp(2026, 8, 20, 8, 15),
            nextScriptGuardianPrewarmAt(
                times,
                timestamp(2026, 8, 20, 8, 0),
                zoneId
            )
        )
        assertEquals(
            timestamp(2026, 8, 20, 17, 45),
            nextScriptGuardianPrewarmAt(
                times,
                timestamp(2026, 8, 20, 8, 15),
                zoneId
            )
        )
        assertEquals(
            timestamp(2026, 8, 21, 8, 15),
            nextScriptGuardianPrewarmAt(
                times,
                timestamp(2026, 8, 20, 18, 0),
                zoneId
            )
        )
    }

    @Test
    fun planFailsClosedForEveryUnschedulableState() {
        assertPlanState(
            ScriptGuardianPrewarmSnapshot.STATE_DISABLED,
            enabled = false
        )
        assertPlanState(
            ScriptGuardianPrewarmSnapshot.STATE_GUARDIAN_DISABLED,
            guardianEnabled = false
        )
        assertPlanState(
            ScriptGuardianPrewarmSnapshot.STATE_EMPTY_TIMES,
            rawTimes = ""
        )
        assertPlanState(
            ScriptGuardianPrewarmSnapshot.STATE_INVALID_TIMES,
            rawTimes = "08:15,invalid"
        )
        assertPlanState(
            ScriptGuardianPrewarmSnapshot.STATE_EXACT_ALARM_PERMISSION_REQUIRED,
            exactAlarmAllowed = false
        )
    }

    @Test
    fun scheduledPlanContainsOnlyTheNextTrigger() {
        val plan = planScriptGuardianPrewarm(
            prewarmEnabled = true,
            guardianEnabled = true,
            rawTimes = "08:15,17:45",
            exactAlarmAllowed = true,
            nowMillis = timestamp(2026, 8, 20, 9, 0),
            zoneId = zoneId
        )

        assertEquals(ScriptGuardianPrewarmSnapshot.STATE_SCHEDULED, plan.state)
        assertEquals(timestamp(2026, 8, 20, 17, 45), plan.nextTriggerAtMillis)
    }

    @Test
    fun productionScheduleUsesShanghaiTime() {
        assertEquals(ZoneId.of("Asia/Shanghai"), SCRIPT_GUARDIAN_PREWARM_ZONE_ID)
    }

    @Test
    fun receiptRebuildMovesPastScheduledTimeEvenIfAlarmArrivesEarly() {
        val scheduledAt = timestamp(2026, 8, 20, 8, 15)

        assertEquals(
            scheduledAt + 1L,
            scriptGuardianPrewarmRescheduleBase(
                nowMillis = scheduledAt - 2_000L,
                scheduledAtMillis = scheduledAt
            )
        )
        assertEquals(
            scheduledAt + 2_000L,
            scriptGuardianPrewarmRescheduleBase(
                nowMillis = scheduledAt + 2_000L,
                scheduledAtMillis = scheduledAt
            )
        )
    }

    @Test
    fun receiverProcessesOnlyANewerScheduledOccurrence() {
        val first = timestamp(2026, 8, 20, 8, 15)
        val second = timestamp(2026, 8, 20, 17, 45)
        val receivedFirst = ScriptGuardianPrewarmSnapshot(
            lastReceivedScheduledAtMillis = first
        )

        assertTrue(
            shouldReceiveScriptGuardianPrewarmOccurrence(
                ScriptGuardianPrewarmSnapshot(),
                first
            )
        )
        assertFalse(shouldReceiveScriptGuardianPrewarmOccurrence(receivedFirst, first))
        assertFalse(
            shouldReceiveScriptGuardianPrewarmOccurrence(
                receivedFirst,
                first - 1L
            )
        )
        assertTrue(shouldReceiveScriptGuardianPrewarmOccurrence(receivedFirst, second))
    }

    @Test
    fun duplicateReceiverStillReconcilesAfterReceiptWasPersistedBeforeReschedule() {
        val scheduledAt = timestamp(2026, 8, 20, 17, 45)
        val previous = ScriptGuardianPrewarmSnapshot(
            nextTriggerAtMillis = scheduledAt,
            lastReceivedScheduledAtMillis = scheduledAt
        )

        val flow = scriptGuardianPrewarmReceiverFlow(previous, scheduledAt)

        assertFalse(flow.shouldRestore)
        assertTrue(flow.shouldReconcile)
        assertEquals(
            0L,
            scriptGuardianPrewarmCatchUpScheduledAt(
                previous = previous,
                prewarmEnabled = true,
                guardianEnabled = true,
                nowMillis = scheduledAt + 1L
            )
        )
    }

    @Test
    fun settingsRebuildCatchesUpAnOverdueUnreceivedOccurrence() {
        val scheduledAt = timestamp(2026, 8, 20, 17, 45)
        val previous = ScriptGuardianPrewarmSnapshot(nextTriggerAtMillis = scheduledAt)

        assertEquals(
            scheduledAt,
            scriptGuardianPrewarmCatchUpScheduledAt(
                previous = previous,
                prewarmEnabled = true,
                guardianEnabled = true,
                nowMillis = scheduledAt + 6 * 60_000L
            )
        )
    }

    @Test
    fun settingsRebuildDoesNotCatchUpBeforeDueTimeOrAfterReceipt() {
        val scheduledAt = timestamp(2026, 8, 20, 17, 45)

        assertEquals(
            0L,
            scriptGuardianPrewarmCatchUpScheduledAt(
                previous = ScriptGuardianPrewarmSnapshot(
                    nextTriggerAtMillis = scheduledAt
                ),
                prewarmEnabled = true,
                guardianEnabled = true,
                nowMillis = scheduledAt - 1L
            )
        )
        assertEquals(
            0L,
            scriptGuardianPrewarmCatchUpScheduledAt(
                previous = ScriptGuardianPrewarmSnapshot(
                    nextTriggerAtMillis = scheduledAt,
                    lastReceivedScheduledAtMillis = scheduledAt
                ),
                prewarmEnabled = true,
                guardianEnabled = true,
                nowMillis = scheduledAt + 1L
            )
        )
    }

    @Test
    fun disabledPrewarmOrGuardianNeverCatchesUp() {
        val scheduledAt = timestamp(2026, 8, 20, 17, 45)
        val previous = ScriptGuardianPrewarmSnapshot(nextTriggerAtMillis = scheduledAt)

        assertEquals(
            0L,
            scriptGuardianPrewarmCatchUpScheduledAt(
                previous,
                prewarmEnabled = false,
                guardianEnabled = true,
                nowMillis = scheduledAt + 1L
            )
        )
        assertEquals(
            0L,
            scriptGuardianPrewarmCatchUpScheduledAt(
                previous,
                prewarmEnabled = true,
                guardianEnabled = false,
                nowMillis = scheduledAt + 1L
            )
        )
    }

    @Test
    fun receiverMapsOnlyItsThreeExplicitPackagePrivateActions() {
        assertEquals(
            ScriptGuardianPrewarmReceiverAction.SCHEDULED_PREWARM,
            scriptGuardianPrewarmReceiverAction(
                ScriptGuardianPrewarmScheduler.ACTION_PREWARM
            )
        )
        assertEquals(
            ScriptGuardianPrewarmReceiverAction.WATCHDOG_REQUEST_RESTORE,
            scriptGuardianPrewarmReceiverAction(
                ScriptGuardianPrewarmScheduler.ACTION_WATCHDOG_REQUEST_RESTORE
            )
        )
        assertEquals(
            ScriptGuardianPrewarmReceiverAction.WATCHDOG_VERIFY_AND_RECOVER,
            scriptGuardianPrewarmReceiverAction(
                ScriptGuardianPrewarmScheduler.ACTION_WATCHDOG_VERIFY_AND_RECOVER
            )
        )
        assertEquals(
            null,
            scriptGuardianPrewarmReceiverAction("android.intent.action.BOOT_COMPLETED")
        )
        assertEquals(null, scriptGuardianPrewarmReceiverAction(null))
    }

    @Test
    fun newExactAlarmOccurrenceMapsToVerifyAndRecoverOnly() {
        assertEquals(
            ScriptGuardianPrewarmReceiptAction.VERIFY_AND_RECOVER,
            scriptGuardianPrewarmReceiptAction(
                ScriptGuardianPrewarmReceiverFlow(
                    shouldRestore = true,
                    shouldReconcile = true
                )
            )
        )
        assertEquals(
            ScriptGuardianPrewarmReceiptAction.NONE,
            scriptGuardianPrewarmReceiptAction(
                ScriptGuardianPrewarmReceiverFlow(
                    shouldRestore = false,
                    shouldReconcile = true
                )
            )
        )
    }

    private fun assertPlanState(
        expected: String,
        enabled: Boolean = true,
        guardianEnabled: Boolean = true,
        rawTimes: String = "08:15,17:45",
        exactAlarmAllowed: Boolean = true
    ) {
        val plan = planScriptGuardianPrewarm(
            prewarmEnabled = enabled,
            guardianEnabled = guardianEnabled,
            rawTimes = rawTimes,
            exactAlarmAllowed = exactAlarmAllowed,
            nowMillis = timestamp(2026, 8, 20, 8, 0),
            zoneId = zoneId
        )

        assertEquals(expected, plan.state)
        assertEquals(0L, plan.nextTriggerAtMillis)
    }

    private fun timestamp(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int
    ): Long = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zoneId)
        .toInstant()
        .toEpochMilli()
}
