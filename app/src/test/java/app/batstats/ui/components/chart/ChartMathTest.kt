package app.batstats.ui.components.chart

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.sin

class ChartMathTest {
    private val utc = TimeZone.getTimeZone("UTC")

    // 2025-10-09 09:20 UTC, the screenshot suite's FIXED_TIME_MS.
    private val fixedTime = 1_760_001_600_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    private fun assertCovers(ticks: NiceTicks, min: Double, max: Double, maxCount: Int) {
        assertTrue("min ${ticks.min} > $min", ticks.min <= min)
        assertTrue("max ${ticks.max} < $max", ticks.max >= max)
        assertTrue("count ${ticks.count} > $maxCount", ticks.count <= maxCount)
        assertTrue("count ${ticks.count} < 2", ticks.count >= 2)
    }

    @Test
    fun niceTicks_levelRangeUsesQuarterSteps() {
        assertEquals(NiceTicks(0.0, 100.0, 25.0), ChartMath.niceTicks(0.0, 100.0, 5))
        assertEquals(NiceTicks(0.0, 100.0, 20.0), ChartMath.niceTicks(0.0, 100.0, 6))
        assertEquals(listOf(0.0, 25.0, 50.0, 75.0, 100.0), ChartMath.niceTicks(0.0, 100.0, 5).values())
    }

    @Test
    fun niceTicks_tinyRange() {
        val ticks = ChartMath.niceTicks(4.2011, 4.2013, 5)
        assertCovers(ticks, 4.2011, 4.2013, 5)
        assertEquals(0.00005, ticks.step, 1e-12)
        assertEquals(5, ticks.decimals)
    }

    @Test
    fun niceTicks_hugeRange() {
        val ticks = ChartMath.niceTicks(0.0, 3e12, 5)
        assertEquals(1e12, ticks.step, 0.0)
        assertEquals(0.0, ticks.min, 0.0)
        assertEquals(3e12, ticks.max, 0.0)
        assertEquals(0, ticks.decimals)
    }

    @Test
    fun niceTicks_negativeRange() {
        val ticks = ChartMath.niceTicks(-1200.0, -300.0, 5)
        assertCovers(ticks, -1200.0, -300.0, 5)
        assertEquals(250.0, ticks.step, 0.0)
        assertTrue(ticks.values().all { it < 0.0 })
    }

    @Test
    fun niceTicks_rangeAcrossZeroHasAnExactZeroTick() {
        val ticks = ChartMath.niceTicks(-1200.0, 400.0, 5)
        assertEquals(NiceTicks(-1500.0, 500.0, 500.0), ticks)
        assertTrue(0.0 in ticks.values())
    }

    @Test
    fun niceTicks_equalBoundsArePadded() {
        val flat = ChartMath.niceTicks(80.0, 80.0, 5)
        assertTrue(flat.min < 80.0 && flat.max > 80.0)
        assertTrue(flat.count in 2..5)

        val zero = ChartMath.niceTicks(0.0, 0.0, 5)
        assertEquals(NiceTicks(-1.0, 1.0, 0.5), zero)
        assertTrue(0.0 in zero.values())
    }

    @Test
    fun niceTicks_nonFiniteFallsBackAndCountIsAtLeastTwo() {
        assertEquals(NiceTicks(0.0, 1.0, 1.0), ChartMath.niceTicks(Double.NaN, 5.0, 5))
        assertEquals(NiceTicks(0.0, 1.0, 1.0), ChartMath.niceTicks(0.0, Double.POSITIVE_INFINITY, 5))
        assertCovers(ChartMath.niceTicks(3.0, 7.0, 0), 3.0, 7.0, 2)
    }

    @Test
    fun niceTicks_reversedBoundsAreSwapped() {
        assertEquals(ChartMath.niceTicks(0.0, 100.0, 5), ChartMath.niceTicks(100.0, 0.0, 5))
    }

    @Test
    fun alignedTicks_returnsExactlyTheRequestedCount() {
        assertEquals(NiceTicks(0.0, 100.0, 25.0), ChartMath.alignedTicks(0.0, 100.0, 5))
        val current = ChartMath.alignedTicks(-1200.0, 400.0, 5)
        assertEquals(5, current.count)
        assertCovers(current, -1200.0, 400.0, 5)
    }

