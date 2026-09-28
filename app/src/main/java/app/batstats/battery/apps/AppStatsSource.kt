package app.batstats.battery.apps

import app.batstats.battery.util.BatteryStatsParser

/** On-demand `dumpsys batterystats -c --charged`; concurrent callers share one dump. */
interface AppStatsSource {
    /** Cached for 60 s unless [force]; parsing happens off the main thread. */
    suspend fun snapshot(force: Boolean = false): AppStatsResult
}

sealed interface AppStatsResult {
    data class Ready(val snapshot: BatteryStatsParser.FullSnapshot) : AppStatsResult
    /** No root/Shizuku/ADB access; the UI explains how to grant it. */
    data object NoAccess : AppStatsResult
    data class Failed(val message: String) : AppStatsResult
}
