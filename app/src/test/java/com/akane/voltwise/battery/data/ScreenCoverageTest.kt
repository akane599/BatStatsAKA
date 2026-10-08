package com.akane.voltwise.battery.data

import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.measurement.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset

class ScreenCoverageTest {
    private fun session() = ChargeSession("coverage", SessionType.DISCHARGE, 0, 240_000,
        90, 89, 20_000, -600_000, null, observedMs = 240_000, counterCoveredMs = 120_000,
        screenOnMs = 120_000, screenOffMs = 120_000, screenOnUah = 20_000,
        screenOnCoveredMs = 120_000, screenOffCoveredMs = 0)

    @Test fun partialBucketUsesOnlyCoveredTimeAndRequiresAMinute() {
        val row = session().copy(screenOnMs = 180_000, screenOffMs = 60_000, screenOnCoveredMs = 60_000)
        assertEquals(1200.0, SessionDrain.of(row, null).screenOn.currentMa!!, 0.0)
        assertNull(SessionDrain.of(row.copy(screenOnCoveredMs = 59_999), null).screenOn.currentMa)
        assertEquals(0.0, SessionDrain.of(row.copy(screenOnUah = 0), null).screenOn.currentMa!!, 0.0)
    }

    @Test fun dailyMissingCounterCoverageIsZeroNotMeasuredZero() {
        fun daily(charge: Long?) = ObservationEngine().let { engine ->
            val first = Observation(0, 0, 0, 90, charge, null, 4000,
                PowerState.DISCHARGING, true, false, "run")
            val before = engine.accept(first)
            val after = engine.accept(first.copy(wallMs = 120_000, elapsedMs = 120_000, uptimeMs = 120_000))
            val interval = DailySummaryAggregator.interval(before, after, null)!!
            DailySummaryAggregator.apply(emptyMap(), interval, ZoneOffset.UTC, 120_000).single()
        }
        val missing = daily(null)
        assertEquals(120_000L, missing.screenOnMs)
        assertEquals(0L, missing.screenOnDischargeUah)
        assertEquals(0L, missing.screenOnCoveredMs)
        val measuredZero = daily(4_000_000)
        assertEquals(0L, measuredZero.screenOnDischargeUah)
        assertEquals(120_000L, measuredZero.screenOnCoveredMs)
    }

    @Test fun exportRoundTripKeepsCoverageAndFormatThreeLeavesItUnknown() {
        val json = Json { encodeDefaults = true }
        val payload = BatteryExport(sessions = listOf(session()), formatVersion = HISTORY_FORMAT_VERSION)
        val encoded = json.encodeToString(BatteryExport.serializer(), payload)
        assertTrue(encoded.contains("\"screenOnCoveredMs\":120000"))
        val restored = json.decodeFromString(BatteryExport.serializer(), encoded)
        assertEquals(session(), restored.sessions.single())
        assertEquals(120_000L, HistoryPolicy.session(restored.sessions.single()).screenOnCoveredMs)
        for (format in 1..3) {
            val old = encoded.replace("\"formatVersion\":$HISTORY_FORMAT_VERSION", "\"formatVersion\":$format")
                .replace(",\"screenOnCoveredMs\":120000", "").replace(",\"screenOffCoveredMs\":0", "")
            val legacy = HistoryPolicy.session(json.decodeFromString(BatteryExport.serializer(), old).sessions.single())
            assertNull(legacy.screenOnCoveredMs)
            assertNull(legacy.screenOffCoveredMs)
            assertNull(SessionDrain.of(legacy, null).screenOn.currentMa)
        }
    }

    @Test fun importClampsCoverageAndRejectsNegativeValues() {
        val row = HistoryPolicy.session(session().copy(screenOnCoveredMs = Long.MAX_VALUE, screenOffCoveredMs = 130_000))
        assertEquals(120_000L, row.screenOnCoveredMs)
        assertEquals(120_000L, row.screenOffCoveredMs)
        for (invalid in listOf(session().copy(screenOnCoveredMs = -1), session().copy(screenOffCoveredMs = -1))) {
            assertThrows(IllegalArgumentException::class.java) { HistoryPolicy.session(invalid) }
        }
    }

    @Test fun clockCorrectionScalesChargeWithCoveredTimeWithoutDilutingRate() {
        val row = session().copy(endTime = 239_000, screenOnCoveredMs = 60_000)
        val normalized = HistoryPolicy.session(row)
        assertEquals(59_750L, normalized.screenOnCoveredMs)
        assertEquals(19_916L, normalized.screenOnUah)
        assertEquals(0L, normalized.screenOffCoveredMs)
    }
}