    @Test
    fun sharedTicks_givesBothAxesTheSameGridlines() {
        val (current, level) = ChartMath.sharedTicks(-1200.0..400.0, 0.0..100.0, 6)
        assertEquals(current.count, level.count)
        assertEquals(NiceTicks(-1500.0, 500.0, 500.0), current)
        assertEquals(NiceTicks(0.0, 100.0, 25.0), level)
    }

    @Test
    fun decimalsFor_matchesTheStep() {
        assertEquals(2, ChartMath.decimalsFor(0.25))
        assertEquals(1, ChartMath.decimalsFor(2.5))
        assertEquals(0, ChartMath.decimalsFor(500.0))
        assertEquals(0, ChartMath.decimalsFor(5e11))
        assertEquals(5, ChartMath.decimalsFor(0.00005))
    }

    @Test
    fun timeTicks_hourWindowUsesQuarterHours() {
        val ticks = ChartMath.timeTicks(fixedTime, fixedTime + hour, 6, utc)
        assertEquals(15 * minute, ticks.stepMs)
        assertEquals(TimeGranularity.MINUTES, ticks.granularity)
        // 09:30, 09:45, 10:00, 10:15.
        assertEquals(List(4) { fixedTime + 10 * minute + it * 15 * minute }, ticks.values.toList())
    }

    @Test
    fun timeTicks_alignToLocalWallClockInOffsetZones() {
        val kolkata = TimeZone.getTimeZone("Asia/Kolkata")
        val ticks = ChartMath.timeTicks(fixedTime, fixedTime + day, 5, kolkata)
        assertEquals(6 * hour, ticks.stepMs)
        assertEquals(TimeGranularity.HOURS, ticks.granularity)
        val calendar = Calendar.getInstance(kolkata)
        ticks.values.forEach { tick ->
            calendar.timeInMillis = tick
            assertEquals(0, calendar.get(Calendar.MINUTE))
            assertEquals(0, calendar.get(Calendar.HOUR_OF_DAY) % 6)
        }
        assertTrue(ticks.values.size in 3..5)
    }

    @Test
    fun timeTicks_springForwardKeepsWholeLocalHours() {
        val madrid = TimeZone.getTimeZone("Europe/Madrid")
        // 2025-03-29 22:00 UTC .. 2025-03-30 06:00 UTC; clocks jump 02:00 → 03:00 local.
        val start = 1_743_285_600_000L
        val ticks = ChartMath.timeTicks(start, start + 8 * hour, 9, madrid)
        assertEquals(hour, ticks.stepMs)
        val calendar = Calendar.getInstance(madrid)
        ticks.values.toList().zipWithNext().forEach { (a, b) -> assertTrue(b > a) }
        ticks.values.forEach { tick ->
            calendar.timeInMillis = tick
            assertEquals(0, calendar.get(Calendar.MINUTE))
            assertTrue(tick in start..start + 8 * hour)
        }
    }

    @Test
    fun timeTicks_weekAndMonthSpansUseCalendarBoundaries() {
        val daily = ChartMath.timeTicks(fixedTime, fixedTime + 7 * day, 8, utc)
        assertEquals(TimeGranularity.DAYS, daily.granularity)
        assertTrue(daily.values.all { it % day == 0L })
        assertEquals(7, daily.values.size)

        val weekly = ChartMath.timeTicks(fixedTime, fixedTime + 30 * day, 6, utc)
        assertEquals(7 * day, weekly.stepMs)
        val calendar = Calendar.getInstance(utc)
        weekly.values.forEach { tick ->
            calendar.timeInMillis = tick
            assertEquals(Calendar.MONDAY, calendar.get(Calendar.DAY_OF_WEEK))
            assertEquals(0L, tick % day)
        }

        val quarterly = ChartMath.timeTicks(fixedTime, fixedTime + 365 * day, 6, utc)
        assertEquals(TimeGranularity.MONTHS, quarterly.granularity)
        quarterly.values.forEach { tick ->
            calendar.timeInMillis = tick
            assertEquals(1, calendar.get(Calendar.DAY_OF_MONTH))
            assertEquals(0, calendar.get(Calendar.MONTH) % 3)
        }
        assertTrue(quarterly.values.size in 3..6)
    }

    @Test
    fun timeTicks_emptyRangeHasNoTicks() {
        assertEquals(0, ChartMath.timeTicks(fixedTime, fixedTime, 5, utc).values.size)
        assertEquals(0, ChartMath.timeTicks(fixedTime, fixedTime - hour, 5, utc).values.size)
    }

