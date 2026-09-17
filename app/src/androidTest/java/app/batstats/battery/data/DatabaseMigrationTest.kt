package app.batstats.battery.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.batstats.battery.data.db.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Seed the actual historical schemas; Room validates the migrated schema on opening. */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @Test fun versionOneKeepsHistory() = migrate(1)
    @Test fun versionTwoKeepsHistory() = migrate(2)
    @Test fun versionThreeKeepsHistoryAndRejectsDuplicateActiveSessions() = migrate(3)

    private fun migrate(version: Int) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
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
        val db = Room.databaseBuilder(context, BatteryDatabase::class.java, name)
            .addMigrations(BatteryDatabase.MIGRATION_1_2, BatteryDatabase.MIGRATION_2_3, BatteryDatabase.MIGRATION_3_4).build()
        try {
            val samples = db.batteryDao().samplesBetween(0, Long.MAX_VALUE).first()
            assertEquals(2, samples.size)
            assertEquals(0L, samples.first().currentNowUa)
            assertEquals(0, samples.first().levelPercent)
            assertNull(samples.last().currentNowUa)
            assertNull(samples.last().chargeCounterUah)
            assertNull(samples.last().voltageMv)
            assertNull(db.sessionDao().active())
            val history = db.sessionDao().sessionsPaged(10, 0).first()
            assertEquals(2, history.size)
            assertEquals(2000L, history.single { it.sessionId == "interrupted" }.endTime)
            assertEquals("legacy", samples.first().source)
            val active = ChargeSession("new", SessionType.DISCHARGE, 3000, null, 50, null, null, null, null)
            db.sessionDao().upsert(active)
            try {
                db.sessionDao().upsert(active.copy(sessionId = "duplicate"))
                fail("Only one active session may exist")
            } catch (_: SQLiteConstraintException) { }
            assertEquals("new", db.sessionDao().active()?.sessionId)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
