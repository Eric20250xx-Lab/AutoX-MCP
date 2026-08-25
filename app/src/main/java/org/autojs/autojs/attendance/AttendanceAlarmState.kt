package org.autojs.autojs.attendance

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal data class AttendanceAlarmSnapshot(
    val state: String = STATE_IDLE,
    val plannedAtMillis: Long = 0L,
    val plannedElapsedRealtimeMillis: Long = 0L,
    val receivedAtMillis: Long = 0L,
    val receivedElapsedRealtimeMillis: Long = 0L,
    val delayMillis: Long = 0L,
    val serviceStartedAtMillis: Long = 0L,
    val serviceStartedElapsedRealtimeMillis: Long = 0L,
    val serviceFinishedAtMillis: Long = 0L,
    val serviceFinishedElapsedRealtimeMillis: Long = 0L
) {
    companion object {
        const val STATE_IDLE = "IDLE"
        const val STATE_PERMISSION_REQUIRED = "PERMISSION_REQUIRED"
        const val STATE_SCHEDULED = "SCHEDULED"
        const val STATE_RECEIVED = "RECEIVED"
        const val STATE_SERVICE_STARTED = "SERVICE_STARTED"
        const val STATE_FINISHED = "FINISHED"
        const val STATE_SERVICE_START_FAILED = "SERVICE_START_FAILED"
        const val STATE_ERROR = "ERROR"
    }
}

internal data class AttendanceAlarmReceiptTransition(
    val snapshot: AttendanceAlarmSnapshot,
    val shouldStartService: Boolean
)

internal fun planAttendanceAlarmTest(
    exactAlarmAllowed: Boolean,
    nowMillis: Long,
    elapsedRealtimeMillis: Long,
    delayMillis: Long = AttendanceAlarmScheduler.TEST_DELAY_MILLIS
): AttendanceAlarmSnapshot = if (exactAlarmAllowed) {
    AttendanceAlarmSnapshot(
        state = AttendanceAlarmSnapshot.STATE_SCHEDULED,
        plannedAtMillis = nowMillis + delayMillis,
        plannedElapsedRealtimeMillis = elapsedRealtimeMillis + delayMillis
    )
} else {
    AttendanceAlarmSnapshot(state = AttendanceAlarmSnapshot.STATE_PERMISSION_REQUIRED)
}

internal fun receiveAttendanceAlarm(
    previous: AttendanceAlarmSnapshot,
    plannedAtMillis: Long,
    plannedElapsedRealtimeMillis: Long,
    receivedAtMillis: Long,
    receivedElapsedRealtimeMillis: Long
): AttendanceAlarmReceiptTransition {
    val accepted = plannedAtMillis > 0L &&
        plannedElapsedRealtimeMillis > 0L &&
        previous.state == AttendanceAlarmSnapshot.STATE_SCHEDULED &&
        previous.plannedAtMillis == plannedAtMillis &&
        previous.plannedElapsedRealtimeMillis == plannedElapsedRealtimeMillis &&
        previous.receivedAtMillis == 0L
    if (!accepted) {
        return AttendanceAlarmReceiptTransition(previous, shouldStartService = false)
    }
    return AttendanceAlarmReceiptTransition(
        snapshot = previous.copy(
            state = AttendanceAlarmSnapshot.STATE_RECEIVED,
            receivedAtMillis = receivedAtMillis,
            receivedElapsedRealtimeMillis = receivedElapsedRealtimeMillis,
            delayMillis = receivedElapsedRealtimeMillis - plannedElapsedRealtimeMillis
        ),
        shouldStartService = true
    )
}

internal fun startAttendanceAlarmService(
    previous: AttendanceAlarmSnapshot,
    plannedAtMillis: Long,
    plannedElapsedRealtimeMillis: Long,
    startedAtMillis: Long,
    startedElapsedRealtimeMillis: Long
): AttendanceAlarmSnapshot = if (
    previous.state == AttendanceAlarmSnapshot.STATE_RECEIVED &&
    previous.plannedAtMillis == plannedAtMillis &&
    previous.plannedElapsedRealtimeMillis == plannedElapsedRealtimeMillis &&
    previous.receivedAtMillis > 0L &&
    previous.receivedElapsedRealtimeMillis > 0L &&
    previous.serviceStartedAtMillis == 0L
) {
    previous.copy(
        state = AttendanceAlarmSnapshot.STATE_SERVICE_STARTED,
        serviceStartedAtMillis = startedAtMillis,
        serviceStartedElapsedRealtimeMillis = startedElapsedRealtimeMillis
    )
} else {
    previous
}

