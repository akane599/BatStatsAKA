package app.batstats.ui.components.chart

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.batstats.R
import app.batstats.ui.theme.BatMotion
import app.batstats.ui.theme.chartColors
import app.batstats.ui.theme.numericBody
import app.batstats.ui.theme.numericLabel
import app.batstats.ui.theme.spacing
import kotlin.math.roundToInt

private val LineWidth = 2.dp
private val GridWidth = 1.dp
private val ZeroLineWidth = 1.5.dp
private val DotRadius = 3.dp
private val CursorDotRadius = 4.dp
private val RingWidth = 2.dp
private val ReadoutGap = 8.dp
private val DashLength = 4.dp
private const val HOUR_MS = 3_600_000L
private const val NONE = Long.MIN_VALUE

/** Screenshot previews only: composes the chart with the scrub cursor at this time. */
internal val LocalPreviewScrubTimeMs = staticCompositionLocalOf<Long?> { null }

/** Default sizes for [TimeSeriesChart]. */
object TimeSeriesChartDefaults {
    /** Plot plus axis labels; the two-series legend row sits above and is extra. */
    val Height: Dp = 180.dp
}

/**
 * A time-series chart for up to two [series] (extra ones are ignored), drawn on one Canvas.
 *
 * - **Axes:** nice value ticks from [ChartMath]; the first series owns the left axis, a second series with a
 *   different unit gets a right axis on the same gridlines (same unit → shared left axis). Units sit above the
 *   axes; a legend row appears for two series. The time axis follows the locale and the 12/24-hour setting.
 * - **Data:** gap markers (null values) and `maxGapMs` jumps break the line; [SeriesStyle.Signed] colors the line
 *   and its wash by sign (the live power trace). The first series is drawn on top. Up to a few thousand points
 *   per series are fine (drawing keeps ≤ 4 per pixel column); downsample longer histories with
 *   [ChartMath.downsampleMinMaxAsync] first.
 * - **Scrub:** drag horizontally (or tap) to show a cursor and a readout of the nearest readings, formatted by each
 *   series' `format`; the readout stays after lifting, a tap clears it.
 * - **Motion:** the trace draws in once, the first time data appears (400 ms); skipped in previews and when the
 *   system removes animations.
 * - **Accessibility:** one `contentDescription` — [contentDescription] if given, otherwise time range plus latest /
 *   low / high per series.
 *
 * @param window the x range (e.g. the last hour, so the live trace scrolls); `null` fits the data.
 * @param chartHeight height of the plot including its axis labels.
 * @param references dashed threshold lines (e.g. design capacity); they widen the axis to stay visible.
 * @param markLatest dot the newest reading of each series ("now" on the live trace).
 * @param emptyText shown in place of the plot when no series has a reading in the window.
 */
