package app.batstats.viewmodel

import app.batstats.battery.data.db.DailySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** History › Days drain: %/h from covered time, % of battery totals, unmeasured days as unavailable (not zero). */
class HistoryDrainMappingTest {
    @Test fun screenRatesUseTheCoveredTimeAndTotalsAreAShareOfTheBattery() {
        // 2 h screen on of which 1 h was covered by the counter, 400 mAh in it; 10 h off, 5 h covered, 250 mAh.
        val day = day(TODAY - 1, onMs = 2 * HOUR, onCovered = HOUR, onUah = 400_000, offMs = 10 * HOUR, offCovered = 5 * HOUR, offUah = 250_000, chargedUah = 2_500_000)
        val state = HistoryMapping.days(DayRange.WEEK, TODAY, listOf(day), fullUah = FULL_UAH)

        assertEquals(DrainUnit.PERCENT, state.unit)
        val figures = state.days.single { it.epochDay == TODAY - 1 }.figures!!
        // 400 mAh over the covered hour = 400 mA = 8 %/h of 5,000 mAh; the duration stays the whole screen-on time.
        assertEquals(DrainState(2 * HOUR, 400.0, 8.0), figures.screenOn)
        assertEquals(DrainState(10 * HOUR, 50.0, 1.0), figures.screenOff)
        assertEquals(8.0, figures.screenOnUsed!!, 1e-9)
        assertEquals(5.0, figures.screenOffUsed!!, 1e-9)
        assertEquals(13.0, figures.used!!, 1e-9)
        assertEquals(50.0, figures.charged, 1e-9)
    }

    @Test fun legacyRowsWithoutCoverageFallBackToTheWholeScreenTime() {
        val legacy = day(TODAY - 1, onMs = 2 * HOUR, onCovered = null, onUah = 400_000, offMs = 10 * HOUR, offCovered = null, offUah = 250_000)
        val figures = HistoryMapping.days(DayRange.WEEK, TODAY, listOf(legacy), fullUah = FULL_UAH).days.single { it.epochDay == TODAY - 1 }.figures!!

        assertEquals(DrainState(2 * HOUR, 200.0, 4.0), figures.screenOn)
        assertEquals(DrainState(10 * HOUR, 25.0, 0.5), figures.screenOff)
        assertEquals(13.0, figures.used!!, 1e-9)

        // A legacy bucket with screen time and no charge is unmeasured, not a 0 mA drain.
        val noCharge = legacy.copy(screenOffDischargeUah = 0)
        val partial = HistoryMapping.days(DayRange.WEEK, TODAY, listOf(noCharge), fullUah = FULL_UAH).days.single { it.epochDay == TODAY - 1 }.figures!!
        assertEquals(DrainState(10 * HOUR, null, null), partial.screenOff)
        assertNull(partial.screenOffUsed)
        assertEquals(8.0, partial.used!!, 1e-9)
    }

    @Test fun anUnmeasuredDayIsUnavailableAndLeftOutOfTheAverage() {
        val measured = day(TODAY - 1, onMs = 2 * HOUR, onCovered = 2 * HOUR, onUah = 600_000, offMs = 10 * HOUR, offCovered = 10 * HOUR, offUah = 300_000, chargedUah = 1_000_000)
        // Screen time on both, but the counter covered none of it (v6 zero coverage).
        val unmeasured = day(TODAY - 2, onMs = 4 * HOUR, onCovered = 0, onUah = 0, offMs = 6 * HOUR, offCovered = 0, offUah = 0, chargedUah = 0)
        val state = HistoryMapping.days(DayRange.WEEK, TODAY, listOf(unmeasured, measured), fullUah = FULL_UAH)

        val blank = state.days.single { it.epochDay == TODAY - 2 }.figures!!
        assertNull(blank.used)
        assertNull(blank.screenOnUsed)
        assertNull(blank.screenOffUsed)
        assertEquals(DrainState(4 * HOUR, null, null), blank.screenOn)
        assertEquals(DrainState(6 * HOUR, null, null), blank.screenOff)

        // Drain averages only the measured day; time and charge still average both recorded days.
        val average = state.average!!
        assertEquals(12.0, average.screenOnUsed!!, 1e-9)
        assertEquals(6.0, average.screenOffUsed!!, 1e-9)
        assertEquals(18.0, average.used!!, 1e-9)
        assertEquals(DrainState(3 * HOUR, 300.0, 6.0), average.screenOn)
        assertEquals(DrainState(8 * HOUR, 30.0, 0.6), average.screenOff)
        assertEquals(10.0, average.charged, 1e-9)

        // Every averaged day unmeasured: the average drain is unavailable too.
        val none = HistoryMapping.days(DayRange.WEEK, TODAY, listOf(unmeasured), fullUah = FULL_UAH).average!!
        assertNull(none.used)
        assertEquals(DrainState(4 * HOUR, null, null), none.screenOn)
    }

