package app.batstats.battery.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
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
import java.time.ZoneOffset
import java.util.UUID

/** Seed the actual historical schemas; Room validates the migrated schema on opening. */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun versionOneKeepsHistory() = migrate(1)
    @Test fun versionTwoKeepsHistory() = migrate(2)
    @Test fun versionThreeKeepsHistoryAndRejectsDuplicateActiveSessions() = migrate(3)

    private fun open(name: String) = Room.databaseBuilder(context, BatteryDatabase::class.java, name)
        .addMigrations(BatteryDatabase.MIGRATION_1_2, BatteryDatabase.MIGRATION_2_3, BatteryDatabase.MIGRATION_3_4, BatteryDatabase.MIGRATION_4_5).build()

    private fun BatteryDatabase.names(type: String): Set<String> =
        openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = ?", arrayOf(type)).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun BatteryDatabase.count(table: String): Int =
        openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    private fun assertV5Tables(db: BatteryDatabase) {
        val tables = db.names("table")
        assertFalse("alarm_rules" in tables); assertFalse("app_energy_stats" in tables)
        assertTrue(tables.containsAll(listOf("daily_summaries", "app_snapshots", "app_snapshot_uids", "session_app_usage")))
        assertFalse("index_battery_samples_status" in db.names("index"))
    }

    private fun migrate(version: Int) = runBlocking {
        val name = "migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
            legacy.execSQL("CREATE TABLE battery_samples (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timestamp INTEGER NOT NULL, levelPercent INTEGER NOT NULL, status INTEGER NOT NULL, plugged INTEGER NOT NULL, currentNowUa INTEGER, chargeCounterUah INTEGER, voltageMv INTEGER, temperatureDeciC INTEGER, health INTEGER, screenOn INTEGER NOT NULL)")
            legacy.execSQL("CREATE INDEX index_battery_samples_timestamp ON battery_samples(timestamp)")
            legacy.execSQL("CREATE INDEX index_battery_samples_status ON battery_samples(status)")
            legacy.execSQL("CREATE TABLE charge_sessions (sessionId TEXT PRIMARY KEY NOT NULL, type TEXT NOT NULL, startTime INTEGER NOT NULL, endTime INTEGER, startLevel INTEGER NOT NULL, endLevel INTEGER, deltaUah INTEGER, avgCurrentUa INTEGER, estCapacityMah INTEGER)")
            legacy.execSQL("CREATE INDEX index_charge_sessions_startTime ON charge_sessions(startTime)")
            legacy.execSQL("CREATE INDEX index_charge_sessions_type ON charge_sessions(type)")
            legacy.execSQL("CREATE TABLE alarm_rules (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, type TEXT NOT NULL, enabled INTEGER NOT NULL, threshold INTEGER NOT NULL, notifyOnce INTEGER NOT NULL)")
            if (version >= 2) {
                legacy.execSQL("CREATE TABLE app_energy_stats (bucketStart INTEGER NOT NULL, packageName TEXT NOT NULL, mode TEXT NOT NULL, energyMah REAL NOT NULL, samples INTEGER NOT NULL, PRIMARY KEY(bucketStart,packageName,mode))")
                legacy.execSQL("CREATE INDEX index_app_energy_stats_bucketStart ON app_energy_stats(bucketStart)")
                legacy.execSQL("CREATE INDEX index_app_energy_stats_packageName ON app_energy_stats(packageName)")
                legacy.execSQL("INSERT INTO app_energy_stats VALUES(0,'example.app','HEURISTIC',1.5,1)")
            }
            legacy.execSQL("INSERT INTO battery_samples VALUES(1,1000,0,3,0,0,0,4000,0,2,1)")
            legacy.execSQL("INSERT INTO battery_samples VALUES(2,2000,50,3,0,-9223372036854775808,-9223372036854775808,0,250,2,0)")
            legacy.execSQL("INSERT INTO charge_sessions VALUES('finished','DISCHARGE',1000,2000,51,50,10000,-100000,NULL)")
            legacy.execSQL("INSERT INTO charge_sessions VALUES('interrupted','CHARGE',2000,NULL,50,NULL,NULL,NULL,NULL)")
            legacy.version = version
        }
        val db = open(name)
        try {
            val samples = db.batteryDao().samplesBetween(0, Long.MAX_VALUE).first()
            assertEquals(2, samples.size)
            assertEquals(0L, samples.first().currentNowUa)
            assertEquals(0, samples.first().levelPercent)
            assertNull(samples.last().currentNowUa)
            assertNull(samples.last().chargeCounterUah)
            assertNull(samples.last().voltageMv)
            assertNull(db.sessionDao().active())
            val history = db.sessionDao().filteredSessions(null, "", 10).first()
            assertEquals(2, history.size)
            assertEquals(2000L, history.single { it.sessionId == "interrupted" }.endTime)
            assertEquals("legacy", samples.first().source)
            assertV5Tables(db)
            val active = ChargeSession("new", SessionType.DISCHARGE, 3000, null, 50, null, null, null, null)
            db.sessionDao().upsert(active)
            try {
                db.sessionDao().upsert(active.copy(sessionId = "duplicate"))
                fail("Only one active session may exist")
            } catch (_: SQLiteConstraintException) { }
            assertEquals("new", db.sessionDao().active()?.sessionId)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    private fun sample(time: Long, sessionId: String) = BatterySample(timestamp = time, levelPercent = 70, status = 3, plugged = 0,
        currentNowUa = -300_000, chargeCounterUah = 3_000_000, voltageMv = 3900, temperatureDeciC = 300, health = 2, screenOn = true,
        elapsedMs = time, uptimeMs = time, observationId = "o", sessionId = sessionId, source = "BatteryManager")
    private fun app(uid: Int, mah: Double = 1.0) = AppUsageRow(uid, "app.$uid", mah, cpuTimeMs = 10, mobileBytes = 5)

    @Test fun versionFourKeepsHistoryDropsLegacyTablesAndGainsWorkingV5Tables() = runBlocking {
        val name = "migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { v4 ->
            // schemas/…/4.json createSql, verbatim (scripts/check_migrations.py verifies it).
            v4.execSQL("CREATE TABLE IF NOT EXISTS `battery_samples` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `levelPercent` INTEGER, `status` INTEGER NOT NULL, `plugged` INTEGER, `currentNowUa` INTEGER, `chargeCounterUah` INTEGER, `voltageMv` INTEGER, `temperatureDeciC` INTEGER, `health` INTEGER, `screenOn` INTEGER NOT NULL, `elapsedMs` INTEGER, `uptimeMs` INTEGER, `observationId` TEXT, `sessionId` TEXT, `currentAverageUa` INTEGER, `energyNwh` INTEGER, `cycleCount` INTEGER, `etaMs` INTEGER, `etaBasis` TEXT, `source` TEXT NOT NULL DEFAULT 'legacy', `boundaryReason` TEXT)")
            v4.execSQL("CREATE INDEX IF NOT EXISTS `index_battery_samples_timestamp` ON `battery_samples` (`timestamp`)")
            v4.execSQL("CREATE INDEX IF NOT EXISTS `index_battery_samples_status` ON `battery_samples` (`status`)")
            v4.execSQL("CREATE INDEX IF NOT EXISTS `index_battery_samples_sessionId` ON `battery_samples` (`sessionId`)")
            v4.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_battery_samples_observationId_elapsedMs` ON `battery_samples` (`observationId`, `elapsedMs`)")
            v4.execSQL("CREATE TABLE IF NOT EXISTS `charge_sessions` (`sessionId` TEXT NOT NULL, `type` TEXT NOT NULL, `startTime` INTEGER NOT NULL, `endTime` INTEGER, `startLevel` INTEGER, `endLevel` INTEGER, `deltaUah` INTEGER, `avgCurrentUa` INTEGER, `estCapacityMah` INTEGER, `activeKey` INTEGER, `observationId` TEXT, `lastSampleTime` INTEGER, `observedMs` INTEGER NOT NULL DEFAULT 0, `counterCoveredMs` INTEGER NOT NULL DEFAULT 0, `screenOnMs` INTEGER NOT NULL DEFAULT 0, `screenOffMs` INTEGER NOT NULL DEFAULT 0, `screenOnUah` INTEGER, `screenOffUah` INTEGER, `cpuSuspendMs` INTEGER, `closeReason` TEXT, `source` TEXT NOT NULL DEFAULT 'legacy', PRIMARY KEY(`sessionId`))")
            v4.execSQL("CREATE INDEX IF NOT EXISTS `index_charge_sessions_startTime` ON `charge_sessions` (`startTime`)")
            v4.execSQL("CREATE INDEX IF NOT EXISTS `index_charge_sessions_type` ON `charge_sessions` (`type`)")
            v4.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_charge_sessions_activeKey` ON `charge_sessions` (`activeKey`)")
            v4.execSQL("CREATE TABLE IF NOT EXISTS `alarm_rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `threshold` INTEGER NOT NULL, `notifyOnce` INTEGER NOT NULL)")
            v4.execSQL("CREATE TABLE IF NOT EXISTS `app_energy_stats` (`bucketStart` INTEGER NOT NULL, `packageName` TEXT NOT NULL, `mode` TEXT NOT NULL, `energyMah` REAL NOT NULL, `samples` INTEGER NOT NULL, PRIMARY KEY(`bucketStart`, `packageName`, `mode`))")
            v4.execSQL("CREATE INDEX IF NOT EXISTS `index_app_energy_stats_bucketStart` ON `app_energy_stats` (`bucketStart`)")
            v4.execSQL("CREATE INDEX IF NOT EXISTS `index_app_energy_stats_packageName` ON `app_energy_stats` (`packageName`)")
            v4.execSQL("INSERT INTO battery_samples (id,timestamp,levelPercent,status,plugged,currentNowUa,chargeCounterUah,voltageMv,temperatureDeciC,health,screenOn,elapsedMs,uptimeMs,observationId,sessionId,source) VALUES (1,1000,80,3,0,-250000,3200000,4000,280,2,1,1000,900,'o','closed','BatteryManager')")
            v4.execSQL("INSERT INTO battery_samples (id,timestamp,levelPercent,status,plugged,currentNowUa,chargeCounterUah,voltageMv,temperatureDeciC,health,screenOn,elapsedMs,uptimeMs,observationId,sessionId,source) VALUES (2,2000,79,3,0,-260000,3150000,3990,285,2,0,2000,1800,'o','open','BatteryManager')")
            v4.execSQL("INSERT INTO charge_sessions (sessionId,type,startTime,endTime,startLevel,endLevel,deltaUah,avgCurrentUa,activeKey,observationId,lastSampleTime,observedMs,counterCoveredMs,screenOnMs,screenOffMs,screenOnUah,cpuSuspendMs,closeReason,source) VALUES ('closed','DISCHARGE',1000,1500,80,80,50000,-250000,NULL,'o',1500,500,500,500,0,50000,100,'Power state changed','BatteryManager observed interval')")
            v4.execSQL("INSERT INTO charge_sessions (sessionId,type,startTime,endTime,startLevel,activeKey,observationId,lastSampleTime,observedMs,source) VALUES ('open','DISCHARGE',2000,NULL,79,1,'o',2000,0,'BatteryManager observed interval')")
            v4.execSQL("INSERT INTO alarm_rules (type,enabled,threshold,notifyOnce) VALUES ('TEMP_HIGH',1,45,1)")
            v4.execSQL("INSERT INTO app_energy_stats VALUES (0,'example.app','HEURISTIC',1.5,1)")
            v4.version = 4
        }
        val db = open(name)
        try {
            // History survives; the new session columns start empty.
            val samples = db.batteryDao().samplesBetween(0, Long.MAX_VALUE).first()
            assertEquals(listOf(1000L, 2000L), samples.map { it.timestamp })
            assertEquals(3_150_000L, samples.last().chargeCounterUah)
            val closed = db.sessionDao().byId("closed")!!
            assertEquals(50_000L, closed.deltaUah); assertEquals(100L, closed.cpuSuspendMs); assertEquals(1500L, closed.endTime)
            assertTrue(listOf(closed.chargerType, closed.energyNwh, closed.peakPowerMw, closed.peakTemperatureDeciC, closed.screenOffSuspendMs,
                closed.capacityEstimateMah, closed.capacityConfidence, closed.capacityBasis, closed.appUsageStatus, closed.appUsageBasis).all { it == null })
            assertEquals("open", db.sessionDao().active()?.sessionId)
            assertV5Tables(db)

            // PersistPolicy's write: day rows, session row and sample commit together or not at all.
            val day = DailySummary(epochDay = 20_000, screenOnMs = 1000, screenOnDischargeUah = 50_000, updatedAt = 3000)
            val open = db.sessionDao().active()!!.copy(lastSampleTime = 3000, peakTemperatureDeciC = 310, appUsageStatus = AppUsageStatus.PENDING)
            assertTrue(db.persistDao().persistSample(sample(3000, "open"), open, listOf(day.copy(epochDay = -1), day)) > 0)
            assertEquals(open, db.sessionDao().byId("open"))
            assertEquals(day, db.dailySummaryDao().byDay(20_000))
            assertEquals(-1L, db.persistDao().persistSample(sample(3000, "open"), open.copy(lastSampleTime = 3100), listOf(day.copy(screenOnMs = 2000))))
            assertEquals(2000L, db.dailySummaryDao().byDay(20_000)?.screenOnMs)
            assertEquals(3100L, db.sessionDao().byId("open")?.lastSampleTime)
            try {
                db.persistDao().persistSample(sample(4000, "second"), open.copy(sessionId = "second"), listOf(day.copy(epochDay = 20_001)))
                fail("A second active session must be rejected")
            } catch (_: SQLiteConstraintException) { }
            assertNull(db.dailySummaryDao().byDay(20_001))
            assertEquals(3, db.batteryDao().count())
            assertEquals(listOf(-1L, 20_000L), db.dailySummaryDao().between(-10, 30_000).first().map { it.epochDay })

            // Snapshots: the open session's baseline plus the last 3 survive; pruned uids cascade.
            val dao = db.appUsageDao()
            val baseline = dao.insertSnapshot(AppSnapshot(sessionId = "open", kind = AppSnapshotKind.BASELINE, capturedAt = 2100,
                windowStartedAt = 500, windowStartCount = 7), listOf(app(10_002), app(10_001)))
            val ends = (1..4).map { i ->
                dao.insertSnapshot(AppSnapshot(sessionId = "closed", kind = AppSnapshotKind.END, capturedAt = 2100L + i,
                    windowStartedAt = 500, windowStartCount = 7), listOf(app(10_001, i.toDouble())))
            }
            assertEquals(listOf(ends[3], ends[2], ends[1], baseline), dao.snapshots().map { it.id })
            assertEquals(listOf(app(10_001), app(10_002)), dao.snapshotUids(baseline).map { it.toRow() })
            assertEquals(baseline, dao.latestSnapshot("open", AppSnapshotKind.BASELINE)?.id)
            assertEquals(5, db.count("app_snapshot_uids"))

            // A session's breakdown: ranked, marks the session READY, replaced whole, FK-checked.
            val others = AppUsageRow(-1, "", 0.5, isOthers = true)
            dao.replaceSessionUsage("closed", AppUsageBasis.WINDOW_RESET, listOf(app(10_001, 3.0), app(10_002, 2.0), others))
            dao.replaceSessionUsage("closed", AppUsageBasis.DELTA, listOf(app(10_002, 4.0), app(10_001, 3.0), others))
            val usage = dao.sessionUsage("closed").first()
            assertEquals(listOf(0, 1, 2), usage.map { it.rank })
            assertEquals(listOf(app(10_002, 4.0), app(10_001, 3.0), others), usage.map { it.toRow() })
            assertTrue(usage.all { it.basis == AppUsageBasis.DELTA })
            db.sessionDao().byId("closed")!!.let {
                assertEquals(AppUsageStatus.READY, it.appUsageStatus); assertEquals(AppUsageBasis.DELTA, it.appUsageBasis)
            }
            try {
                dao.replaceSessionUsage("missing", AppUsageBasis.ABSOLUTE, listOf(app(1)))
                fail("App usage needs its session")
            } catch (_: SQLiteConstraintException) { }

            // Retention: the closed session goes, its breakdown cascades, its snapshots become orphans and go,
            // days before the cutoff's day go; the open session's baseline stays.
            HistoryPolicy.purgeExpired(db, 1600, ZoneOffset.UTC)
            assertNull(db.sessionDao().byId("closed"))
            assertEquals(0, db.count("session_app_usage"))
            assertEquals(listOf(baseline), dao.snapshots().map { it.id })
            assertEquals(2, db.count("app_snapshot_uids"))
            assertEquals(listOf(20_000L), db.dailySummaryDao().between(-10, 30_000).first().map { it.epochDay })
            assertEquals(listOf(2000L, 3000L), db.batteryDao().samplesBetween(0, Long.MAX_VALUE).first().map { it.timestamp })
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