@Composable
fun TimeSeriesChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    window: TimeWindow? = null,
    chartHeight: Dp = TimeSeriesChartDefaults.Height,
    references: List<ChartReference> = emptyList(),
    markLatest: Boolean = false,
    animateDrawIn: Boolean = true,
    timeFormatter: TimeAxisFormatter = rememberTimeAxisFormatter(),
    emptyText: String = stringResource(R.string.component_chart_empty),
    contentDescription: String? = null,
) {
    // Keep one instance while the content is equal, so the draw cache survives unrelated recompositions.
    val shown = remember(series) { if (series.size > 2) series.subList(0, 2) else series }
    val refs = remember(references) { references }
    val model = remember(shown, window, refs) { ChartModel.of(shown, window, refs) }
    val seriesTemplate = stringResource(R.string.component_chart_summary_series)
    val rangeTemplate = stringResource(R.string.component_chart_summary_range)
    val summary = contentDescription ?: remember(model, shown, timeFormatter, seriesTemplate, rangeTemplate, emptyText) {
        if (model.hasData) summaryText(model, shown, timeFormatter, seriesTemplate, rangeTemplate) else emptyText
    }
    val skipAnimation = !animateDrawIn || LocalInspectionMode.current
    val drawnIn = rememberSaveable { mutableStateOf(skipAnimation) }

    Column(modifier.fillMaxWidth().clearAndSetSemantics { this.contentDescription = summary }) {
        if (shown.size > 1) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = MaterialTheme.spacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                shown.forEach { s -> LegendItem(s.label, key = { LegendKey(s.style) }) }
            }
        }
        Box(Modifier.fillMaxWidth().height(chartHeight)) {
            if (model.hasData) {
                Plot(shown, model, refs, fitToData = window == null, markLatest, drawnIn, timeFormatter)
            } else {
                Text(
                    emptyText,
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Plot(
    series: List<ChartSeries>,
    model: ChartModel,
    references: List<ChartReference>,
    fitToData: Boolean,
    markLatest: Boolean,
    drawnIn: MutableState<Boolean>,
    timeFormatter: TimeAxisFormatter,
) {
    val colors = MaterialTheme.chartColors
    val labelStyle = MaterialTheme.typography.numericLabel.copy(color = colors.axisLabel)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val previewScrub = LocalPreviewScrubTimeMs.current
    val scrub = remember { ScrubState(previewScrub ?: NONE) }
    val seriesState = rememberUpdatedState(series)
    val modelState = rememberUpdatedState(model)
    val selection = remember { derivedStateOf { scrub.select(modelState.value, seriesState.value) } }
    val progress = remember { Animatable(if (drawnIn.value) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) progress.animateTo(1f, BatMotion.long(BatMotion.StandardDecelerate))
        drawnIn.value = true
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val canvas = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val layout = remember(canvas, model, references, labelStyle, timeFormatter, density, fitToData) {
            PlotLayout.measure(
                density, canvas, model, references, textMeasurer, labelStyle, timeFormatter,
                edgeInset = if (fitToData) DotRadius + RingWidth else 0.dp,
                endPadding = DotRadius + RingWidth,
            )
        }
        val layoutState = rememberUpdatedState(layout)
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        scrub.timeMs = if (scrub.timeMs != NONE) NONE else layoutState.value.xMap.time(offset.x)
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> scrub.timeMs = layoutState.value.xMap.time(offset.x) },
                    ) { change, _ ->
                        change.consume()
                        scrub.timeMs = layoutState.value.xMap.time(change.position.x)
                    }
                }
                .drawWithCache {
                    val dotRadius = DotRadius.toPx()
                    val xMap = layout.xMap
                    val drawings = series.indices.map { i ->
                        val s = series[i]
                        val dense = (model.to[i] - model.from[i] + 1) * dotRadius * 4 > xMap.width
                        buildSeriesDrawing(
                            s.points, model.from[i], model.to[i], s.style, xMap, layout.yMaps[model.axisOf[i]],
                            s.maxGapMs, allDots = s.showPoints && !dense,
                        )
                    }
                    val stroke = Stroke(LineWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val gridWidth = GridWidth.toPx()
                    val zeroWidth = ZeroLineWidth.toPx()
                    val ring = RingWidth.toPx()
                    val cursorRadius = CursorDotRadius.toPx()
                    val dash = PathEffect.dashPathEffect(floatArrayOf(DashLength.toPx(), DashLength.toPx()))
                    val leftTicks = layout.ticks[0]
                    val gap = layout.gap

                    onDrawBehind {
                        for (i in 0 until leftTicks.count) {
                            val value = leftTicks.valueAt(i)
                            val y = layout.yMaps[0].y(value)
                            val width = if (value == 0.0 && leftTicks.min < 0.0) zeroWidth else gridWidth
                            drawLine(colors.grid, Offset(layout.left, y), Offset(layout.right, y), width)
                        }
                        for (axis in layout.ticks.indices) {
                            val labels = layout.tickLabels[axis]
                            for (i in labels.indices) {
                                val label = labels[i]
                                val y = layout.yMaps[axis].y(layout.ticks[axis].valueAt(i)) - label.size.height / 2
                                val x = if (axis == 0) layout.left - gap - label.size.width else layout.right + gap
                                drawText(label, topLeft = Offset(x, y))
                            }
                            val unit = layout.unitLabels[axis] ?: continue
                            val x = if (axis == 0) layout.left - gap - unit.size.width else layout.right + gap
                            drawText(unit, topLeft = Offset(x, 0f))
                        }
                        for (i in layout.timeLabels.indices) {
                            drawText(layout.timeLabels[i], topLeft = Offset(layout.timeLabelLefts[i], layout.timeLabelTop))
                        }

                        val reveal = progress.value
                        val revealRight = layout.left + (layout.right - layout.left) * reveal
                        clipRect(left = layout.left, top = 0f, right = revealRight, bottom = size.height) {
                            for (i in drawings.lastIndex downTo 0) drawSeriesFill(drawings[i])
                            for (i in drawings.lastIndex downTo 0) drawSeriesLine(drawings[i], stroke, dotRadius, ring)
                        }

                        for (i in references.indices) {
                            val y = layout.referenceYs[i]
                            drawLine(colors.axisLabel, Offset(layout.left, y), Offset(layout.right, y), gridWidth, pathEffect = dash)
                            val label = layout.referenceLabels[i]
                            val above = y - gap / 2 - label.size.height
                            drawText(label, topLeft = Offset(layout.right - label.size.width, if (above >= layout.top) above else y + gap / 2))
                        }

                        if (markLatest && reveal >= 1f) {
                            for (i in series.indices) {
                                val stats = model.stats[i] ?: continue
                                val y = layout.yMaps[model.axisOf[i]].y(stats.latest)
                                drawRingedDot(drawings[i].colorAt(y), Offset(xMap.x(stats.latestTimeMs), y), dotRadius, ring)
                            }
                        }

                        val selected = selection.value ?: return@onDrawBehind
                        val cursorX = xMap.x(selected.timeMs)
                        drawLine(colors.axisLabel, Offset(cursorX, layout.top), Offset(cursorX, layout.bottom), gridWidth)
                        for (i in series.indices) {
                            val index = selected.indexOf(i)
                            if (index < 0) continue
                            val point = series[i].points[index]
                            val value = point.value ?: continue
                            val y = layout.yMaps[model.axisOf[i]].y(value)
                            drawRingedDot(drawings[i].colorAt(y), Offset(xMap.x(point.timeMs), y), cursorRadius, ring)
                        }
                    }
                },
        )
        Readout(selection, layout, series, timeFormatter, readoutGranularity(model.spanMs))
    }
}

