package app.batstats.battery.data

import app.batstats.battery.data.db.*
import org.junit.Assert.*
import org.junit.Test

class HistoryPolicyTest {
    private fun sample() = BatterySample(timestamp = 1000, levelPercent = 0, status = 3, plugged = 0,
        currentNowUa = 0, chargeCounterUah = 0, voltageMv = 4000, temperatureDeciC = 0,
        health = 2, screenOn = true, elapsedMs = 100, uptimeMs = 80, observationId = "observation", sessionId = "session", source = "BatteryManager")
    private fun session() = ChargeSession("session", SessionType.DISCHARGE, 1000, null, 60, 59, 1000, -1000, null,
        observationId = "observation", lastSampleTime = 2000, observedMs = 1000, counterCoveredMs = 1000, screenOnMs = 1000, screenOnUah = 1000)

    @Test fun sampleIdentitySurvivesRepeatedImportsAndLocalRowIds() {
        val original = sample()
        val imported = HistoryPolicy.sample(original)
        assertTrue(imported.id < -1)
        assertEquals(imported, HistoryPolicy.sample(imported))
        assertTrue(HistoryPolicy.sameSample(original.copy(id = 123), imported))
        assertNotEquals(imported.id, HistoryPolicy.sample(original.copy(currentNowUa = 1)).id)
        assertEquals(0L, imported.currentNowUa); assertEquals(0L, imported.chargeCounterUah)
        assertEquals(0, imported.levelPercent); assertEquals(0, imported.temperatureDeciC)
    }
    @Test fun knownUnavailableLegacySentinelsStayMissing() {
        val imported = HistoryPolicy.sample(sample().copy(currentNowUa = Long.MIN_VALUE,
            chargeCounterUah = Int.MIN_VALUE.toLong(), voltageMv = 0, temperatureDeciC = Int.MIN_VALUE))
        assertNull(imported.currentNowUa); assertNull(imported.chargeCounterUah)
        assertNull(imported.voltageMv); assertNull(imported.temperatureDeciC)
    }
    @Test fun invalidUnitsTimesAndTextAreRejected() {
        for (bad in listOf(sample().copy(levelPercent = 101), sample().copy(currentNowUa = 1_000_000_000),
            sample().copy(voltageMv = 4_000_000), sample().copy(uptimeMs = 101), sample().copy(timestamp = -1),
            sample().copy(etaBasis = "=1+1"), sample().copy(observationId = "line\nbreak"))) {
            assertThrows(IllegalArgumentException::class.java) { HistoryPolicy.sample(bad) }
        }
    }
    @Test fun activeSnapshotsCloseAtLastEvidenceAndNeverResume() {
        val imported = HistoryPolicy.session(session())
        assertEquals(2000L, imported.endTime); assertNull(imported.activeKey)
        assertEquals("import:session", imported.sessionId)
        assertEquals(imported, HistoryPolicy.session(imported))
        assertEquals(1000L, HistoryPolicy.session(session().copy(lastSampleTime = null, observedMs = 0,
            counterCoveredMs = 0, deltaUah = null, screenOnMs = 0, screenOnUah = null)).endTime)
    }
    @Test fun fictionalScreenOffAndIncompatibleCoverageAreRejected() {
        for (bad in listOf(session().copy(screenOffUah = 10), session().copy(screenOffMs = 1),
            session().copy(counterCoveredMs = 1001), session().copy(deltaUah = 999),
            session().copy(deltaUah = -1), session().copy(cpuSuspendMs = 1001), session().copy(endTime = 999))) {
            assertThrows(IllegalArgumentException::class.java) { HistoryPolicy.session(bad) }
        }
    }
}
