package app.batstats.ui.components.chart

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartModelTest {
    private val solid = SeriesStyle.Solid(Color.Gray)
    private val signed = SeriesStyle.Signed(Color.Green, Color.Yellow)

    private fun points(vararg values: Double?) = values.mapIndexed { i, v -> TimePoint(1_000L * i, v) }

    @Test
    fun secondSeriesWithAnotherUnitGetsTheRightAxis() {
        val model = ChartModel.of(
            listOf(
                ChartSeries("Current", points(-400.0, -300.0), signed, unit = "mA"),
                ChartSeries("Level", points(60.0, 59.0), solid, unit = "%", axisMin = 0.0, axisMax = 100.0),
            ),
            window = null,
            references = emptyList(),
        )
        assertEquals(listOf(0, 1), model.axisOf.toList())
        assertEquals(2, model.axes.size)
        assertEquals("%", model.axes[1].unit)
        assertEquals(0.0, model.axes[1].min, 0.0)
        assertEquals(100.0, model.axes[1].max, 0.0)
        // A signed series always shows its zero line.
        assertEquals(0.0, model.axes[0].max, 0.0)
        assertEquals(-400.0, model.axes[0].min, 0.0)
    }

    @Test
    fun seriesWithTheSameUnitShareOneAxis() {
        val model = ChartModel.of(
            listOf(
                ChartSeries("Now", points(30.0, 32.0), solid, unit = "°C"),
                ChartSeries("Peak", points(35.0, 41.0), solid, unit = "°C"),
            ),
            window = null,
            references = emptyList(),
        )
        assertEquals(listOf(0, 0), model.axisOf.toList())
        assertEquals(1, model.axes.size)
        assertEquals(30.0, model.axes[0].min, 0.0)
        assertEquals(41.0, model.axes[0].max, 0.0)
    }

    @Test
    fun windowLimitsStatsButDrawsOneNeighbourEachSide() {
        val series = ChartSeries("Current", points(1.0, 2.0, 50.0, 3.0, 4.0, 5.0), solid)
        val model = ChartModel.of(listOf(series), TimeWindow(3_000, 4_000), emptyList())
        assertEquals(2, model.from[0])
        assertEquals(5, model.to[0])
        val stats = model.stats[0]
        assertEquals(4.0, stats?.latest ?: Double.NaN, 0.0)
        assertEquals(4_000L, stats?.latestTimeMs)
        assertEquals(3.0, stats?.low ?: Double.NaN, 0.0)
        // The off-window spike at 2 s doesn't stretch the axis.
        assertEquals(4.0, model.axes[0].max, 0.0)
    }

    @Test
    fun referencesWidenTheirAxis() {
        val model = ChartModel.of(
            listOf(ChartSeries("Capacity", points(4_300.0, 4_250.0), solid, unit = "mAh")),
            window = null,
            references = listOf(ChartReference(5_000.0, "Design")),
        )
        assertEquals(5_000.0, model.axes[0].max, 0.0)
    }

    @Test
    fun gapsOnlyAndEmptySeriesHaveNoData() {
        val gapsOnly = ChartModel.of(listOf(ChartSeries("x", points(null, Double.NaN), solid)), null, emptyList())
        assertFalse(gapsOnly.hasData)
        assertNull(gapsOnly.stats[0])
        val empty = ChartModel.of(listOf(ChartSeries("x", emptyList(), solid)), null, emptyList())
        assertFalse(empty.hasData)
        assertTrue(empty.endMs > empty.startMs)
    }

    @Test
    fun singleReadingGetsATimeSpan() {
        val model = ChartModel.of(listOf(ChartSeries("x", listOf(TimePoint(90_000, 5.0)), solid)), null, emptyList())
        assertTrue(model.hasData)
        assertTrue(model.startMs < 90_000 && model.endMs > 90_000)
    }

    @Test
    fun minuteTicksDropTheDayPeriod() {
        assertEquals("h:mm", TimeAxisFormatter.withoutDayPeriod("h:mm a"))
        assertEquals("h:mm", TimeAxisFormatter.withoutDayPeriod("h:mm\u202Fa"))
        assertEquals("h:mm", TimeAxisFormatter.withoutDayPeriod("a h:mm"))
        assertEquals("h 'at' mm", TimeAxisFormatter.withoutDayPeriod("h 'at' mm a"))
        assertEquals("HH:mm", TimeAxisFormatter.withoutDayPeriod("HH:mm"))
    }

    @Test
    fun readoutGranularityFollowsTheWindowSpan() {
        val hour = 3_600_000L
        assertEquals(TimeGranularity.SECONDS, readoutGranularity(hour))
        assertEquals(TimeGranularity.MINUTES, readoutGranularity(6 * hour))
        assertEquals(TimeGranularity.DATE_TIME, readoutGranularity(24 * hour))
        assertEquals(TimeGranularity.DAYS, readoutGranularity(90 * 24 * hour))
    }
}