/** Floating readout pinned to the plot top, beside the cursor (flips left near the right edge). */
@Composable
private fun Readout(
    selection: State<Selection?>,
    layout: PlotLayout,
    series: List<ChartSeries>,
    timeFormatter: TimeAxisFormatter,
    granularity: TimeGranularity,
) {
    val selected = selection.value ?: return
    val noValue = stringResource(R.string.component_no_value)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(placeable.width, placeable.height) {
                val current = selection.value ?: return@layout
                val cursor = layout.xMap.x(current.timeMs)
                val gap = ReadoutGap.toPx()
                val x = if (cursor + gap + placeable.width <= layout.right) cursor + gap else cursor - gap - placeable.width
                placeable.place(x.coerceAtLeast(0f).roundToInt(), layout.top.roundToInt())
            }
        },
    ) {
        Column(Modifier.padding(horizontal = MaterialTheme.spacing.xs, vertical = MaterialTheme.spacing.xxs)) {
            Text(
                timeFormatter.format(selected.timeMs, granularity),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            series.forEachIndexed { i, s ->
                val index = selected.indexOf(i)
                val value = if (index >= 0) s.points[index].value else null
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                ) {
                    if (series.size > 1) LegendKey(keyStyle(s.style, value))
                    Text(
                        value?.let(s.format::format) ?: noValue,
                        style = MaterialTheme.typography.numericBody,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** A signed series' key takes the color of the selected reading's direction. */
private fun keyStyle(style: SeriesStyle, value: Double?): SeriesStyle = when {
    style !is SeriesStyle.Signed || value == null -> style
    value >= 0 -> SeriesStyle.Solid(style.positive)
    else -> SeriesStyle.Solid(style.negative)
}

internal fun readoutGranularity(spanMs: Long): TimeGranularity = when {
    spanMs <= HOUR_MS -> TimeGranularity.SECONDS
    spanMs < 20 * HOUR_MS -> TimeGranularity.MINUTES
    spanMs <= 14 * ChartMath.DAY_MS -> TimeGranularity.DATE_TIME
    else -> TimeGranularity.DAYS
}

private fun summaryText(
    model: ChartModel,
    series: List<ChartSeries>,
    formatter: TimeAxisFormatter,
    seriesTemplate: String,
    rangeTemplate: String,
): String {
    val granularity = readoutGranularity(model.spanMs)
    val parts = mutableListOf(
        rangeTemplate.format(formatter.format(model.startMs, granularity), formatter.format(model.endMs, granularity)),
    )
    series.forEachIndexed { i, s ->
        val stats = model.stats[i] ?: return@forEachIndexed
        parts += seriesTemplate.format(s.label, s.format.format(stats.latest), s.format.format(stats.low), s.format.format(stats.high))
    }
    return parts.joinToString(" ")
}

/** Nearest reading per series at the scrub time; [timeMs] is where the cursor snaps. */
@Immutable
internal data class Selection(val timeMs: Long, val first: Int, val second: Int) {
    fun indexOf(series: Int): Int = if (series == 0) first else second
}

/** The scrub position (epoch ms, or [NONE]); only the draw phase and the readout read it. */
@Stable
internal class ScrubState(initialTimeMs: Long) {
    var timeMs by mutableLongStateOf(initialTimeMs)

    fun select(model: ChartModel, series: List<ChartSeries>): Selection? {
        val time = timeMs
        if (time == NONE || time < model.startMs || time > model.endMs) return null
        val first = series.getOrNull(0)?.let { ChartMath.nearestIndex(it.points, time, it.maxGapMs ?: Long.MAX_VALUE) } ?: -1
        val second = series.getOrNull(1)?.let { ChartMath.nearestIndex(it.points, time, it.maxGapMs ?: Long.MAX_VALUE) } ?: -1
        val snap = when {
            first >= 0 -> series[0].points[first].timeMs
            second >= 0 -> series[1].points[second].timeMs
            else -> return null
        }
        return Selection(snap, first, second)
    }
}
