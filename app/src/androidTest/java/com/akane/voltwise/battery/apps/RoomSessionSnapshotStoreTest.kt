package com.akane.voltwise.battery.apps

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.voltwise.battery.data.db.AppSnapshotKind
import com.akane.voltwise.battery.data.db.BatteryDatabase
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.SessionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The Room side of the per-app pipeline: A3's one-baseline, row-must-exist, unique-uid and atomic-end rules. */
@RunWith(AndroidJUnit4::class)
class RoomSessionSnapshotStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, BatteryDatabase::class.java).build()
    private val store = RoomSessionSnapshotStore(db)

    @After fun close() = db.close()

    private fun session(id: String, type: SessionType = SessionType.DISCHARGE, open: Boolean = false,
                        status: AppUsageStatus? = AppUsageStatus.PENDING, start: Long = 1_000) =
        ChargeSession(id, type, start, if (open) null else start + 500, 80, null, null, null, null,
            lastSampleTime = start + 500, appUsageStatus = status)

    private fun snapshot(window: Long, capturedAt: Long, vararg rows: AppUsageRow) = AppUsageSnapshot(window, 3, capturedAt, rows.toList())

    @Test fun oneBaselinePerExistingSessionWithSharedUidsCollapsed() = runBlocking {
        assertFalse("No row yet: orphan pruning would delete it", store.saveBaseline("B", snapshot(100, 1, AppUsageRow(1, "a", 1.0))))
        db.sessionDao().insert(session("B", open = true))
        val first = snapshot(100, 2, AppUsageRow(1, "a", 1.0), AppUsageRow(1, "a2", 0.5), AppUsageRow(2, "b", 2.0))
        assertTrue(store.saveBaseline("B", first))
        assertFalse(store.saveBaseline("B", snapshot(100, 3, AppUsageRow(1, "a", 9.0))))
        val stored = store.baseline("B")!!
        assertEquals(listOf(1 to 1.5, 2 to 2.0), stored.rows.map { it.uid to it.powerMah })
        assertEquals(100L, stored.windowStartedAt)
        assertTrue(store.hasBaseline("B"))
        assertEquals(1, db.appUsageDao().snapshots().count { it.kind == AppSnapshotKind.BASELINE })
    }

    @Test fun endWritesSnapshotBreakdownAndStatusTogether() = runBlocking {
        db.sessionDao().insert(session("A"))
        val end = snapshot(100, 5, AppUsageRow(1, "a", 3.0))
        val result = AppUsageDelta.compute(null, end)
        assertTrue(store.saveEnd("A", end, result))
        val row = db.sessionDao().byId("A")!!
        assertEquals(AppUsageStatus.READY, row.appUsageStatus)
        assertEquals(AppUsageBasis.ABSOLUTE, row.appUsageBasis)
        assertEquals(listOf("a"), db.appUsageDao().sessionUsageRows("A").map { it.packageName })
        assertNotNull(db.appUsageDao().latestSnapshot("A", AppSnapshotKind.END))
        assertFalse("A deleted session is skipped", store.saveEnd("gone", end, result))
    }

    @Test fun pendingClosedDischargesSkipsOpenReadyLegacyAndChargingSessions() = runBlocking {
        db.sessionDao().insert(session("closed-pending", start = 1_000))
        db.sessionDao().insert(session("closed-ready", status = AppUsageStatus.READY, start = 2_000))
        db.sessionDao().insert(session("legacy", status = null, start = 3_000))
        db.sessionDao().insert(session("charge", SessionType.CHARGE, status = AppUsageStatus.NOT_APPLICABLE, start = 4_000))
        db.sessionDao().insert(session("open", open = true, start = 5_000))
        assertEquals(listOf("closed-pending"), store.pendingClosedDischarges())
        store.setStatus("closed-pending", AppUsageStatus.FAILED)
        assertEquals(AppUsageStatus.FAILED, db.sessionDao().byId("closed-pending")!!.appUsageStatus)
        assertTrue(store.pendingClosedDischarges().isEmpty())
        assertEquals(OpenSession("open", SessionType.DISCHARGE), store.openSession().first())
    }

    @Test fun snapshotsArePrunedToTheOpenBaselinePlusTheNewestThree() = runBlocking {
        db.sessionDao().insert(session("open", open = true, start = 1))
        assertTrue(store.saveBaseline("open", snapshot(100, 1, AppUsageRow(1, "a", 1.0))))
        repeat(5) { i ->
            db.sessionDao().insert(session("s$i", start = 100L + i))
            store.saveEnd("s$i", snapshot(100, 10L + i, AppUsageRow(1, "a", 2.0)), AppUsageDelta.compute(null, snapshot(100, 10L + i, AppUsageRow(1, "a", 2.0))))
        }
        val kept = db.appUsageDao().snapshots()
        assertEquals(4, kept.size)
        assertTrue(kept.any { it.sessionId == "open" && it.kind == AppSnapshotKind.BASELINE })
    }
}