    private fun wave(count: Int, stepMs: Long = 1_000L) = List(count) { i ->
        TimePoint(fixedTime + i * stepMs, 100 * sin(i / 7.0) + (i % 13) * 3.0)
    }

    @Test
    fun downsample_smallInputIsReturnedAsIs() {
        val points = wave(40)
        assertTrue(ChartMath.downsampleMinMax(points, 10) === points)
        assertTrue(ChartMath.downsampleMinMax(points, 0) === points)
    }

    @Test
    fun downsample_keepsEachBucketsExtremesInTimeOrder() {
        val points = wave(1_000)
        val buckets = 10
        val reduced = ChartMath.downsampleMinMax(points, buckets)
        assertTrue(reduced.size <= buckets * 4)
        assertEquals(points.first(), reduced.first())
        assertEquals(points.last(), reduced.last())
        reduced.zipWithNext().forEach { (a, b) -> assertTrue(b.timeMs > a.timeMs) }
        val span = points.last().timeMs - points.first().timeMs + 1
        points.groupBy { (it.timeMs - points.first().timeMs) * buckets / span }.values.forEach { bucket ->
            assertTrue(bucket.minBy { it.value ?: 0.0 } in reduced)
            assertTrue(bucket.maxBy { it.value ?: 0.0 } in reduced)
        }
    }

    @Test
    fun downsample_keepsGapMarkersAndReducesEachSideSeparately() {
        val points = wave(1_000).mapIndexed { i, point ->
            when (i) {
                300, 301, 302 -> point.copy(value = null)
                700 -> point.copy(value = Double.NaN)
                else -> point
            }
        }
        val reduced = ChartMath.downsampleMinMax(points, 10)
        val gaps = reduced.filter { ChartMath.isGap(it) }
        // The three consecutive markers collapse to one; the NaN reading counts as a marker.
        assertEquals(listOf(points[300].timeMs, points[700].timeMs), gaps.map { it.timeMs })
        val firstGap = reduced.indexOfFirst { ChartMath.isGap(it) }
        assertTrue(reduced.take(firstGap).all { it.timeMs < points[300].timeMs })
        assertTrue(reduced.drop(firstGap + 1).all { it.timeMs > points[302].timeMs })
        // The bucket just before the first gap keeps its extremes and ends on its last reading.
        val beforeGap = points.subList(200, 300)
        assertTrue(beforeGap.minBy { it.value ?: 0.0 } in reduced)
        assertTrue(beforeGap.maxBy { it.value ?: 0.0 } in reduced)
        assertEquals(points[299], reduced[firstGap - 1])
    }

    @Test
    fun downsampleAsync_matchesTheSynchronousResult() = runTest {
        val points = wave(2_000)
        val async = ChartMath.downsampleMinMaxAsync(points, 25, StandardTestDispatcher(testScheduler))
        assertEquals(ChartMath.downsampleMinMax(points, 25), async)
    }

    @Test
    fun nearestIndex_picksTheCloserNeighbourWithoutCrossingGaps() {
        val points = listOf(
            TimePoint(10, 1.0),
            TimePoint(20, null),
            TimePoint(30, 3.0),
            TimePoint(40, 4.0),
        )
        assertEquals(0, ChartMath.nearestIndex(points, 12))
        assertEquals(0, ChartMath.nearestIndex(points, 19))
        assertEquals(2, ChartMath.nearestIndex(points, 21))
        assertEquals(2, ChartMath.nearestIndex(points, 34))
        assertEquals(3, ChartMath.nearestIndex(points, 36))
        assertEquals(3, ChartMath.nearestIndex(points, 400))
        assertEquals(0, ChartMath.nearestIndex(points, -5))
        assertEquals(-1, ChartMath.nearestIndex(points, 400, maxDistanceMs = 100))
        assertEquals(-1, ChartMath.nearestIndex(emptyList(), 5))
        assertEquals(-1, ChartMath.nearestIndex(listOf(TimePoint(5, null)), 5))
    }

    @Test
    fun firstIndexAtOrAfter_isALowerBound() {
        val points = listOf(TimePoint(10, 1.0), TimePoint(20, 2.0), TimePoint(20, 2.5), TimePoint(30, 3.0))
        assertEquals(0, ChartMath.firstIndexAtOrAfter(points, 5))
        assertEquals(1, ChartMath.firstIndexAtOrAfter(points, 20))
        assertEquals(3, ChartMath.firstIndexAtOrAfter(points, 21))
        assertEquals(4, ChartMath.firstIndexAtOrAfter(points, 31))
    }
}
