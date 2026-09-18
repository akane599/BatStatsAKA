package app.batstats.battery.data.db

import androidx.room.*
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "battery_samples",
    indices = [Index("timestamp"), Index("status"), Index("sessionId"), Index(value = ["observationId", "elapsedMs"], unique = true)]
)
data class BatterySample(
    @field:PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val levelPercent: Int?,                  // 0..100
    val status: Int,                        // BatteryManager status
    val plugged: Int?,                       // BatteryManager EXTRA_PLUGGED
    val currentNowUa: Long?,                // microAmps (negative while discharging)
    val chargeCounterUah: Long?,            // microAh
    val voltageMv: Int?,                    // mV
    val temperatureDeciC: Int?,             // tenths of °C
    val health: Int?,                       // BatteryManager EXTRA_HEALTH
    val screenOn: Boolean,
    val elapsedMs: Long? = null,
    val uptimeMs: Long? = null,
    val observationId: String? = null,
    val sessionId: String? = null,
    val currentAverageUa: Long? = null,
    val energyNwh: Long? = null,
    val cycleCount: Int? = null,
    val etaMs: Long? = null,
    val etaBasis: String? = null,
    @ColumnInfo(defaultValue = "'legacy'") val source: String = "legacy",
    val boundaryReason: String? = null
)

@Serializable
@Entity(
    tableName = "charge_sessions",
    indices = [Index("startTime"), Index("type"), Index(value = ["activeKey"], unique = true)]
)
data class ChargeSession(
    @Contextual
    @field:PrimaryKey val sessionId: String,
    val type: SessionType,
    val startTime: Long,
    val endTime: Long?,             // null while active
    val startLevel: Int?,
    val endLevel: Int?,
    val deltaUah: Long?,            // integrated charge delta
    val avgCurrentUa: Long?,        // session average
    val estCapacityMah: Int?,       // legacy session estimate; new sessions do not guess capacity
    val activeKey: Int? = if (endTime == null) 1 else null,
    val observationId: String? = null,
    val lastSampleTime: Long? = null,
    @ColumnInfo(defaultValue = "0") val observedMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val counterCoveredMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val screenOnMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val screenOffMs: Long = 0,
    val screenOnUah: Long? = null,
    val screenOffUah: Long? = null,
    val cpuSuspendMs: Long? = null,
    val closeReason: String? = null,
    @ColumnInfo(defaultValue = "'legacy'") val source: String = "legacy"
)

enum class SessionType { CHARGE, DISCHARGE, PLUGGED, UNKNOWN }

@Entity(tableName = "alarm_rules")
@Serializable
data class AlarmRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: AlarmType,
    val enabled: Boolean,
    val threshold: Int,    // percent for CHARGE_LIMIT; °C for TEMP; mA for DISCHARGE
    val notifyOnce: Boolean = true
)

enum class AlarmType { CHARGE_LIMIT, TEMP_HIGH, DISCHARGE_HIGH }

/**
 * Aggregated per‑app energy estimates (heuristic mode).
 * Stores hour buckets to keep data light...
 */
@Entity(
    tableName = "app_energy_stats",
    primaryKeys = ["bucketStart", "packageName", "mode"],
    indices = [Index("bucketStart"), Index("packageName")]
)
data class AppEnergyStat(
    val bucketStart: Long,          // start of the hour (ms)
    val packageName: String,
    val mode: String = "HEURISTIC", // HEURISTIC / SHIZUKU / ROOT (future)
    val energyMah: Double,          // accumulated mAh in this bucket
    val samples: Int                // number of samples contributed
)

data class AppDrainAggregate(
    val packageName: String,
    val energyMah: Double,
    val samples: Int
)

/** Bounded representative chart rows; bucket discontinuities must remain visible. */
data class SessionChartReading(
    val timestamp: Long,
    val currentNowUa: Long?,
    val voltageMv: Int?,
    val temperatureDeciC: Int?,
    val observationId: String?,
    val source: String,
    val discontinuity: Boolean
)