internal fun finishAttendanceAlarmService(
    previous: AttendanceAlarmSnapshot,
    plannedAtMillis: Long,
    plannedElapsedRealtimeMillis: Long,
    finishedAtMillis: Long,
    finishedElapsedRealtimeMillis: Long
): AttendanceAlarmSnapshot = if (
    previous.state == AttendanceAlarmSnapshot.STATE_SERVICE_STARTED &&
    previous.plannedAtMillis == plannedAtMillis &&
    previous.plannedElapsedRealtimeMillis == plannedElapsedRealtimeMillis &&
    previous.serviceStartedAtMillis > 0L &&
    previous.serviceStartedElapsedRealtimeMillis > 0L &&
    previous.serviceFinishedAtMillis == 0L
) {
    previous.copy(
        state = AttendanceAlarmSnapshot.STATE_FINISHED,
        serviceFinishedAtMillis = finishedAtMillis,
        serviceFinishedElapsedRealtimeMillis = finishedElapsedRealtimeMillis
    )
} else {
    previous
}

internal fun failAttendanceAlarmServiceStart(
    previous: AttendanceAlarmSnapshot,
    plannedAtMillis: Long,
    plannedElapsedRealtimeMillis: Long
): AttendanceAlarmSnapshot = if (
    previous.state == AttendanceAlarmSnapshot.STATE_RECEIVED &&
    previous.plannedAtMillis == plannedAtMillis &&
    previous.plannedElapsedRealtimeMillis == plannedElapsedRealtimeMillis
) {
    previous.copy(state = AttendanceAlarmSnapshot.STATE_SERVICE_START_FAILED)
} else {
    previous
}

internal object AttendanceAlarmStore {
    @Synchronized
    fun snapshot(context: Context): AttendanceAlarmSnapshot = runCatching {
        readSnapshot(database(context.applicationContext).readableDatabase)
    }.getOrElse {
        AttendanceAlarmSnapshot(state = AttendanceAlarmSnapshot.STATE_ERROR)
    }

    @Synchronized
    fun replace(context: Context, snapshot: AttendanceAlarmSnapshot): Boolean =
        mutate(context.applicationContext) { snapshot }

    @Synchronized
    fun recordReceived(
        context: Context,
        plannedAtMillis: Long,
        plannedElapsedRealtimeMillis: Long,
        receivedAtMillis: Long,
        receivedElapsedRealtimeMillis: Long
    ): Boolean {
        var shouldStartService = false
        val stored = mutate(context.applicationContext) { previous ->
            val transition = receiveAttendanceAlarm(
                previous = previous,
                plannedAtMillis = plannedAtMillis,
                plannedElapsedRealtimeMillis = plannedElapsedRealtimeMillis,
                receivedAtMillis = receivedAtMillis,
                receivedElapsedRealtimeMillis = receivedElapsedRealtimeMillis
            )
            shouldStartService = transition.shouldStartService
            transition.snapshot.takeIf { transition.shouldStartService }
        }
        return shouldStartService && stored
    }

    @Synchronized
    fun recordServiceStarted(
        context: Context,
        plannedAtMillis: Long,
        plannedElapsedRealtimeMillis: Long,
        startedAtMillis: Long,
        startedElapsedRealtimeMillis: Long
    ): Boolean {
        return mutate(context.applicationContext) { previous ->
            startAttendanceAlarmService(
                previous,
                plannedAtMillis,
                plannedElapsedRealtimeMillis,
                startedAtMillis,
                startedElapsedRealtimeMillis
            ).takeUnless { it === previous }
        }
    }

    @Synchronized
    fun recordServiceFinished(
        context: Context,
        plannedAtMillis: Long,
        plannedElapsedRealtimeMillis: Long,
        finishedAtMillis: Long,
        finishedElapsedRealtimeMillis: Long
    ): Boolean {
        return mutate(context.applicationContext) { previous ->
            finishAttendanceAlarmService(
                previous,
                plannedAtMillis,
                plannedElapsedRealtimeMillis,
                finishedAtMillis,
                finishedElapsedRealtimeMillis
            ).takeUnless { it === previous }
        }
    }

    @Synchronized
    fun recordServiceStartFailure(
        context: Context,
        plannedAtMillis: Long,
        plannedElapsedRealtimeMillis: Long
    ) {
        mutate(context.applicationContext) { previous ->
            failAttendanceAlarmServiceStart(
                previous,
                plannedAtMillis,
                plannedElapsedRealtimeMillis
            ).takeUnless { it === previous }
        }
    }

    private fun mutate(
        context: Context,
        transform: (AttendanceAlarmSnapshot) -> AttendanceAlarmSnapshot?
    ): Boolean = runCatching {
        val sqlite = database(context).writableDatabase
        sqlite.beginTransaction()
        try {
            val next = transform(readSnapshot(sqlite)) ?: return@runCatching false
            val stored = writeSnapshot(sqlite, next)
            if (stored) sqlite.setTransactionSuccessful()
            stored
        } finally {
            sqlite.endTransaction()
        }
    }.getOrDefault(false)

