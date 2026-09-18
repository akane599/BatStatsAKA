package app.batstats.battery.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.batstats.battery.data.db.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HistoryImportTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun sample(time: Long = 1500) = BatterySample(timestamp = time, levelPercent = 60, status = 3, plugged = 0,
        currentNowUa = -1000, chargeCounterUah = 2_000_000, voltageMv = 4000, temperatureDeciC = 250, health = 2,
        screenOn = true, elapsedMs = time, uptimeMs = time, sessionId = "session", observationId = "observation", source = "BatteryManager")
    private fun session() = ChargeSession("session", SessionType.DISCHARGE, 1000, null, 60, 59, 1000, -1000, null,
        lastSampleTime = 2000, observationId = "observation", observedMs = 1000, counterCoveredMs = 1000,
        screenOnMs = 1000, screenOnUah = 1000, source = "BatteryManager observed interval")
    private fun database() = Room.inMemoryDatabaseBuilder(context, BatteryDatabase::class.java).build()

    private inline fun <T> BatteryDatabase.useDatabase(block: (BatteryDatabase) -> T): T = try { block(this) } finally { close() }

    @Test fun repeatedImportDoesNotDuplicateOrRestoreAnActiveSession() = runBlocking {
        database().useDatabase { db ->
            val manager = ExportImportManager(context, db, HistoryMaintenance())
            val payload = BatteryExport(listOf(sample()), listOf(session()))
            assertEquals(HistoryImportResult(1, 1, 0, 0), manager.importPayload(payload))
            assertEquals(HistoryImportResult(0, 0, 0, 2), manager.importPayload(payload))
            assertNull(db.sessionDao().active())
            assertEquals(2000L, db.sessionDao().byId("import:session")!!.endTime)
            assertEquals(1, db.batteryDao().count())
        }
    }
    @Test fun ownExportPreservesTheLocalActiveSessionAndCanRestoreAPrunedPoint() = runBlocking {
        database().useDatabase { db ->
            val manager = ExportImportManager(context, db, HistoryMaintenance())
            db.sessionDao().insert(session())
            db.batteryDao().insertSample(sample())
            val payload = manager.snapshot(0, 3000, true, true)
            assertEquals(HistoryImportResult(0, 0, 0, 2), manager.importPayload(payload))
            assertEquals(session(), db.sessionDao().active())
            db.batteryDao().clearAll()
            assertEquals(HistoryImportResult(1, 0, 0, 1), manager.importPayload(payload))
            assertEquals("session", db.batteryDao().lastSample()!!.sessionId)
            assertEquals(1, db.batteryDao().samplesForSession("session").first().size)
            assertEquals(HistoryImportResult(0, 0, 0, 2), manager.importPayload(payload))
            assertEquals(session(), db.sessionDao().active())
        }
    }
    @Test fun conflictingPointRollsBackTheEntireImportIncludingEarlierSessionInserts() = runBlocking {
        database().useDatabase { db ->
            val manager = ExportImportManager(context, db, HistoryMaintenance())
            db.batteryDao().insertSample(sample())
            try {
                manager.importPayload(BatteryExport(listOf(sample().copy(currentNowUa = -2000)), listOf(session())))
                fail("Conflicting observed point must fail")
            } catch (_: IllegalArgumentException) { }
            assertEquals(0, db.sessionDao().count())
            assertEquals(1, db.batteryDao().count())
            assertEquals(-1000L, db.batteryDao().lastSample()!!.currentNowUa)
        }
    }
    @Test fun invalidFinalRecordCannotLeaveAPartialImport() = runBlocking {
        database().useDatabase { db ->
            val manager = ExportImportManager(context, db, HistoryMaintenance())
            try {
                manager.importPayload(BatteryExport(listOf(sample(), sample(1700).copy(voltageMv = 4_000_000))))
                fail("Invalid voltage must fail")
            } catch (_: IllegalArgumentException) { }
            assertEquals(0, db.batteryDao().count())
        }
    }
    @Test fun exportRangeSelectsOverlappingSessionsWithoutRewritingTheirTotals() = runBlocking {
        database().useDatabase { db ->
            val manager = ExportImportManager(context, db, HistoryMaintenance())
            db.sessionDao().insert(session().copy(endTime = 2000, activeKey = null))
            db.sessionDao().insert(session().copy(sessionId = "outside", startTime = 3000, endTime = 4000, lastSampleTime = 4000, activeKey = null))
            db.batteryDao().insertSample(sample()); db.batteryDao().insertSample(sample(1900))
            val payload = manager.snapshot(1400, 1600, true, true)
            assertEquals(1, payload.samples.size); assertEquals(1, payload.sessions.size)
            assertEquals(1000L, payload.sessions.single().startTime)
            assertEquals(2000L, payload.sessions.single().endTime)
            assertEquals(1000L, payload.sessions.single().deltaUah)
            assertEquals(1400L, payload.fromEpochMs); assertEquals(1600L, payload.toEpochMs)
            assertTrue(payload.units["chargeCounterUah"]!!.contains("µAh"))
        }
    }
    @Test fun jsonFileRoundTripRetainsNullableValuesAndDeduplicates() = runBlocking {
        val file = File.createTempFile("history-test", ".json", context.cacheDir)
        try {
            database().useDatabase { source ->
                val point = sample().copy(sessionId = null, chargeCounterUah = null, temperatureDeciC = 0)
                source.batteryDao().insertSample(point)
                ExportImportManager(context, source, HistoryMaintenance()).exportJson(Uri.fromFile(file), 0, 3000, true, false)
                database().useDatabase { dest ->
                    val manager = ExportImportManager(context, dest, HistoryMaintenance())
                    assertEquals(1, manager.importJson(Uri.fromFile(file)).samplesAdded)
                    assertEquals(1, manager.importJson(Uri.fromFile(file)).unchanged)
                    assertNull(dest.batteryDao().lastSample()!!.chargeCounterUah)
                    assertEquals(0, dest.batteryDao().lastSample()!!.temperatureDeciC)
                }
            }
        } finally { file.delete() }
    }
    @Test fun localChartsDoNotMixImportedOrLegacyDeviceReadings() = runBlocking {
        database().useDatabase { db ->
            db.batteryDao().insertSample(sample())
            db.batteryDao().insertSample(sample(1600).copy(source = "import:BatteryManager", observationId = "import:foreign"))
            db.batteryDao().insertSample(sample(1700).copy(source = "legacy", observationId = null))
            val chart = db.batteryDao().chartSamples(0, 3000, 1).first()
            assertEquals(listOf(1500L), chart.map { it.timestamp })
            assertEquals(3, db.batteryDao().samplesBetween(0, 3000).first().size)
        }
    }
    @Test fun legacyCsvIsAcceptedAndMalformedLaterRowsLeaveNoHistory() = runBlocking {
        val file = File.createTempFile("history-test", ".csv", context.cacheDir)
        val header = "timestamp,levelPercent,status,plugged,currentNowUa,chargeCounterUah,voltageMv,temperatureDeciC,health,screenOn\n"
        val row = "1000,0,3,0,0,,4000,0,2,true\n"
        try {
            database().useDatabase { db ->
                val manager = ExportImportManager(context, db, HistoryMaintenance())
                file.writeText(header + row + "malformed\n")
                try { manager.importCsv(Uri.fromFile(file)); fail("Whole file must fail") } catch (_: IllegalArgumentException) { }
                assertEquals(0, db.batteryDao().count())
                file.writeText(header + row)
                assertEquals(1, manager.importCsv(Uri.fromFile(file)).samplesAdded)
                assertEquals(1, manager.importCsv(Uri.fromFile(file)).unchanged)
                assertNull(db.batteryDao().lastSample()!!.chargeCounterUah)
            }
        } finally { file.delete() }
    }
}
