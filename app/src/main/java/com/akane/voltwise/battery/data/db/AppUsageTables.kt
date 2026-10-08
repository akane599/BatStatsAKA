package com.akane.voltwise.battery.data.db

import androidx.room.*
import com.akane.voltwise.battery.apps.AppUsageBasis
import com.akane.voltwise.battery.apps.AppUsageRow
import kotlinx.serialization.Serializable

enum class AppSnapshotKind { BASELINE, END }

/**
 * Header of a stored `batterystats --charged` per-app snapshot: BASELINE after unplug (for the new discharge
 * session), END after plug-in (for the session that just ended). Transient: only the open session's baseline
 * and the last [AppUsageDao.SNAPSHOTS_KEPT] survive. `sessionId` is not a foreign key; retention removes
 * snapshots whose session is gone.
 */
@Entity(tableName = "app_snapshots", indices = [Index("sessionId")])
data class AppSnapshot(
    @field:PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String?,
    val kind: AppSnapshotKind,
    val capturedAt: Long,           // wall clock, ms
    val windowStartedAt: Long?,     // batterystats window start; a different window means the stats were reset
    val windowStartCount: Long?,
)

/** One uid's totals in a snapshot: [AppUsageRow] minus `isOthers` (snapshots hold raw per-uid rows only). */
@Entity(
    tableName = "app_snapshot_uids",
    primaryKeys = ["snapshotId", "uid"],
    foreignKeys = [ForeignKey(entity = AppSnapshot::class, parentColumns = ["id"], childColumns = ["snapshotId"], onDelete = ForeignKey.CASCADE)],
)
data class AppSnapshotUid(
    val snapshotId: Long,
    val uid: Int,
    val packageName: String,
    val powerMah: Double,
    val cpuTimeMs: Long? = null,
    val foregroundTimeMs: Long? = null,
    val backgroundTimeMs: Long? = null,
    val wakelockTimeMs: Long? = null,
    val mobileBytes: Long? = null,
    val wifiBytes: Long? = null,
)

/**
 * A discharge session's per-app breakdown: ranked rows (0 = largest), the top 30 apps plus at most one
 * `isOthers` row. Deleted with its session (FK cascade). Exported and imported with the sessions.
 */
@Serializable
@Entity(
    tableName = "session_app_usage",
    primaryKeys = ["sessionId", "rank"],
    foreignKeys = [ForeignKey(entity = ChargeSession::class, parentColumns = ["sessionId"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class SessionAppUsage(
    val sessionId: String,
    val rank: Int,
    val uid: Int,
    val packageName: String,
    val powerMah: Double,           // mAh attributed by Android batterystats
    val cpuTimeMs: Long? = null,
    val foregroundTimeMs: Long? = null,
    val backgroundTimeMs: Long? = null,
    val wakelockTimeMs: Long? = null,
    val mobileBytes: Long? = null,
    val wifiBytes: Long? = null,
    val isOthers: Boolean = false,
    val basis: AppUsageBasis,
) {
    companion object {
        /** Top 30 apps plus the "others" row. */
        const val MAX_ROWS = 31
    }
}

fun AppUsageRow.toSnapshotUid(snapshotId: Long) = AppSnapshotUid(snapshotId, uid, packageName, powerMah,
    cpuTimeMs, foregroundTimeMs, backgroundTimeMs, wakelockTimeMs, mobileBytes, wifiBytes)

fun AppSnapshotUid.toRow() = AppUsageRow(uid, packageName, powerMah,
    cpuTimeMs, foregroundTimeMs, backgroundTimeMs, wakelockTimeMs, mobileBytes, wifiBytes)

fun AppUsageRow.toSessionUsage(sessionId: String, rank: Int, basis: AppUsageBasis) = SessionAppUsage(sessionId, rank,
    uid, packageName, powerMah, cpuTimeMs, foregroundTimeMs, backgroundTimeMs, wakelockTimeMs, mobileBytes, wifiBytes, isOthers, basis)

fun SessionAppUsage.toRow() = AppUsageRow(uid, packageName, powerMah,
    cpuTimeMs, foregroundTimeMs, backgroundTimeMs, wakelockTimeMs, mobileBytes, wifiBytes, isOthers)
