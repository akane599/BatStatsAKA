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

/**
 * Who a batterystats row's power belongs to. Power is accounted per UID, so when several packages share the UID
 * the power is theirs together: [Shared] says so instead of naming one member.
 */
sealed interface UidIdentity {
    /**
     * The package name lookups and stored rows use: the app's, a shared UID's representative, or A2's display name
     * for a UID with no package ("System UID 1000", "UID 10123").
     */
    val packageName: String

    data class App(override val packageName: String) : UidIdentity

    /** [members] are distinct and sorted, so the representative (the first) doesn't depend on the dump's order. */
    data class Shared(val members: List<String>) : UidIdentity {
        override val packageName: String get() = members.first()
        val memberCount: Int get() = members.size
    }

    data class NoPackage(override val packageName: String) : UidIdentity
}

fun BatteryStatsParser.AppPowerStats.identity(): UidIdentity {
    val members = packages.distinct().sorted()
    return when (members.size) {
        0 -> UidIdentity.NoPackage(packageName)
        1 -> UidIdentity.App(members.single())
        else -> UidIdentity.Shared(members)
    }
}

/** Maps a full checkin parse to the per-app fields the delta/db layers need. */
fun BatteryStatsParser.FullSnapshot.toAppUsageSnapshot(): AppUsageSnapshot = AppUsageSnapshot(
    windowStartedAt = startedAt,
    windowStartCount = startCount,
    capturedAt = capturedAt,
    rows = apps.map { app ->
        AppUsageRow(
            uid = app.uid,
            packageName = app.identity().packageName,
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
