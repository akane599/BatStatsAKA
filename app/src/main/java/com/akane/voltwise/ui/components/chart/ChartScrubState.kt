package com.akane.voltwise.ui.components.chart

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

private const val NO_SCRUB = Long.MIN_VALUE

/** What the scrub cursor snaps to: the reading time it is drawn at and each series' value there (`null` = none near). */
@Immutable
data class ChartReading(val timeMs: Long, val values: List<Double?>)

/**
 * The scrub cursor of a [TimeSeriesChart]: where the user dragged or tapped, in epoch ms. The chart makes its own by
 * default; hoist one with [rememberChartScrubState] to read it ([timeMs], [readingIn]), place it ([scrubTo]) or
 * [clear] it (e.g. when the range changes). Pass one state to two charts to scrub them together: each snaps the
 * shared time to its own nearest readings. A scrub is transient, so the state isn't saved.
 */
@Stable
class ChartScrubState(initialTimeMs: Long? = null) {
    private var time by mutableLongStateOf(initialTimeMs ?: NO_SCRUB)

    /** The cursor time (epoch ms), or `null` when not scrubbing. Reading it subscribes to every cursor move. */
    val timeMs: Long? get() = time.takeIf { it != NO_SCRUB }

    /** Puts the cursor at [timeMs]; a chart ignores a time outside its span. */
    fun scrubTo(timeMs: Long) {
        time = if (timeMs == NO_SCRUB) NO_SCRUB + 1 else timeMs
    }

    fun clear() {
        time = NO_SCRUB
    }

    /**
     * The readings the cursor snaps to in [series] (the first two, as the chart shows them) — the chart's readout
     * values, given the same [series] and [window]. `null` when not scrubbing, outside the span, or nothing is near.
     * Read it inside `derivedStateOf` to recompose only when the snapped reading changes, not on every move.
     */
    fun readingIn(series: List<ChartSeries>, window: TimeWindow? = null): ChartReading? {
        val cursor = timeMs ?: return null
        val shown = if (series.size > 2) series.subList(0, 2) else series
        val span = ChartModel.spanOf(shown, window)
        return snap(cursor, span.startMs, span.endMs, shown)?.toReading(shown.size)
    }
}

/**
 * A tap on a chart: hides the cursor when [shownHere] (this chart draws it), else puts it at [timeMs]. A cursor this
 * chart doesn't show (e.g. set outside its span, or by a chart sharing the state) moves here instead of being cleared
 * invisibly.
 */
internal fun ChartScrubState.toggleAt(timeMs: Long, shownHere: Boolean) {
    if (shownHere) clear() else scrubTo(timeMs)
}

/** A [ChartScrubState] that survives recomposition; [initialTimeMs] places the cursor (e.g. in previews). */
@Composable
fun rememberChartScrubState(initialTimeMs: Long? = null): ChartScrubState = remember { ChartScrubState(initialTimeMs) }

/**
 * The cursor snapped to the nearest readings: [timeMs] is where it is drawn; per series the reading's time and
 * value (NaN when that series has nothing near). Flat primitives so drawing it doesn't allocate.
 */
@Immutable
internal data class Selection(
    val timeMs: Long,
    val firstTimeMs: Long,
    val firstValue: Double,
    val secondTimeMs: Long,
    val secondValue: Double,
) {
    fun timeOf(series: Int): Long = if (series == 0) firstTimeMs else secondTimeMs

    /** NaN when the series has no reading near the cursor. */
    fun rawValue(series: Int): Double = if (series == 0) firstValue else secondValue

    fun valueOf(series: Int): Double? = rawValue(series).takeUnless { it.isNaN() }

    fun toReading(seriesCount: Int): ChartReading = ChartReading(timeMs, List(seriesCount, ::valueOf))
}

/** The chart's selection for a cursor at [timeMs] (`null` = not scrubbing); see [snap]. */
internal fun selectionAt(timeMs: Long?, model: ChartModel, series: List<ChartSeries>): Selection? =
    timeMs?.let { snap(it, model.startMs, model.endMs, series) }

/**
 * Nearest reading per series (up to two) among those inside [startMs]..[endMs], never across a gap marker or beyond
 * the series' `maxGapMs`; the cursor snaps to the first series' reading, else the second's. `null` when [timeMs] is
 * outside the span or no series has a reading near.
 */
internal fun snap(timeMs: Long, startMs: Long, endMs: Long, series: List<ChartSeries>): Selection? {
    if (timeMs < startMs || timeMs > endMs) return null
    fun nearest(index: Int): TimePoint? {
        val s = series.getOrNull(index) ?: return null
        // Only in-span readings: the neighbour just outside a window would put the cursor in the axis gutter.
        val first = ChartMath.firstIndexAtOrAfter(s.points, startMs)
        val end = ChartMath.firstIndexAtOrAfter(s.points, endMs + 1)
        if (first >= end) return null
        val i = ChartMath.nearestIndex(s.points.subList(first, end), timeMs, s.maxGapMs ?: Long.MAX_VALUE)
        return if (i >= 0) s.points[first + i] else null
    }
    val first = nearest(0)
    val second = nearest(1)
    val snapped = (first ?: second ?: return null).timeMs
    return Selection(
        timeMs = snapped,
        firstTimeMs = first?.timeMs ?: snapped,
        firstValue = first?.value ?: Double.NaN,
        secondTimeMs = second?.timeMs ?: snapped,
        secondValue = second?.value ?: Double.NaN,
    )
}
