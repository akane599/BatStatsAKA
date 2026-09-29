package app.batstats.viewmodel

import app.batstats.battery.apps.AppUsageBasis
import app.batstats.battery.apps.AppUsageStatus
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
import org.junit.Assert.assertEquals
import org.junit.Test

/** [inAppHistory]: AppDetails' history bars are all "from unplug to plug-in", as its ⓘ says. */
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
}
