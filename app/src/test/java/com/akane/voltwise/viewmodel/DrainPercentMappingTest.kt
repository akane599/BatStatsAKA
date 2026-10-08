package com.akane.voltwise.viewmodel

import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.DailySummary
import com.akane.voltwise.battery.data.db.SessionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Now › Today and the SessionDetails header: % of battery and %/h over the resolved capacity, mAh/mA otherwise. */
class DrainPercentMappingTest {
    private val day = DailySummary(
        epochDay = 20_000,
        screenOnMs = 2 * HOUR,
        screenOffMs = 6 * HOUR,
        screenOnDischargeUah = 800_000,
        screenOffDischargeUah = 400_000,
        chargedUah = 2_000_000,
        screenOnCoveredMs = 2 * HOUR,
        screenOffCoveredMs = 5 * HOUR,
    )

    @Test fun todayWithAKnownCapacityIsAShareOfTheBattery() {
        val today = NowMapping.today(day, fullUah = 4_000_000)
        assertEquals(30.0, today.usedPercent!!, 1e-9)
        assertEquals(50.0, today.chargedPercent!!, 1e-9)
        // The mAh stay alongside for the supporting line.
        assertEquals(1_200.0, today.usedMah!!, 1e-9)
        assertEquals(2_000.0, today.chargedMah!!, 1e-9)
        assertEquals(2 * HOUR, today.screenOnMs)
    }

    @Test fun todayWithoutACapacityFallsBackToMah() {
        listOf(null, 0L).forEach { fullUah ->
            val today = NowMapping.today(day, fullUah)
            assertEquals(1_200.0, today.usedMah!!, 1e-9)
            assertEquals(2_000.0, today.chargedMah!!, 1e-9)
            assertNull(today.usedPercent)
            assertNull(today.chargedPercent)
        }
    }

    @Test fun todayWithTimeOnBatteryButNoMeasuredChargeIsUnavailableNotZero() {
        val unmeasured = day.copy(
            screenOnDischargeUah = 0, screenOffDischargeUah = 0, chargedUah = 0, screenOnCoveredMs = 0, screenOffCoveredMs = 0,
        )
        val today = NowMapping.today(unmeasured, fullUah = 4_000_000)
        assertNull(today.usedMah)
        assertNull(today.usedPercent)
        // No counter evidence at all today: a zero charge is just as unknown.
        assertNull(today.chargedMah)
        assertNull(today.chargedPercent)
        assertEquals(2 * HOUR, today.screenOnMs)

        // A legacy row (null coverage) with a zero charge after time on battery is the same missing data.
        val legacy = NowMapping.today(unmeasured.copy(screenOnCoveredMs = null, screenOffCoveredMs = null), fullUah = null)
        assertNull(legacy.usedMah)

        // But a charge reported without discharge evidence still stands.
        val charged = NowMapping.today(unmeasured.copy(chargedUah = 1_000_000), fullUah = 4_000_000)
        assertNull(charged.usedMah)
        assertEquals(25.0, charged.chargedPercent!!, 1e-9)
    }

    @Test fun todayMeasuredZeroAndNoTimeOnBatteryStayZero() {
        // Covered time with no charge drawn is a measured zero.
        val measuredZero = NowMapping.today(day.copy(screenOnDischargeUah = 0, screenOffDischargeUah = 0), fullUah = 4_000_000)
        assertEquals(0.0, measuredZero.usedPercent!!, 1e-9)
        // Plugged in all day: nothing used, and the charge is what the counter added.
        val plugged = NowMapping.today(DailySummary(epochDay = 20_000, chargedUah = 1_000_000, screenOnCoveredMs = 0, screenOffCoveredMs = 0), null)
        assertEquals(0.0, plugged.usedMah!!, 1e-9)
        assertEquals(1_000.0, plugged.chargedMah!!, 1e-9)
        // A legacy row with charge keeps its stored figure.
        val legacy = NowMapping.today(day.copy(screenOnCoveredMs = null, screenOffCoveredMs = null), fullUah = null)
        assertEquals(1_200.0, legacy.usedMah!!, 1e-9)
    }

    @Test fun sessionHeaderAddsPerHourAndShareOfBatteryOverTheResolvedCapacity() {
        val summary = SessionDetailsMapping.summary(discharge, recording = false, readings = emptyList(), fullUah = 4_000_000)
        assertEquals(400.0, summary.averageMa!!, 1e-9)
        // SessionDrain's conversion: mA × 100 000 ÷ full µAh.
        assertEquals(10.0, summary.percentPerHour!!, 1e-9)
        assertEquals(1_200.0, summary.chargeMah!!, 1e-9)
        assertEquals(30.0, summary.chargePercent!!, 1e-9)

        val charging = SessionDetailsMapping.summary(
            discharge.copy(type = SessionType.CHARGE, deltaUah = 2_000_000, avgCurrentUa = 2_000_000),
            recording = false,
            readings = emptyList(),
            fullUah = 4_000_000,
        )
        assertEquals(50.0, charging.percentPerHour!!, 1e-9)
        assertEquals(50.0, charging.chargePercent!!, 1e-9)
    }

    @Test fun sessionHeaderWithoutCapacityOrAverageKeepsMilliamps() {
        listOf(null, 0L).forEach { fullUah ->
            val summary = SessionDetailsMapping.summary(discharge, recording = false, readings = emptyList(), fullUah = fullUah)
            assertEquals(400.0, summary.averageMa!!, 1e-9)
            assertNull(summary.percentPerHour)
            assertNull(summary.chargePercent)
        }
        // Under a minute of counter data: no average, so no rate either; the total still converts.
        val brief = SessionDetailsMapping.summary(discharge.copy(counterCoveredMs = 30_000), recording = false, readings = emptyList(), fullUah = 4_000_000)
        assertNull(brief.averageMa)
        assertNull(brief.percentPerHour)
        assertEquals(30.0, brief.chargePercent!!, 1e-9)
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val T0 = 1_700_000_000_000L

        val discharge = ChargeSession(
            sessionId = "s",
            type = SessionType.DISCHARGE,
            startTime = T0,
            endTime = T0 + 3 * HOUR,
            startLevel = 90,
            endLevel = 60,
            deltaUah = 1_200_000,
            avgCurrentUa = -400_000,
            estCapacityMah = null,
            observationId = "gen-1",
            lastSampleTime = T0 + 3 * HOUR,
            observedMs = 3 * HOUR,
            counterCoveredMs = 3 * HOUR,
            screenOnMs = HOUR,
            screenOffMs = 2 * HOUR,
            screenOnUah = 600_000,
            screenOffUah = 600_000,
            source = "BatteryManager observed interval",
        )
    }
}
