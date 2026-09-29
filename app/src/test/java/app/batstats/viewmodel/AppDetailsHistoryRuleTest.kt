package app.batstats.viewmodel

import app.batstats.battery.apps.AppUsageBasis
import app.batstats.battery.apps.AppUsageStatus
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionAppUsage
import app.batstats.battery.data.db.SessionType
import org.junit.Assert.assertEquals
import org.junit.Test

/** [inAppHistory] and [isSameApp]: AppDetails' history bars are this app's, all "from unplug to plug-in". */
class AppDetailsHistoryRuleTest {
    private fun session(type: SessionType, status: AppUsageStatus?, basis: AppUsageBasis?) = ChargeSession(
        sessionId = "$type-$status-$basis", type = type, startTime = 0, endTime = 1, startLevel = 90, endLevel = 80,
        deltaUah = null, avgCurrentUa = null, estCapacityMah = null, appUsageStatus = status, appUsageBasis = basis,
    )

    @Test fun onlyReadyDischargesMeasuredFromUnplugToPlugInAreKept() {
        val kept = listOf(
            session(SessionType.DISCHARGE, AppUsageStatus.READY, AppUsageBasis.DELTA),
            session(SessionType.DISCHARGE, AppUsageStatus.READY, AppUsageBasis.ABSOLUTE),
            session(SessionType.DISCHARGE, AppUsageStatus.READY, AppUsageBasis.WINDOW_RESET),
            session(SessionType.DISCHARGE, AppUsageStatus.READY, null),
            session(SessionType.DISCHARGE, AppUsageStatus.FAILED, AppUsageBasis.DELTA),
            session(SessionType.DISCHARGE, AppUsageStatus.PENDING, null),
            session(SessionType.CHARGE, AppUsageStatus.READY, AppUsageBasis.DELTA),
        ).filter { it.inAppHistory() }

        assertEquals(listOf("DISCHARGE-READY-DELTA"), kept.map { it.sessionId })
    }

    private fun row(uid: Int, packageName: String, others: Boolean = false) = SessionAppUsage(
        sessionId = "s", rank = 1, uid = uid, packageName = packageName, powerMah = 1.0, isOthers = others,
        basis = AppUsageBasis.DELTA,
    )

    @Test fun anAppUidReusedByAnotherPackageIsNotThisAppsHistory() {
        // Android can hand an uninstalled app's uid to the next install.
        assertEquals(true, row(10_123, "com.example.old").isSameApp(10_123, "com.example.old"))
        assertEquals(false, row(10_123, "com.example.old").isSameApp(10_123, "com.example.new"))
        assertEquals(false, row(10_124, "com.example.old").isSameApp(10_123, "com.example.old"))
    }

    @Test fun systemUidsMatchByUidAloneAndBlankPackagesMatchAny() {
        // Uid 1000 is shared by many system packages and never reassigned.
        assertEquals(true, row(1_000, "android").isSameApp(1_000, "com.android.settings"))
        assertEquals(true, row(10_123, "").isSameApp(10_123, "com.example.app"))
        assertEquals(true, row(10_123, "com.example.app").isSameApp(10_123, ""))
        assertEquals(false, row(10_123, "com.example.app", others = true).isSameApp(10_123, "com.example.app"))
    }
}