    @Test fun aDayWithoutTimeOnBatteryUsedNothing() {
        val plugged = day(TODAY - 1, onMs = 0, onCovered = 0, onUah = 0, offMs = 0, offCovered = 0, offUah = 0, chargedUah = 3_000_000)
        val figures = HistoryMapping.days(DayRange.WEEK, TODAY, listOf(plugged), fullUah = FULL_UAH).days.single { it.epochDay == TODAY - 1 }.figures!!
        assertEquals(0.0, figures.used!!, 1e-9)
        assertEquals(60.0, figures.charged, 1e-9)
    }

    @Test fun ratesNeedAMinuteOfCoverage() {
        val brief = day(TODAY - 1, onMs = HOUR, onCovered = 59 * SECOND, onUah = 10_000, offMs = HOUR, offCovered = MINUTE, offUah = 1_000)
        val figures = HistoryMapping.days(DayRange.WEEK, TODAY, listOf(brief), fullUah = FULL_UAH).days.single { it.epochDay == TODAY - 1 }.figures!!
        // Under a minute: no rate, though the charge itself still counts toward the total.
        assertEquals(DrainState(HOUR, null, null), figures.screenOn)
        assertEquals(0.2, figures.screenOnUsed!!, 1e-9)
        assertEquals(DrainState(HOUR, 60.0, 1.2), figures.screenOff)
    }

    @Test fun anUnknownCapacityKeepsMahTotalsAndMilliampRates() {
        val day = day(TODAY - 1, onMs = 2 * HOUR, onCovered = HOUR, onUah = 400_000, offMs = 10 * HOUR, offCovered = 5 * HOUR, offUah = 250_000, chargedUah = 2_500_000)
        for (capacity in listOf(null, 0L)) {
            val state = HistoryMapping.days(DayRange.WEEK, TODAY, listOf(day), fullUah = capacity)
            assertEquals(DrainUnit.MAH, state.unit)
            val figures = state.days.single { it.epochDay == TODAY - 1 }.figures!!
            assertEquals(DrainState(2 * HOUR, 400.0, null), figures.screenOn)
            assertEquals(DrainState(10 * HOUR, 50.0, null), figures.screenOff)
            assertEquals(400.0, figures.screenOnUsed!!, 1e-9)
            assertEquals(650.0, figures.used!!, 1e-9)
            assertEquals(2_500.0, figures.charged, 1e-9)
        }
    }

    private companion object {
        const val SECOND = 1_000L
        const val MINUTE = 60 * SECOND
        const val HOUR = 60 * MINUTE
        const val TODAY = 20_000L
        const val FULL_UAH = 5_000_000L

        fun day(
            epochDay: Long,
            onMs: Long,
            onCovered: Long?,
            onUah: Long,
            offMs: Long,
            offCovered: Long?,
            offUah: Long,
            chargedUah: Long = 0,
        ) = DailySummary(
            epochDay = epochDay,
            screenOnMs = onMs,
            screenOffMs = offMs,
            screenOnDischargeUah = onUah,
            screenOffDischargeUah = offUah,
            chargedUah = chargedUah,
            screenOnCoveredMs = onCovered,
            screenOffCoveredMs = offCovered,
        )
    }
}
