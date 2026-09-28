package app.batstats.ui.components.chart

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import kotlin.math.max

private val LabelGap = 6.dp
private val GridWidth = 1.dp
private const val MIN_Y_TICKS = 3
private const val MAX_Y_TICKS = 6
private const val MIN_TIME_TICKS = 2
private const val MAX_TIME_TICKS = 7

/** Tick rows are at least this many label heights apart. */
private const val Y_TICK_SPACING = 1.8f

/** Time labels are at least this many gaps apart. */
private const val TIME_LABEL_SPACING = 3

/**
 * Where everything of a [TimeSeriesChart] sits for one canvas size: plot bounds, axis mappings, and every label
 * already measured. Built in composition (so input and the readout have it from the first frame); the draw
 * cache only builds paths from it.
 */
@Immutable
internal class PlotLayout(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val gap: Float,
    val xMap: XAxisMap,
    val ticks: List<NiceTicks>,
    val yMaps: List<YAxisMap>,
    val tickLabels: List<List<TextLayoutResult>>,
    val unitLabels: List<TextLayoutResult?>,
    val timeLabels: List<TextLayoutResult>,
    val timeLabelLefts: FloatArray,
    val timeLabelTop: Float,
    val referenceLabels: List<TextLayoutResult>,
    val referenceYs: FloatArray,
) {
    /** Whether the plot area left after labels and gutters is non-empty; tiny canvases draw nothing. */
    val hasRoom: Boolean get() = xMap.width > 0f && bottom > top

    companion object {
        /**
         * @param edgeInset keeps the first/last reading this far inside the plot (data-fit charts), so edge dots
         *   aren't clipped; 0 for a moving window where the trace runs off the edge.
         */
        fun measure(
            density: Density,
            size: Size,
            model: ChartModel,
            references: List<ChartReference>,
            textMeasurer: TextMeasurer,
            labelStyle: TextStyle,
            timeFormatter: TimeAxisFormatter,
            edgeInset: Dp,
            endPadding: Dp,
        ): PlotLayout = with(density) {
            val gap = LabelGap.toPx()
            val digit = textMeasurer.measure("0", labelStyle)
            val labelHeight = digit.size.height.toFloat()
            val hasUnits = model.axes.any { it.unit.isNotEmpty() }
            val top = (if (hasUnits) labelHeight + gap / 2 else 0f) + labelHeight / 2
            val timeLabelTop = size.height - labelHeight
            val bottom = timeLabelTop - max(gap, labelHeight / 2 + GridWidth.toPx())
            val maxTicks = ((bottom - top) / (labelHeight * Y_TICK_SPACING)).toInt().coerceIn(MIN_Y_TICKS, MAX_Y_TICKS)
            val ticks = model.ticks(maxTicks)
            val tickLabels = ticks.map { axis ->
                val format = NumberFormat.getNumberInstance().apply {
                    minimumFractionDigits = axis.decimals
                    maximumFractionDigits = axis.decimals
                }
                List(axis.count) { textMeasurer.measure(format.formatWithMinus(axis.valueAt(it)), labelStyle) }
            }
            val unitLabels = model.axes.map { axis -> axis.unit.takeIf(String::isNotEmpty)?.let { textMeasurer.measure(it, labelStyle) } }
            fun gutter(axis: Int): Float =
                max(tickLabels[axis].maxOf { it.size.width }, unitLabels[axis]?.size?.width ?: 0).toFloat() + gap

            val left = gutter(0)
            val right = size.width - if (ticks.size > 1) gutter(1) else endPadding.toPx()
            val inset = edgeInset.toPx()
            val xMap = XAxisMap(left + inset, right - left - 2 * inset, model.startMs, model.spanMs)
            val yMaps = ticks.map { YAxisMap(top, bottom, it.min, it.max) }

            val widest = listOf(TimeGranularity.MINUTES, TimeGranularity.HOURS, TimeGranularity.DAYS)
                .maxOf { textMeasurer.measure(timeFormatter.axisLabel(model.endMs, it), labelStyle).size.width } + digit.size.width
            val maxTimeTicks = ((right - left) / (widest + gap * TIME_LABEL_SPACING)).toInt().coerceIn(MIN_TIME_TICKS, MAX_TIME_TICKS)
            val timeTicks = ChartMath.timeTicks(model.startMs, model.endMs, maxTimeTicks, timeFormatter.zone)
            val timeLabels = ArrayList<TextLayoutResult>()
            val lefts = ArrayList<Float>()
            var previousRight = Float.NEGATIVE_INFINITY
            for (tick in timeTicks.values) {
                val label = textMeasurer.measure(timeFormatter.axisLabel(tick, timeTicks.granularity), labelStyle)
                val width = label.size.width.toFloat()
                val labelLeft = (xMap.x(tick) - width / 2).coerceIn(0f, max(0f, size.width - width))
                if (labelLeft < previousRight + gap) continue
                timeLabels += label
                lefts += labelLeft
                previousRight = labelLeft + width
            }

            val referenceLabels = references.map { textMeasurer.measure(it.label, labelStyle) }
            val referenceYs = FloatArray(references.size) { i ->
                yMaps[model.axisOf.getOrElse(references[i].seriesIndex) { 0 }].y(references[i].value)
            }
            PlotLayout(
                left, top, right, bottom, gap, xMap, ticks, yMaps, tickLabels, unitLabels,
                timeLabels, lefts.toFloatArray(), timeLabelTop, referenceLabels, referenceYs,
            )
        }
    }
}
