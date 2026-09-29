package app.batstats.battery.widget

import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.util.TimeEstimator
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [WidgetUpdater.readings]: realtime carries each capture twice while discharging (raw, then the writer's copy with
 * its estimate); the time widget holds the estimate across the raw one, so it never shows "—" between updates and
 * its text stays the same within a capture (one push, not two).
 */
class WidgetReadingsTest {
    private fun capture(timestamp: Long, etaMs: Long? = null, status: Int = 3, plugged: Int = 0) = BatteryRepository.Realtime(
        BatterySample(timestamp = timestamp, levelPercent = 70, status = status, plugged = plugged, currentNowUa = -500_000,
            chargeCounterUah = null, voltageMv = 3_900, temperatureDeciC = 300, health = 2, screenOn = true, etaMs = etaMs),
    )

    @Test fun theEstimateIsHeldAcrossRawCapturesAndDoesNotFlipWithinOne() = runTest {
        val readings = WidgetUpdater.readings(flowOf(
            capture(1_000_000),
            capture(1_000_000, etaMs = 7_200_000),
            capture(1_030_000),
            capture(1_030_000, etaMs = 7_150_000),
            capture(1_060_000),
        )).toList()

        assertEquals(listOf(null, 7_200_000L, 7_170_000L, 7_150_000L, 7_120_000L), readings.map { it.remainingMs })
        val texts = readings.drop(1).map { reading -> reading.remainingMs?.let(TimeEstimator::duration) }
        assertEquals("Same text for the raw capture and the writer's copy", listOf("2h 0m", "2h 0m", "2h 0m", "2h 0m"), texts)
    }

    @Test fun aPowerChangeDropsTheHeldEstimate() = runTest {
        val readings = WidgetUpdater.readings(flowOf(
            capture(1_000_000, etaMs = 7_200_000),
            capture(1_030_000, status = 2, plugged = 1),
        )).toList()
        assertEquals(listOf(7_200_000L, null), readings.map { it.remainingMs })
    }

    @Test fun readingsWithoutASampleAreSkipped() = runTest {
        assertEquals(emptyList<Long?>(), WidgetUpdater.readings(flowOf(BatteryRepository.Realtime())).toList().map { it.remainingMs })
    }

    @Test fun durationsRoundToFiveMinutesAndAtLeastOne() {
        assertEquals("2h 0m", TimeEstimator.duration(7_080_000))
        assertEquals("40m", TimeEstimator.duration(2_400_000))
        assertEquals("1m", TimeEstimator.duration(0))
    }
}
