package com.akane.voltwise.viewmodel

import com.akane.voltwise.battery.data.BatteryRepository
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.DailySummary
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.measurement.HealthSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryCapacityTest {
    private class FakeHistoryRepository : HistoryRepository {
        override val realtime = flowOf(BatteryRepository.Realtime())
        override val isMonitoring = flowOf(false)
        override val recordingObservation = flowOf<String?>(null)
        val rows = MutableStateFlow(emptyList<ChargeSession>())
        var query: Pair<SessionType?, Int>? = null
        override fun days(fromDay: Long, toDay: Long): Flow<List<DailySummary>> = flowOf(emptyList())
        override fun sessions(type: SessionType?, limit: Int): Flow<List<ChargeSession>> {
            query = type to limit
            return rows
        }
    }

    private fun session(type: SessionType, capacityMah: Int, confidence: String) = ChargeSession(
        sessionId = type.name, type = type, startTime = 0, endTime = 1_000,
        startLevel = 90, endLevel = 70, deltaUah = null, avgCurrentUa = null, estCapacityMah = null,
        capacityEstimateMah = capacityMah, capacityConfidence = confidence,
    )

    @Test fun storedCapacityUsesHealthWindowAcrossAllSessionTypesAndUpdates() = runTest {
        val repo = FakeHistoryRepository()
        val capacity = repo.storedEstimateUah
        assertEquals(null to HealthSummary.SESSIONS, repo.query)
        assertNull(capacity.first())
        repo.rows.value = listOf(
            session(SessionType.CHARGE, 5_000, "HIGH"),
            session(SessionType.DISCHARGE, 3_000, "LOW"),
            session(SessionType.UNKNOWN, 9_000, "unknown"),
        )
        assertEquals(5_000_000L, capacity.first())
        repo.rows.value = emptyList()
        assertNull(capacity.first())
    }
}
