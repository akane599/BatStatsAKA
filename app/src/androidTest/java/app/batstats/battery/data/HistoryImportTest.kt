package app.batstats.battery.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.batstats.battery.apps.AppUsageBasis
import app.batstats.battery.apps.AppUsageRow
import app.batstats.battery.apps.AppUsageStatus
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
    private fun closedSession() = session().copy(endTime = 2000, activeKey = null, energyNwh = 3_900_000, peakTemperatureDeciC = 310,
        screenOffSuspendMs = 0, capacityEstimateMah = 4800, capacityConfidence = "LOW", capacityBasis = "COUNTER_SPAN",
        appUsageStatus = AppUsageStatus.READY, appUsageBasis = AppUsageBasis.DELTA)
    private val appRows = listOf(AppUsageRow(10_123, "com.example.video", 12.5, cpuTimeMs = 60_000, foregroundTimeMs = 900_000, wifiBytes = 4096),
        AppUsageRow(1000, "android", 3.25), AppUsageRow(-1, "", 0.75, isOthers = true))

    @Test fun formatTwoFileWithoutV5FieldsImportsWithThemEmpty() = runBlocking {
        val file = File.createTempFile("history-test", ".json", context.cacheDir)
        try {
            file.writeText("""{"sessions":[{"sessionId":"old","type":"DISCHARGE","startTime":1000,"endTime":2000,"startLevel":60,"endLevel":59,""" +
                """"deltaUah":null,"avgCurrentUa":null,"estCapacityMah":3000,"activeKey":null,"observationId":null,"lastSampleTime":null,""" +
                """"observedMs":0,"counterCoveredMs":0,"screenOnMs":0,"screenOffMs":0,"screenOnUah":null,"screenOffUah":null,""" +
                """"cpuSuspendMs":null,"closeReason":"Power state changed","source":"BatteryManager observed interval"}],"formatVersion":2}""")
            database().useDatabase { db ->
                assertEquals(HistoryImportResult(0, 1, 0, 0), ExportImportManager(context, db, HistoryMaintenance()).importJson(Uri.fromFile(file)))
                val imported = db.sessionDao().byId("import:old")!!
                assertEquals(3000, imported.estCapacityMah)
                assertTrue(listOf(imported.chargerType, imported.energyNwh, imported.peakPowerMw, imported.peakTemperatureDeciC,
                    imported.screenOffSuspendMs, imported.capacityEstimateMah, imported.capacityConfidence, imported.capacityBasis,
                    imported.appUsageStatus, imported.appUsageBasis).all { it == null })
                assertEquals(emptyList<SessionAppUsage>(), db.appUsageDao().sessionUsageRows("import:old"))
            }
        } finally { file.delete() }
    }
    @Test fun appUsageTravelsWithImportedSessionsAndLocalBreakdownsWin() = runBlocking {
        database().useDatabase { source ->
            source.sessionDao().insert(closedSession())
            source.appUsageDao().replaceSessionUsage("session", AppUsageBasis.DELTA, appRows)
            val payload = ExportImportManager(context, source, HistoryMaintenance()).snapshot(0, 3000, false, true)
            assertEquals(HISTORY_FORMAT_VERSION, payload.formatVersion)
            assertEquals(appRows, payload.appUsage.map { it.toRow() })
            database().useDatabase { dest ->
                val manager = ExportImportManager(context, dest, HistoryMaintenance())
                assertEquals(HistoryImportResult(0, 1, 0, 0), manager.importPayload(payload))
                val imported = dest.sessionDao().byId("import:session")!!
                assertEquals(closedSession().copy(sessionId = "import:session", observationId = "import:observation", source = "import:BatteryManager observed interval"), imported)
                assertEquals(appRows, dest.appUsageDao().sessionUsage("import:session").first().map { it.toRow() })
                assertEquals(HistoryImportResult(0, 0, 0, 1), manager.importPayload(payload))
                assertEquals(3, dest.appUsageDao().sessionUsageRows("import:session").size)
                // An export made before the breakdown existed neither erases it nor counts as a change.
                val earlier = payload.copy(sessions = payload.sessions.map { it.copy(appUsageStatus = AppUsageStatus.PENDING, appUsageBasis = null) }, appUsage = emptyList())
                assertEquals(HistoryImportResult(0, 0, 0, 1), manager.importPayload(earlier))
                assertEquals(AppUsageStatus.READY, dest.sessionDao().byId("import:session")?.appUsageStatus)
                assertEquals(3, dest.appUsageDao().sessionUsageRows("import:session").size)
            }
            // Re-importing its own export leaves the device's local session and breakdown alone.
            val foreign = payload.copy(appUsage = payload.appUsage.map { it.copy(powerMah = 99.0) })
            assertEquals(HistoryImportResult(0, 0, 0, 1), ExportImportManager(context, source, HistoryMaintenance()).importPayload(foreign))
            assertEquals(appRows, source.appUsageDao().sessionUsageRows("session").map { it.toRow() })
        }
    }
    @Test fun staleParentKeepsItsNewerBreakdownWhileSameWindowUsageCanUpdateIt() = runBlocking {
        database().useDatabase { db ->
            val manager = ExportImportManager(context, db, HistoryMaintenance())
            val rowsA = appRows.mapIndexed { rank, row -> row.toSessionUsage("session", rank, AppUsageBasis.DELTA) }
            val rowsB = rowsA.map { it.copy(powerMah = 99.0) }
            val newer = BatteryExport(sessions = listOf(closedSession()), appUsage = rowsA)
            val older = newer.copy(sessions = listOf(closedSession().copy(
                endTime = 1500, lastSampleTime = 1500, observedMs = 500, counterCoveredMs = 500,
                screenOnMs = 500, deltaUah = 500, screenOnUah = 500,
            )), appUsage = rowsB)
            assertEquals(HistoryImportResult(0, 1, 0, 0), manager.importPayload(newer))
            val retained = db.sessionDao().byId("import:session")
            val retainedRows = db.appUsageDao().sessionUsageRows("import:session")
            assertEquals(HistoryImportResult(0, 0, 0, 1), manager.importPayload(older))
            assertEquals(retained, db.sessionDao().byId("import:session"))
            assertEquals(retainedRows, db.appUsageDao().sessionUsageRows("import:session"))
            assertEquals(HistoryImportResult(0, 0, 1, 0), manager.importPayload(newer.copy(appUsage = rowsB)))
            assertEquals(HistoryPolicy.appUsage(rowsB), db.appUsageDao().sessionUsageRows("import:session"))
            assertEquals(HistoryImportResult(0, 0, 0, 1), manager.importPayload(newer.copy(appUsage = rowsB)))
        }
    }

    @Test fun appUsageWithoutItsSessionOrWithDuplicateRanksRollsBackTheImport() = runBlocking {
        database().useDatabase { db ->
            val manager = ExportImportManager(context, db, HistoryMaintenance())
            val rows = appRows.mapIndexed { rank, row -> row.toSessionUsage("session", rank, AppUsageBasis.ABSOLUTE) }
            for (bad in listOf(BatteryExport(sessions = listOf(closedSession()), appUsage = rows.map { it.copy(sessionId = "ghost") }),
                BatteryExport(sessions = listOf(closedSession()), appUsage = rows.map { it.copy(rank = 0) }),
                BatteryExport(sessions = listOf(closedSession()), appUsage = rows.map { it.copy(powerMah = Double.NaN) }))) {
                try { manager.importPayload(bad); fail("Invalid app usage must fail") } catch (_: IllegalArgumentException) { }
                assertEquals(0, db.sessionDao().count())
            }
        }
    }
    @Test fun appUsageCsvAttachesToAnImportedSession() = runBlocking {
        val file = File.createTempFile("history-test", ".csv", context.cacheDir)
        try {
            database().useDatabase { db ->
                val manager = ExportImportManager(context, db, HistoryMaintenance())
                manager.importPayload(BatteryExport(sessions = listOf(closedSession())))
                file.writeText("sessionId,rank,uid,packageName,powerMah,cpuTimeMs,foregroundTimeMs,backgroundTimeMs,wakelockTimeMs,mobileBytes,wifiBytes,isOthers,basis,exportedAtEpochMs\n" +
                    "session,0,10123,com.example.video,12.5,60000,900000,,,,4096,false,DELTA,1\n" +
                    "session,1,1000,android,3.25,,,,,,,false,DELTA,1\n" +
                    "session,2,-1,,0.75,,,,,,,true,DELTA,1\n")
                assertEquals(HistoryImportResult(0, 0, 1, 0), manager.importCsv(Uri.fromFile(file)))
                assertEquals(appRows, db.appUsageDao().sessionUsageRows("import:session").map { it.toRow() })
                assertEquals(HistoryImportResult(0, 0, 0, 0), manager.importCsv(Uri.fromFile(file)))
            }
        } finally { file.delete() }
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
