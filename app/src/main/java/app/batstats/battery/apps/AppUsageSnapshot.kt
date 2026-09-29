package app.batstats.battery.apps

import app.batstats.battery.util.BatteryStatsParser

/** A per-UID snapshot of one `dumpsys batterystats -c --charged` dump, keyed to the stats
 * window it was taken in (`windowStartedAt`/`windowStartCount`) so two snapshots can be told
 * apart from a window reset. A3's `app_snapshots`/`app_snapshot_uids` tables model these fields
 * exactly; do not add or rename fields here without updating that schema. */
data class AppUsageSnapshot(
    val windowStartedAt: Long?,
    val windowStartCount: Long?,
    val capturedAt: Long,
    val rows: List<AppUsageRow>,
)

/** Maps a full checkin parse to the per-app fields the delta/db layers need. */
fun BatteryStatsParser.FullSnapshot.toAppUsageSnapshot(): AppUsageSnapshot = AppUsageSnapshot(
    windowStartedAt = startedAt,
    windowStartCount = startCount,
    capturedAt = capturedAt,
    rows = apps.map { app ->
        AppUsageRow(
            uid = app.uid,
            packageName = app.packages.firstOrNull() ?: app.packageName,
            powerMah = app.powerMah,
            cpuTimeMs = app.cpuTimeMs,
            foregroundTimeMs = app.foregroundTimeMs,
            backgroundTimeMs = app.backgroundTimeMs,
            wakelockTimeMs = app.wakeLockTimeMs,
            mobileBytes = sumBytesOrNull(app.mobileRxBytes, app.mobileTxBytes),
            wifiBytes = sumBytesOrNull(app.wifiRxBytes, app.wifiTxBytes),
        )
    },
)

/** rx + tx, null only when both sides are unknown. */
private fun sumBytesOrNull(rx: Long?, tx: Long?): Long? =
    if (rx == null && tx == null) null else (rx ?: 0L) + (tx ?: 0L)