    private fun readSnapshot(sqlite: SQLiteDatabase): AttendanceAlarmSnapshot =
        sqlite.rawQuery(
            "SELECT $COLUMN_STATE, $COLUMN_PLANNED_AT, $COLUMN_PLANNED_ELAPSED, " +
                "$COLUMN_RECEIVED_AT, $COLUMN_RECEIVED_ELAPSED, $COLUMN_DELAY, " +
                "$COLUMN_SERVICE_STARTED_AT, $COLUMN_SERVICE_STARTED_ELAPSED, " +
                "$COLUMN_SERVICE_FINISHED_AT, $COLUMN_SERVICE_FINISHED_ELAPSED " +
                "FROM $TABLE_SNAPSHOT WHERE $COLUMN_ID = $SNAPSHOT_ID",
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return AttendanceAlarmSnapshot()
            AttendanceAlarmSnapshot(
                state = cursor.getString(0),
                plannedAtMillis = cursor.getLong(1),
                plannedElapsedRealtimeMillis = cursor.getLong(2),
                receivedAtMillis = cursor.getLong(3),
                receivedElapsedRealtimeMillis = cursor.getLong(4),
                delayMillis = cursor.getLong(5),
                serviceStartedAtMillis = cursor.getLong(6),
                serviceStartedElapsedRealtimeMillis = cursor.getLong(7),
                serviceFinishedAtMillis = cursor.getLong(8),
                serviceFinishedElapsedRealtimeMillis = cursor.getLong(9)
            )
        }

    private fun writeSnapshot(
        sqlite: SQLiteDatabase,
        snapshot: AttendanceAlarmSnapshot
    ): Boolean = sqlite.insertWithOnConflict(
        TABLE_SNAPSHOT,
        null,
        ContentValues(11).apply {
            put(COLUMN_ID, SNAPSHOT_ID)
            put(COLUMN_STATE, snapshot.state)
            put(COLUMN_PLANNED_AT, snapshot.plannedAtMillis)
            put(COLUMN_PLANNED_ELAPSED, snapshot.plannedElapsedRealtimeMillis)
            put(COLUMN_RECEIVED_AT, snapshot.receivedAtMillis)
            put(COLUMN_RECEIVED_ELAPSED, snapshot.receivedElapsedRealtimeMillis)
            put(COLUMN_DELAY, snapshot.delayMillis)
            put(COLUMN_SERVICE_STARTED_AT, snapshot.serviceStartedAtMillis)
            put(
                COLUMN_SERVICE_STARTED_ELAPSED,
                snapshot.serviceStartedElapsedRealtimeMillis
            )
            put(COLUMN_SERVICE_FINISHED_AT, snapshot.serviceFinishedAtMillis)
            put(
                COLUMN_SERVICE_FINISHED_ELAPSED,
                snapshot.serviceFinishedElapsedRealtimeMillis
            )
        },
        SQLiteDatabase.CONFLICT_REPLACE
    ) != -1L

    private fun database(context: Context): AttendanceAlarmDatabase =
        databaseInstance ?: synchronized(this) {
            databaseInstance ?: AttendanceAlarmDatabase(context.applicationContext).also {
                databaseInstance = it
            }
        }

    private class AttendanceAlarmDatabase(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE_SNAPSHOT (" +
                    "$COLUMN_ID INTEGER NOT NULL PRIMARY KEY, " +
                    "$COLUMN_STATE TEXT NOT NULL, " +
                    "$COLUMN_PLANNED_AT INTEGER NOT NULL, " +
                    "$COLUMN_PLANNED_ELAPSED INTEGER NOT NULL, " +
                    "$COLUMN_RECEIVED_AT INTEGER NOT NULL, " +
                    "$COLUMN_RECEIVED_ELAPSED INTEGER NOT NULL, " +
                    "$COLUMN_DELAY INTEGER NOT NULL, " +
                    "$COLUMN_SERVICE_STARTED_AT INTEGER NOT NULL, " +
                    "$COLUMN_SERVICE_STARTED_ELAPSED INTEGER NOT NULL, " +
                    "$COLUMN_SERVICE_FINISHED_AT INTEGER NOT NULL, " +
                    "$COLUMN_SERVICE_FINISHED_ELAPSED INTEGER NOT NULL)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Volatile
    private var databaseInstance: AttendanceAlarmDatabase? = null

    private const val DATABASE_NAME = "attendance_alarm_delivery_test.db"
    private const val DATABASE_VERSION = 1
    private const val TABLE_SNAPSHOT = "alarm_snapshot"
    private const val SNAPSHOT_ID = 1
    private const val COLUMN_ID = "id"
    private const val COLUMN_STATE = "state"
    private const val COLUMN_PLANNED_AT = "planned_at"
    private const val COLUMN_PLANNED_ELAPSED = "planned_elapsed"
    private const val COLUMN_RECEIVED_AT = "received_at"
    private const val COLUMN_RECEIVED_ELAPSED = "received_elapsed"
    private const val COLUMN_DELAY = "delivery_delay"
    private const val COLUMN_SERVICE_STARTED_AT = "service_started_at"
    private const val COLUMN_SERVICE_STARTED_ELAPSED = "service_started_elapsed"
    private const val COLUMN_SERVICE_FINISHED_AT = "service_finished_at"
    private const val COLUMN_SERVICE_FINISHED_ELAPSED = "service_finished_elapsed"
}
