package com.akane.voltwise.battery.measurement

import com.akane.voltwise.battery.data.db.DailySummary
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

class DailySummaryAggregatorTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private fun at(time: String) = OffsetDateTime.parse(time).toInstant().toEpochMilli()
    private fun day(date: String) = LocalDate.parse(date).toEpochDay()
    private fun apply(interval: DayInterval, rows: Map<Long, DailySummary> = emptyMap()) =
        DailySummaryAggregator.apply(rows, interval, berlin, updatedAt = 42).associateBy { it.epochDay }

    @Test fun partialCoverageAcrossDaysPreservesTotalsAndNeverExceedsDuration() {
        val rows = apply(DayInterval(at("2026-06-10T00:00+02:00"), at("2026-06-13T00:00+02:00"),
            screenOnMs = 3, screenOnCoveredMs = 2, screenOnDischargeUah = 0)).values.toList()
        assertEquals(2L, rows.sumOf { it.screenOnCoveredMs!! })
        assertTrue(rows.all { it.screenOnCoveredMs!! in 0..it.screenOnMs })
        assertEquals(0L, rows.last().screenOnCoveredMs)
    }

    @Test fun knownCoverageAccumulatesButLegacyCoverageStaysUnknown() {
        val d = day("2026-06-10")
        val interval = DayInterval(at("2026-06-10T10:00+02:00"), at("2026-06-10T10:02+02:00"),
            screenOnMs = 120_000, screenOnCoveredMs = 60_000, screenOnDischargeUah = 1000)
        val first = apply(interval).getValue(d)
        assertEquals(60_000L, first.screenOnCoveredMs)
        assertEquals(120_000L, apply(interval, mapOf(d to first)).getValue(d).screenOnCoveredMs)
        val legacy = first.copy(screenOnCoveredMs = null)
        assertNull(apply(interval, mapOf(d to legacy)).getValue(d).screenOnCoveredMs)
    }

    @Test fun intervalWithinOneDayAddsToThatDay() {
        val june10 = day("2026-06-10")
        val rows = apply(DayInterval(at("2026-06-10T10:00+02:00"), at("2026-06-10T10:30+02:00"),
            screenOnMs = 1_800_000, screenOnDischargeUah = 90_000, cpuSuspendMs = 0,
            endLevelPercent = 64, endTemperatureDeciC = 312))
        val expected = DailySummary(june10, screenOnMs = 1_800_000, screenOnDischargeUah = 90_000, cpuSuspendMs = 0,
            minLevel = 64, maxLevel = 64, peakTemperatureDeciC = 312, updatedAt = 42,
            screenOnCoveredMs = 0, screenOffCoveredMs = 0)
        assertEquals(mapOf(june10 to expected), rows)
        assertEquals(june10, DailySummaryAggregator.epochDay(at("2026-06-10T23:59:59+02:00"), berlin))
        assertEquals(june10 + 1, DailySummaryAggregator.epochDay(at("2026-06-10T22:00:00Z"), berlin))
    }

    @Test fun existingRowIsAccumulated() {
        val june10 = day("2026-06-10")
        val existing = DailySummary(june10, screenOffMs = 1_000, screenOffDischargeUah = 50, chargedUah = 7,
            cpuSuspendMs = 400, minLevel = 60, maxLevel = 70, peakTemperatureDeciC = 330, updatedAt = 1)
        val rows = apply(DayInterval(at("2026-06-10T10:00+02:00"), at("2026-06-10T10:00:02+02:00"),
            screenOffMs = 2_000, screenOffDischargeUah = 100, cpuSuspendMs = 600,
            endLevelPercent = 55, endTemperatureDeciC = 300), mapOf(june10 to existing))
        assertEquals(existing.copy(screenOffMs = 3_000, screenOffDischargeUah = 150, cpuSuspendMs = 1_000,
            minLevel = 55, updatedAt = 42), rows[june10])
    }

    @Test fun intervalAcrossMidnightIsSplitByWallTime() {
        val rows = apply(DayInterval(at("2026-06-10T23:45+02:00"), at("2026-06-11T00:15+02:00"),
            screenOffMs = 1_800_000, screenOffDischargeUah = 9_001, cpuSuspendMs = 1_500_000, endLevelPercent = 50))
        val first = rows.getValue(day("2026-06-10"))
        val second = rows.getValue(day("2026-06-11"))
        assertEquals(900_000L, first.screenOffMs)
        assertEquals(4_500L, first.screenOffDischargeUah)
        assertEquals(750_000L, first.cpuSuspendMs)
        assertNull(first.minLevel) // the reading belongs to the interval's end
        assertEquals(900_000L, second.screenOffMs)
        assertEquals(4_501L, second.screenOffDischargeUah) // totals are preserved
        assertEquals(750_000L, second.cpuSuspendMs)
        assertEquals(50, second.minLevel)
    }

    @Test fun springForwardDayHasTwentyThreeHours() {
        // Berlin 2026-03-29: 02:00 → 03:00. 23:00 (+01) → 03:30 (+02) is 3.5 real hours, 1 of them on the 28th.
        val rows = apply(DayInterval(at("2026-03-28T23:00+01:00"), at("2026-03-29T03:30+02:00"),
            screenOffMs = 12_600_000, screenOffDischargeUah = 35_000))
        assertEquals(3_600_000L, rows.getValue(day("2026-03-28")).screenOffMs)
        assertEquals(10_000L, rows.getValue(day("2026-03-28")).screenOffDischargeUah)
        assertEquals(9_000_000L, rows.getValue(day("2026-03-29")).screenOffMs)
        assertEquals(25_000L, rows.getValue(day("2026-03-29")).screenOffDischargeUah)

        // The local day is 23 h long: 24 h from its midnight spill one hour into the 30th.
        val whole = apply(DayInterval(at("2026-03-29T00:00+01:00"), at("2026-03-30T01:00+02:00"), screenOffMs = 86_400_000))
        assertEquals(82_800_000L, whole.getValue(day("2026-03-29")).screenOffMs)
        assertEquals(3_600_000L, whole.getValue(day("2026-03-30")).screenOffMs)
    }

    @Test fun fallBackDayHasTwentyFiveHours() {
        // Berlin 2026-10-25: 03:00 → 02:00. 23:00 (+02) → 02:30 (+01) is 4.5 real hours, 1 of them on the 24th.
        val rows = apply(DayInterval(at("2026-10-24T23:00+02:00"), at("2026-10-25T02:30+01:00"),
            screenOnMs = 16_200_000, chargedUah = 45_000))
        assertEquals(3_600_000L, rows.getValue(day("2026-10-24")).screenOnMs)
        assertEquals(10_000L, rows.getValue(day("2026-10-24")).chargedUah)
        assertEquals(12_600_000L, rows.getValue(day("2026-10-25")).screenOnMs)
        assertEquals(35_000L, rows.getValue(day("2026-10-25")).chargedUah)

        // The local day is 25 h long and all of it stays on the 25th.
        val whole = apply(DayInterval(at("2026-10-25T00:00+02:00"), at("2026-10-26T00:00+01:00"), screenOffMs = 90_000_000))
        assertEquals(90_000_000L, whole.getValue(day("2026-10-25")).screenOffMs)
        assertEquals(DailySummary(day("2026-10-26"), updatedAt = 42, screenOnCoveredMs = 0, screenOffCoveredMs = 0), whole[day("2026-10-26")])
    }

    @Test fun readingsTrackMinMaxLevelAndPeakTemperature() {
        var rows = emptyMap<Long, DailySummary>()
        var time = at("2026-06-10T08:00+02:00")
        for ((level, temperature) in listOf(80 to 300, 75 to 350, 90 to 320, null to null)) {
            rows = rows + apply(DayInterval(time, time, endLevelPercent = level, endTemperatureDeciC = temperature), rows)
            time += 60_000
        }
        val june10 = rows.getValue(day("2026-06-10"))
        assertEquals(75, june10.minLevel)
        assertEquals(90, june10.maxLevel)
        assertEquals(350, june10.peakTemperatureDeciC)
        assertNull(june10.cpuSuspendMs) // never discharging: not measured
    }

    @Test fun intervalWithNothingToAddTouchesOnlyTheEndDay() {
        val rows = apply(DayInterval(at("2026-06-10T22:00+02:00"), at("2026-06-11T02:00+02:00"), endLevelPercent = 40))
        assertEquals(setOf(day("2026-06-11")), rows.keys)
        assertEquals(40, rows.getValue(day("2026-06-11")).minLevel)
    }

    @Test fun intervalIsTheDifferenceOfTwoEngineSummaries() {
        val start = at("2026-06-10T12:00+02:00")
        fun obs(t: Long, charge: Long, power: PowerState, uptime: Long = t, boundary: Boundary = Boundary.SAMPLE) =
            Observation(start + t, t, uptime, 70, charge, -1, 4000, power, false, false, "one", boundary = boundary)
        val engine = ObservationEngine()
        val a = engine.accept(obs(0, 4_000_000, PowerState.DISCHARGING))
        assertEquals(DayInterval(start, start, endLevelPercent = 70),
            DailySummaryAggregator.interval(ObservationSummary(), a, endTemperatureDeciC = null))
        val b = engine.accept(obs(60_000, 3_999_000, PowerState.DISCHARGING, uptime = 20_000))
        assertEquals(DayInterval(start, start + 60_000, screenOffMs = 60_000, screenOffDischargeUah = 1_000,
            cpuSuspendMs = 40_000, endLevelPercent = 70, endTemperatureDeciC = 301, screenOffCoveredMs = 60_000),
            DailySummaryAggregator.interval(a, b, endTemperatureDeciC = 301))
        val c = engine.accept(obs(120_000, 3_999_000, PowerState.CHARGING, uptime = 80_000, boundary = Boundary.POWER))
        val d = engine.accept(obs(180_000, 4_009_000, PowerState.CHARGING, uptime = 140_000))
        assertEquals(DayInterval(start + 120_000, start + 180_000, chargedUah = 10_000, endLevelPercent = 70),
            DailySummaryAggregator.interval(c, d, endTemperatureDeciC = null))
    }
}
