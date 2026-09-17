package app.batstats.battery.data.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@TypeConverters(EnumConverters::class)
@Database(
    entities = [BatterySample::class, ChargeSession::class, AlarmRule::class, AppEnergyStat::class],
    version = 4,
    exportSchema = true
)
abstract class BatteryDatabase : RoomDatabase() {
    abstract fun batteryDao(): BatteryDao
    abstract fun sessionDao(): SessionDao
    abstract fun alarmDao(): AlarmDao
    abstract fun appEnergyDao(): AppEnergyDao

    companion object {
        @Volatile private var INSTANCE: BatteryDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS app_energy_stats (bucketStart INTEGER NOT NULL, packageName TEXT NOT NULL, mode TEXT NOT NULL, energyMah REAL NOT NULL, samples INTEGER NOT NULL, PRIMARY KEY(bucketStart,packageName,mode))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_app_energy_stats_bucketStart ON app_energy_stats(bucketStart)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_app_energy_stats_packageName ON app_energy_stats(packageName)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit // Same schema; retain historical rows.
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE battery_samples_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timestamp INTEGER NOT NULL, levelPercent INTEGER, status INTEGER NOT NULL, plugged INTEGER, currentNowUa INTEGER, chargeCounterUah INTEGER, voltageMv INTEGER, temperatureDeciC INTEGER, health INTEGER, screenOn INTEGER NOT NULL, elapsedMs INTEGER, uptimeMs INTEGER, observationId TEXT, sessionId TEXT, currentAverageUa INTEGER, energyNwh INTEGER, cycleCount INTEGER, etaMs INTEGER, etaBasis TEXT, source TEXT NOT NULL DEFAULT 'legacy', boundaryReason TEXT)")
                db.execSQL("INSERT INTO battery_samples_new (id,timestamp,levelPercent,status,plugged,currentNowUa,chargeCounterUah,voltageMv,temperatureDeciC,health,screenOn) SELECT id,timestamp,CASE WHEN levelPercent BETWEEN 0 AND 100 THEN levelPercent END,status,plugged,CASE WHEN currentNowUa BETWEEN -100000000 AND 100000000 THEN currentNowUa END,CASE WHEN chargeCounterUah BETWEEN 0 AND 200000000 THEN chargeCounterUah END,CASE WHEN voltageMv BETWEEN 1 AND 30000 THEN voltageMv END,CASE WHEN temperatureDeciC BETWEEN -500 AND 1500 THEN temperatureDeciC END,health,screenOn FROM battery_samples")
                db.execSQL("DROP TABLE battery_samples")
                db.execSQL("ALTER TABLE battery_samples_new RENAME TO battery_samples")
                db.execSQL("CREATE INDEX index_battery_samples_timestamp ON battery_samples(timestamp)")
                db.execSQL("CREATE INDEX index_battery_samples_status ON battery_samples(status)")
                db.execSQL("CREATE INDEX index_battery_samples_sessionId ON battery_samples(sessionId)")
                db.execSQL("CREATE UNIQUE INDEX index_battery_samples_observationId_elapsedMs ON battery_samples(observationId,elapsedMs)")
                db.execSQL("CREATE TABLE charge_sessions_new (sessionId TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, startTime INTEGER NOT NULL, endTime INTEGER, startLevel INTEGER, endLevel INTEGER, deltaUah INTEGER, avgCurrentUa INTEGER, estCapacityMah INTEGER, activeKey INTEGER, observationId TEXT, lastSampleTime INTEGER, observedMs INTEGER NOT NULL DEFAULT 0, counterCoveredMs INTEGER NOT NULL DEFAULT 0, screenOnMs INTEGER NOT NULL DEFAULT 0, screenOffMs INTEGER NOT NULL DEFAULT 0, screenOnUah INTEGER, screenOffUah INTEGER, cpuSuspendMs INTEGER, closeReason TEXT, source TEXT NOT NULL DEFAULT 'legacy')")
                db.execSQL("INSERT INTO charge_sessions_new (sessionId,type,startTime,endTime,startLevel,endLevel,deltaUah,avgCurrentUa,estCapacityMah,closeReason) SELECT sessionId,type,startTime,COALESCE(endTime,startTime),startLevel,endLevel,deltaUah,avgCurrentUa,estCapacityMah,'Legacy record; continuity not recorded' FROM charge_sessions")
                db.execSQL("DROP TABLE charge_sessions")
                db.execSQL("ALTER TABLE charge_sessions_new RENAME TO charge_sessions")
                db.execSQL("CREATE INDEX index_charge_sessions_startTime ON charge_sessions(startTime)")
                db.execSQL("CREATE INDEX index_charge_sessions_type ON charge_sessions(type)")
                db.execSQL("CREATE UNIQUE INDEX index_charge_sessions_activeKey ON charge_sessions(activeKey)")
            }
        }

        fun get(context: Context): BatteryDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BatteryDatabase::class.java,
                    "battery.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build().also { INSTANCE = it }
            }
    }
}

class EnumConverters {
    @TypeConverter fun fromSessionType(t: SessionType?): String? = t?.name
    @TypeConverter fun toSessionType(s: String?): SessionType? = s?.let { enumValueOf<SessionType>(it) }

    @TypeConverter fun fromAlarmType(t: AlarmType?): String? = t?.name
    @TypeConverter fun toAlarmType(s: String?): AlarmType? = s?.let { enumValueOf<AlarmType>(it) }
}
