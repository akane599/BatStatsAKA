package com.akane.voltwise.battery.widget

import com.akane.voltwise.R
import com.akane.voltwise.battery.data.BatteryRepository
import com.akane.voltwise.battery.data.db.BatterySample
import com.akane.voltwise.battery.util.TimeEstimator
import com.akane.voltwise.ui.format.percentText
import java.util.Locale
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
        val texts = readings.drop(1).map { reading -> reading.remainingMs?.let { TimeEstimator.duration(it, Locale.US, english) } }
        assertEquals("Same text for the raw capture and the writer's copy", listOf("2 h 0 min", "2 h 0 min", "2 h 0 min", "2 h 0 min"), texts)
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
        assertEquals("2 h 0 min", TimeEstimator.duration(7_080_000, Locale.US, english))
        assertEquals("40 min", TimeEstimator.duration(2_400_000, Locale.US, english))
        assertEquals("1 min", TimeEstimator.duration(0, Locale.US, english))
    }

    @Test fun widgetTextUsesTheLocalizedTemplates() {
        assertEquals("42%", percentText(42.0, Locale.US, english))
        assertEquals("%42", percentText(42.0, turkish, turkishTemplates))
        assertEquals("42 %", percentText(42.0, Locale.forLanguageTag("es-ES"), spanishTemplates))
        assertEquals("2 sa 5 dk", TimeEstimator.duration(7_500_000, turkish, turkishTemplates))
        assertEquals("40 dk", TimeEstimator.duration(2_400_000, turkish, turkishTemplates))
    }

    private val turkish = Locale.forLanguageTag("tr-TR")

    private fun templates(vararg entries: Pair<Int, String>): (Int, Array<out Any>) -> String {
        val byId = entries.toMap()
        return { id, args -> String.format(Locale.ROOT, byId.getValue(id), *args) }
    }

    private val english = templates(
        R.string.percent_value to "%1\$s%%",
        R.string.now_duration_hours_minutes to "%1\$s h %2\$s min",
        R.string.now_duration_minutes to "%1\$s min",
    )
    private val turkishTemplates = templates(
        R.string.percent_value to "%%%1\$s",
        R.string.now_duration_hours_minutes to "%1\$s sa %2\$s dk",
        R.string.now_duration_minutes to "%1\$s dk",
    )
    private val spanishTemplates = templates(R.string.percent_value to "%1\$s %%")
}
