package com.akane.voltwise.ui.components.chart

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChartScrubStateTest {
    private val solid = SeriesStyle.Solid(Color.Gray)
    private val signed = SeriesStyle.Signed(Color.Green, Color.Yellow)

    private fun points(vararg values: Double?) = values.mapIndexed { i, v -> TimePoint(1_000L * i, v) }

    private val current = ChartSeries("Current", points(-400.0, null, -300.0, -200.0), signed, unit = "mA")
    private val level = ChartSeries("Level", points(60.0, 59.0, 58.0, 57.0), solid, unit = "%")

    @Test
    fun scrubStateMovesAndClears() {
        val state = ChartScrubState()
        assertNull(state.timeMs)
        state.scrubTo(1_234)
        assertEquals(1_234L, state.timeMs)
        state.clear()
        assertNull(state.timeMs)
        assertEquals(99L, ChartScrubState(initialTimeMs = 99).timeMs)
        // The internal "not scrubbing" marker is never mistaken for a real time.
        state.scrubTo(Long.MIN_VALUE)
        assertNotNull(state.timeMs)
    }

    @Test
    fun selectionSnapsToTheNearestReadingPerSeries() {
        val model = ChartModel.of(listOf(current, level), null, emptyList())
        val selection = selectionAt(900, model, listOf(current, level))
        assertEquals(0L, selection?.timeMs)
        assertEquals(-400.0, selection?.valueOf(0) ?: Double.NaN, 0.0)
        assertEquals(59.0, selection?.valueOf(1) ?: Double.NaN, 0.0)
        assertEquals(1_000L, selection?.timeOf(1))
        assertEquals(ChartReading(0L, listOf(-400.0, 59.0)), selection?.toReading(2))
        // Just after the gap marker the first series has only the reading on the far side.
        val afterGap = selectionAt(1_100, model, listOf(current, level))
        assertEquals(2_000L, afterGap?.timeMs)
        assertEquals(-300.0, afterGap?.valueOf(0) ?: Double.NaN, 0.0)
    }

    @Test
    fun noSelectionWhenNotScrubbingOrOutsideTheSpan() {
        val series = listOf(ChartSeries("x", points(1.0, 2.0), solid))
        val model = ChartModel.of(series, TimeWindow(0, 1_000), emptyList())
        assertNull(selectionAt(null, model, series))
        assertNull(selectionAt(5_000, model, series))
        val gaps = listOf(ChartSeries("x", points(null, null), solid))
        assertNull(selectionAt(500, ChartModel.of(gaps, TimeWindow(0, 1_000), emptyList()), gaps))
        assertNull(selectionAt(500, ChartModel.of(emptyList(), TimeWindow(0, 1_000), emptyList()), emptyList()))
    }

    @Test
    fun cursorSnapsOnlyToReadingsInsideTheWindow() {
        // Readings every second; the window holds only those at 3 s and 4 s.
        val series = listOf(ChartSeries("x", points(1.0, 2.0, 3.0, 4.0, 5.0, 6.0), solid))
        val model = ChartModel.of(series, TimeWindow(2_500, 4_600), emptyList())
        // At the left edge the reading at 2 s is as near, but it's outside: the cursor stays in the plot.
        val atStart = selectionAt(2_500, model, series)
        assertEquals(3_000L, atStart?.timeMs)
        assertEquals(4.0, atStart?.valueOf(0) ?: Double.NaN, 0.0)
        // At the right edge the reading at 5 s is nearer, but outside too.
        assertEquals(4_000L, selectionAt(4_600, model, series)?.timeMs)
    }

    @Test
    fun readingInIsWhatTheChartShows() {
        val series = listOf(current, level)
        val state = ChartScrubState()
        assertNull(state.readingIn(series))
        state.scrubTo(2_900)
        val reading = state.readingIn(series)
        assertEquals(ChartReading(3_000L, listOf(-200.0, 57.0)), reading)
        val model = ChartModel.of(series, window = null, references = emptyList())
        assertEquals(selectionAt(2_900, model, series)?.toReading(2), reading)
        // A cursor outside the window reads nothing; a third series is ignored, as the chart ignores it.
        assertNull(state.readingIn(series, TimeWindow(1_500, 2_500)))
        assertEquals(2, state.readingIn(series + level)?.values?.size)
    }

    @Test
    fun aTapClearsOnlyACursorThisChartShows() {
        val series = listOf(ChartSeries("x", points(1.0, 2.0, 3.0, 4.0), solid))
        val model = ChartModel.of(series, window = null, references = emptyList())
        fun tap(state: ChartScrubState, atMs: Long) = state.toggleAt(atMs, shownHere = selectionAt(state.timeMs, model, series) != null)
        // Set outside this chart's span (e.g. through a shared state): invisible here, so the tap moves it in.
        val state = ChartScrubState(initialTimeMs = 10_000)
        tap(state, 1_500)
        assertEquals(1_500L, state.timeMs)
        // Visible now: the next tap clears it, and the one after shows it again.
        tap(state, 2_000)
        assertNull(state.timeMs)
        tap(state, 2_000)
        assertEquals(2_000L, state.timeMs)
    }

    @Test
    fun oneStateScrubsTwoChartsEachAtItsOwnReadings() {
        val state = ChartScrubState(initialTimeMs = 2_100)
        val everySecond = listOf(ChartSeries("Current", points(1.0, 2.0, 3.0, 4.0), solid))
        val sparse = listOf(ChartSeries("Temperature", listOf(TimePoint(0, 30.0), TimePoint(3_000, 31.0)), solid))
        assertEquals(ChartReading(2_000L, listOf(3.0)), state.readingIn(everySecond))
        assertEquals(ChartReading(3_000L, listOf(31.0)), state.readingIn(sparse))
    }
}
