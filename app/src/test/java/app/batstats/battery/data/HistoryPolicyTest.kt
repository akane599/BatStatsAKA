package app.batstats.battery.data

import app.batstats.battery.apps.AppUsageBasis
import app.batstats.battery.apps.AppUsageStatus
import app.batstats.battery.data.db.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

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
    @Test fun v5SessionFieldsAreValidatedAndAPendingBreakdownIsNotImported() {
        val full = session().copy(chargerType = "USB", energyNwh = 3_900_000, peakPowerMw = 4500, peakTemperatureDeciC = 310,
            screenOffSuspendMs = 0, capacityEstimateMah = 4800, capacityConfidence = "HIGH", capacityBasis = "COUNTER_SPAN",
            appUsageStatus = AppUsageStatus.READY, appUsageBasis = AppUsageBasis.DELTA)
        val imported = HistoryPolicy.session(full)
        assertEquals(full.copy(sessionId = "import:session", observationId = "import:observation", endTime = 2000, activeKey = null,
            source = "import:legacy", closeReason = "Imported snapshot; monitoring was not resumed"), imported)
        assertEquals(imported, HistoryPolicy.session(imported))
        assertNull(HistoryPolicy.session(full.copy(appUsageStatus = AppUsageStatus.PENDING)).appUsageStatus)
        for (bad in listOf(full.copy(energyNwh = -1), full.copy(peakPowerMw = -1), full.copy(peakPowerMw = 2_000_000),
            full.copy(peakTemperatureDeciC = 2000), full.copy(screenOffSuspendMs = 1001), full.copy(capacityEstimateMah = 0),
            full.copy(chargerType = "=HYPERLINK()"), full.copy(capacityBasis = "@x"))) {
            assertThrows(IllegalArgumentException::class.java) { HistoryPolicy.session(bad) }
        }
    }
    @Test fun afterCloseValuesAreNotPartOfTheMeasurementAndAreKeptWhenAFileLacksThem() {
        val ready = session().copy(appUsageStatus = AppUsageStatus.READY, appUsageBasis = AppUsageBasis.WINDOW_RESET,
            capacityEstimateMah = 4800, capacityConfidence = "LOW", capacityBasis = "COUNTER_SPAN", closeReason = "Power state changed")
        val bare = session()
        assertTrue(HistoryPolicy.sameMeasurement(ready, bare))
        assertFalse(HistoryPolicy.sameMeasurement(ready, bare.copy(deltaUah = 999)))
        assertEquals(ready.copy(closeReason = null), HistoryPolicy.mergeDerived(ready, bare))
        assertEquals(bare.copy(appUsageStatus = AppUsageStatus.FAILED), HistoryPolicy.mergeDerived(ready, bare.copy(appUsageStatus = AppUsageStatus.FAILED))
            .copy(capacityEstimateMah = null, capacityConfidence = null, capacityBasis = null))
    }
    @Test fun appUsageRowsAreWholeRankedBreakdowns() {
        fun row(rank: Int, others: Boolean = false) = SessionAppUsage("session", rank, 10_000 + rank, "app.$rank", 1.5, cpuTimeMs = 10,
            isOthers = others, basis = AppUsageBasis.DELTA)
        val valid = (0 until SessionAppUsage.MAX_ROWS).map { row(it, others = it == SessionAppUsage.MAX_ROWS - 1) } + row(0).copy(sessionId = "other")
        val imported = HistoryPolicy.appUsage(valid)
        assertEquals(setOf("import:session", "import:other"), imported.map { it.sessionId }.toSet())
        assertEquals(imported, HistoryPolicy.appUsage(imported))
        for (bad in listOf(listOf(row(SessionAppUsage.MAX_ROWS)), listOf(row(-1)), listOf(row(0), row(0)), listOf(row(0, true), row(1, true)),
            listOf(row(0).copy(powerMah = -0.1)), listOf(row(0).copy(powerMah = Double.POSITIVE_INFINITY)), listOf(row(0).copy(wifiBytes = -1)),
            listOf(row(0).copy(sessionId = " ")), listOf(row(0).copy(packageName = "=cmd")))) {
            assertThrows(IllegalArgumentException::class.java) { HistoryPolicy.appUsage(bad) }
        }
    }
    @Test fun retentionKeepsTheCutoffsWholeLocalDay() {
        assertEquals(0L, HistoryPolicy.retentionCutoffDay(0, ZoneOffset.UTC))
        assertEquals(-1L, HistoryPolicy.retentionCutoffDay(0, ZoneId.of("America/Los_Angeles")))
        // 04:30 local on the spring-forward day.
        assertEquals(LocalDate.of(2024, 3, 10).toEpochDay(),
            HistoryPolicy.retentionCutoffDay(Instant.parse("2024-03-10T08:30:00Z").toEpochMilli(), ZoneId.of("America/New_York")))
    }
}
