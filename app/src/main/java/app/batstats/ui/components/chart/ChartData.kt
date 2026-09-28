package app.batstats.ui.components.chart

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import app.batstats.ui.theme.chartColors
import java.text.NumberFormat
import kotlin.math.abs

/**
 * One reading at [timeMs] (epoch ms). A `null` or non-finite [value] is a gap marker: the line breaks there.
 * Every chart expects its points sorted by [timeMs] ascending.
 */
@Immutable
data class TimePoint(val timeMs: Long, val value: Double?)

/** The time span a chart shows, e.g. the last hour for the live trace. Points outside it are clipped. */
@Immutable
data class TimeWindow(val startMs: Long, val endMs: Long) {
    val spanMs: Long get() = endMs - startMs
}

/** Formats a value for readouts, labels and the spoken summary (the caller owns units and locale rules). */
fun interface ValueFormatter {
    fun format(value: Double): String
}

/**
 * Default [ValueFormatter]: the default-locale number with at most [maxDecimals] fraction digits (grouped), then
 * [unit] after a space. Values ≥ 100 drop the decimals. A data class, so a series that uses it stays comparable.
 */
@Immutable
data class NumberFormatter(val unit: String = "", val maxDecimals: Int = 1) : ValueFormatter {
    override fun format(value: Double): String {
        val number = NumberFormat.getNumberInstance().apply {
            maximumFractionDigits = if (abs(value) >= 100) 0 else maxDecimals
        }.format(value)
        return if (unit.isEmpty()) number else "$number $unit"
    }
}

/** How a series is painted. Colors come from `MaterialTheme.chartColors` via [ChartDefaults]. */
@Immutable
sealed interface SeriesStyle {
    /** One [color] for the whole line; [fill] (optional) washes the area between the line and the zero baseline. */
    @Immutable
    data class Solid(val color: Color, val fill: Color? = null) : SeriesStyle

    /**
     * Colored by sign: [positive] above zero, [negative] below, split exactly at the zero line (energy direction:
     * positive current flows into the battery). The axis always includes zero.
     */
    @Immutable
    data class Signed(
        val positive: Color,
        val negative: Color,
        val positiveFill: Color? = null,
        val negativeFill: Color? = null,
    ) : SeriesStyle
}

/**
 * One line of a [TimeSeriesChart].
 *
 * @param label legend, readout and spoken-summary name ("Current").
 * @param unit shown above the value axis; series with different units get separate left/right axes.
 * @param format formats readout, reference and summary values (tick labels use the axis step's decimals).
 * @param axisMin / [axisMax] a value the axis always includes (e.g. 0 and 100 for level %); readings beyond it
 *   still widen the axis. `null` follows the data.
 * @param maxGapMs also break the line where consecutive points are further apart than this; `null` breaks only
 *   at gap markers.
 * @param showPoints draw a dot at every point, for sparse measurements such as capacity estimates.
 */
@Immutable
data class ChartSeries(
    val label: String,
    val points: List<TimePoint>,
    val style: SeriesStyle,
    val unit: String = "",
    val format: ValueFormatter = NumberFormatter(unit),
    val axisMin: Double? = null,
    val axisMax: Double? = null,
    val maxGapMs: Long? = null,
    val showPoints: Boolean = false,
)

/** A dashed threshold line (e.g. design capacity) on the axis of series [seriesIndex], labelled at its right end. */
@Immutable
data class ChartReference(val value: Double, val label: String, val seriesIndex: Int = 0)

/** Theme-backed series styles. */
object ChartDefaults {
    /** The power trace: charge color where energy flows in (> 0), drain color where it flows out (< 0). */
    @Composable
    @ReadOnlyComposable
    fun directionStyle(filled: Boolean = true): SeriesStyle.Signed {
        val colors = MaterialTheme.chartColors
        return SeriesStyle.Signed(
            positive = colors.charge,
            negative = colors.drain,
            positiveFill = if (filled) colors.chargeFill else null,
            negativeFill = if (filled) colors.drainFill else null,
        )
    }

    /** A single-color line; [filled] adds the area wash at the theme's fill alpha. */
    @Composable
    @ReadOnlyComposable
    fun solidStyle(color: Color, filled: Boolean = false): SeriesStyle.Solid = SeriesStyle.Solid(
        color = color,
        fill = if (filled) color.copy(alpha = MaterialTheme.chartColors.chargeFill.alpha) else null,
    )
}
