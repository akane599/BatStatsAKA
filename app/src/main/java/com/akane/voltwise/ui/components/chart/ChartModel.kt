package com.akane.voltwise.ui.components.chart

import androidx.compose.runtime.Immutable
import kotlin.math.max
import kotlin.math.min

private const val SINGLE_POINT_PAD_MS = 30_000L

/** Latest / low / high reading of a series inside the chart window. */
@Immutable
internal class SeriesStats(val latest: Double, val latestTimeMs: Long, val low: Double, val high: Double)

/**
 * A value axis: its data range (already widened for pins, references and zero), unit caption, and the bounds its
 * ticks stay inside (when every series on it has [ChartSeries.axisBounds]; widened to the data).
 */
@Immutable
internal class AxisModel(val min: Double, val max: Double, val unit: String, val bounds: ClosedFloatingPointRange<Double>? = null)

/**
 * Everything about a [TimeSeriesChart] that doesn't depend on pixels: the time span, which axis each series uses,
 * the index range to draw per series (window plus one neighbour each side, so lines run to the edges), and stats.
 */
@Immutable
internal class ChartModel(
    val startMs: Long,
    val endMs: Long,
    val axes: List<AxisModel>,
    val axisOf: IntArray,
    val from: IntArray,
    val to: IntArray,
    val stats: List<SeriesStats?>,
) {
    val spanMs: Long get() = endMs - startMs
    val hasData: Boolean get() = stats.any { it != null }

    /** Ticks per axis; two axes share gridlines. */
    fun ticks(maxCount: Int): List<NiceTicks> = if (axes.size == 2) {
        val (left, right) = ChartMath.sharedTicks(axes[0].min..axes[0].max, axes[1].min..axes[1].max, maxCount, axes[0].bounds, axes[1].bounds)
        listOf(left, right)
    } else {
        listOf(ChartMath.niceTicks(axes[0].min, axes[0].max, maxCount))
    }

    companion object {
        /**
         * The time span a chart shows: [window], else first to last reading of any series. An empty or
         * single-instant span is widened by 30 s each side, so the x mapping never divides by zero.
         */
        fun spanOf(series: List<ChartSeries>, window: TimeWindow?): TimeWindow {
            var start = window?.startMs ?: series.minOfOrNull { s -> s.points.firstOrNull { !ChartMath.isGap(it) }?.timeMs ?: Long.MAX_VALUE } ?: 0L
            var end = window?.endMs ?: series.maxOfOrNull { s -> s.points.lastOrNull { !ChartMath.isGap(it) }?.timeMs ?: Long.MIN_VALUE } ?: 0L
            if (start == Long.MAX_VALUE || end == Long.MIN_VALUE) {
                start = 0L
                end = 0L
            }
            if (end <= start) {
                start -= SINGLE_POINT_PAD_MS
                end = start + 2 * SINGLE_POINT_PAD_MS
            }
            return TimeWindow(start, end)
        }

        fun of(series: List<ChartSeries>, window: TimeWindow?, references: List<ChartReference>): ChartModel {
            val span = spanOf(series, window)
            val start = span.startMs
            val end = span.endMs
            val from = IntArray(series.size)
            val to = IntArray(series.size)
            val stats = series.mapIndexed { i, s ->
                val first = ChartMath.firstIndexAtOrAfter(s.points, start)
                val last = ChartMath.firstIndexAtOrAfter(s.points, end + 1) - 1
                from[i] = max(0, first - 1)
                to[i] = min(s.points.lastIndex, last + 1)
                statsOf(s.points, first, last)
            }
            val axisOf = IntArray(series.size) { i -> if (i == 0 || series[i].unit == series[0].unit) 0 else 1 }
            val axisCount = (axisOf.maxOrNull() ?: 0) + 1
            val axes = List(axisCount) { axis ->
                val members = series.indices.filter { axisOf[it] == axis }
                var low = Double.POSITIVE_INFINITY
                var high = Double.NEGATIVE_INFINITY
                fun include(value: Double?) {
                    if (value == null || !value.isFinite()) return
                    low = min(low, value)
                    high = max(high, value)
                }
                members.forEach { i ->
                    stats[i]?.let { include(it.low); include(it.high) }
                    include(series[i].axisMin)
                    include(series[i].axisMax)
                    if (series[i].style is SeriesStyle.Signed) include(0.0)
                }
                references.filter { axisOf.getOrElse(it.seriesIndex) { 0 } == axis }.forEach { include(it.value) }
                if (low > high) {
                    low = 0.0
                    high = 1.0
                }
                val memberBounds = members.mapNotNull { series[it].axisBounds }
                val bounds = if (members.isNotEmpty() && memberBounds.size == members.size) {
                    min(low, memberBounds.minOf { it.start })..max(high, memberBounds.maxOf { it.endInclusive })
                } else {
                    null
                }
                AxisModel(low, high, members.firstOrNull()?.let { series[it].unit }.orEmpty(), bounds)
            }
            return ChartModel(start, end, axes, axisOf, from, to, stats)
        }

        private fun statsOf(points: List<TimePoint>, first: Int, last: Int): SeriesStats? {
            var low = Double.POSITIVE_INFINITY
            var high = Double.NEGATIVE_INFINITY
            var latest: TimePoint? = null
            for (i in first..last) {
                val point = points[i]
                val value = point.value ?: continue
                if (!value.isFinite()) continue
                low = min(low, value)
                high = max(high, value)
                latest = point
            }
            val newest = latest ?: return null
            return SeriesStats(newest.value ?: return null, newest.timeMs, low, high)
        }
    }
}
