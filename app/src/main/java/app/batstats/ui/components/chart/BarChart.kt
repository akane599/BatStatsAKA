package app.batstats.ui.components.chart

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.batstats.R
import app.batstats.ui.theme.chartColors
import app.batstats.ui.theme.numericLabel
import app.batstats.ui.theme.spacing
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private val MaxBarWidth = 24.dp
private val BarRadius = 4.dp
private val SegmentGap = 2.dp
private val BarGridWidth = 1.dp
private val BarLabelGap = 6.dp
private val BreakdownHeight = 12.dp
private const val BAR_FILL_RATIO = 0.6f
private const val DIM_ALPHA = 0.38f
private const val MIN_BAR_TICKS = 3
private const val MAX_BAR_TICKS = 5
private const val BAR_TICK_SPACING = 1.8f

/**
 * One bar: [values] stack bottom-up in [BarSegment] order (negative and non-finite values count as 0). [shortLabel]
 * ("T", "7") replaces [label] under every bar when the full labels don't fit; [label] is always the spoken name.
 */
@Immutable
data class BarEntry(val label: String, val values: List<Double>, val shortLabel: String? = null)

/** Which category labels a [BarChart] draws ([shown], by bar index) and whether in their short form. */
@Immutable
internal data class BarLabelPlan(val short: Boolean, val shown: Set<Int>)

/**
 * The category-label rule: every bar keeps its full label when they all fit their slots ([slot] wide, [gap] apart);
 * else every bar gets its short label when those fit; else every Nth label is drawn (N = the smallest stride at
 * which the narrower form fits), counted from the newest bar, with the first bar taking the place of the label
 * nearest to it, so the newest and the oldest are always labelled. A [selected] bar is always labelled; labels
 * closer than the stride yield to it. Deterministic: labels never drop by collision.
 */
internal fun barLabelPlan(fullWidths: List<Float>, shortWidths: List<Float>?, slot: Float, gap: Float, selected: Int?): BarLabelPlan {
    val count = fullWidths.size
    if (count == 0) return BarLabelPlan(short = false, shown = emptySet())
    val all = (0 until count).toSet()
    val widestFull = fullWidths.max() + gap
    if (widestFull <= slot) return BarLabelPlan(short = false, shown = all)
    val widestShort = shortWidths?.takeIf { it.size == count }?.let { it.max() + gap }
    if (widestShort != null && widestShort <= slot) return BarLabelPlan(short = true, shown = all)
    val short = widestShort != null && widestShort < widestFull
    val stride = if (slot > 0f) max(1, ceil((if (short) widestShort ?: widestFull else widestFull) / slot).toInt()) else count
    val shown = (0 until count).filter { (count - 1 - it) % stride == 0 }.toMutableSet()
    if (0 !in shown) {
        shown.removeAll { it < stride }
        shown += 0
    }
    if (selected != null && selected in 0 until count && selected !in shown) {
        shown.removeAll { abs(it - selected) < stride }
        shown += selected
    }
    return BarLabelPlan(short, shown)
}

/** A stack layer's legend name and color (e.g. "Screen on" / "Screen off"). */
@Immutable
data class BarSegment(val label: String, val color: Color)

/** One part of a [BreakdownBar]. */
@Immutable
data class BreakdownSegment(val label: String, val value: Double, val color: Color)

/** Default sizes for [BarChart]. */
object BarChartDefaults {
    /** Plot plus axis labels; the legend row sits above and is extra. */
    val Height: Dp = 160.dp
}

/**
 * Stacked bars over categories (e.g. one per day: screen-on vs screen-off drain), on a value axis from zero.
 * Bars are at most 24 dp wide with a 4 dp rounded top and a 2 dp gap between layers. Category labels follow
 * [barLabelPlan]: full, else short ([BarEntry.shortLabel]), else every Nth with the newest, oldest and selected bars
 * always labelled. A legend row appears for two or more [segments].
 *
 * Selection is optional: pass [onSelect] to make bars tappable (the whole slot is the target; tapping the selected
 * bar clears it) and to add one TalkBack action per bar ("Select Oct 9") plus "Clear selection". With a
 * [selectedIndex] the other bars dim, the selected total is labelled on its cap and announced as the state.
 *
 * @param entries the bars, oldest first (the newest keeps its label when labels thin out).
 * @param unit caption above the value axis.
 * @param format formats the spoken summary (the highest and the latest total).
 * @param selectedFormat formats the selected total on its cap and in its announcement; [format] by default.
 * @param contentDescription overrides the default summary (highest and latest total).
 */
@Composable
fun BarChart(
    entries: List<BarEntry>,
    segments: List<BarSegment>,
    modifier: Modifier = Modifier,
    chartHeight: Dp = BarChartDefaults.Height,
    unit: String = "",
    format: ValueFormatter = NumberFormatter(unit),
    selectedFormat: ValueFormatter = format,
    selectedIndex: Int? = null,
    onSelect: ((Int?) -> Unit)? = null,
    emptyText: String = stringResource(R.string.component_chart_empty),
    contentDescription: String? = null,
) {
    // Keep one instance while the content is equal, so the draw cache survives unrelated recompositions.
    val bars = remember(entries) { entries }
    val layers = remember(segments) { segments }
    val totals = remember(bars) { bars.map { entry -> entry.values.sumOf(::barValue) } }
    val hasData = totals.any { it > 0.0 }
    val template = stringResource(R.string.component_bar_summary)
    val selectTemplate = stringResource(R.string.component_bar_select)
    val clearLabel = stringResource(R.string.component_bar_clear)
    val selectedTemplate = stringResource(R.string.component_bar_selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val selected = selectedIndex?.takeIf { it in bars.indices }
    val selectedText = selected?.let { selectedTemplate.format(bars[it].label, selectedFormat.format(totals[it])) }
    // One action per bar (plus "Clear selection"): TalkBack users select bars without aiming at them.
    val actions = remember(bars, onSelect != null, selected != null, selectTemplate, clearLabel) {
        if (onSelect == null) {
            emptyList()
        } else {
            bars.mapIndexed { i, bar ->
                CustomAccessibilityAction(selectTemplate.format(bar.label)) {
                    currentOnSelect?.invoke(i)
                    true
                }
            } + listOfNotNull(
                if (selected != null) CustomAccessibilityAction(clearLabel) { currentOnSelect?.invoke(null); true } else null,
            )
        }
    }
    val summary = contentDescription ?: remember(bars, totals, format, template, emptyText) {
        barSummary(bars.map { it.label }, totals, format, template, emptyText)
    }
    Column(
        modifier.fillMaxWidth().clearAndSetSemantics {
            this.contentDescription = summary
            if (selectedText != null) stateDescription = selectedText
            if (actions.isNotEmpty()) customActions = actions
        },
    ) {
        if (layers.size > 1) {
            Row(
                Modifier.padding(bottom = MaterialTheme.spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
            ) {
                layers.forEach { segment -> LegendItem(segment.label, key = { LegendSwatch(segment.color) }) }
            }
        }
        Box(Modifier.fillMaxWidth().height(chartHeight)) {
            if (hasData) {
                Bars(bars, layers, totals, unit, selectedFormat, selectedIndex, onSelect)
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

/** Plot geometry the tap handler needs; written by the draw cache. */
private class BarHitArea {
    var left = 0f
    var slot = 1f
}

@Composable
private fun Bars(
    entries: List<BarEntry>,
    segments: List<BarSegment>,
    totals: List<Double>,
    unit: String,
    format: ValueFormatter,
    selectedIndex: Int?,
    onSelect: ((Int?) -> Unit)?,
) {
    val colors = MaterialTheme.chartColors
    val labelStyle = MaterialTheme.typography.numericLabel.copy(color = colors.axisLabel)
    val capStyle = MaterialTheme.typography.numericLabel.copy(color = MaterialTheme.colorScheme.onSurface)
    val textMeasurer = rememberTextMeasurer(cacheSize = CHART_TEXT_CACHE_SIZE)
    val hit = remember { BarHitArea() }
    val currentSelected by rememberUpdatedState(selectedIndex)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val selectable = onSelect != null
    val tap = if (selectable) {
        Modifier.pointerInput(entries.size) {
            detectTapGestures { offset ->
                if (hit.slot <= 0f || offset.x < hit.left) return@detectTapGestures
                val index = ((offset.x - hit.left) / hit.slot).toInt()
                if (index in entries.indices) {
                    currentOnSelect?.invoke(if (index == currentSelected) null else index)
                }
            }
        }
    } else {
        Modifier
    }
    Spacer(
        Modifier
            .fillMaxSize()
            .then(tap)
            .drawWithCache {
                val gap = BarLabelGap.toPx()
                val labelHeight = textMeasurer.measure("0", labelStyle).size.height.toFloat()
                val unitLabel = unit.takeIf(String::isNotEmpty)?.let { textMeasurer.measure(it, labelStyle) }
                val capRoom = if (selectable || selectedIndex != null) labelHeight + gap / 2 else 0f
                // The cap label sits over the plot and the unit caption over the gutter, so their rows can overlap.
                val top = max((if (unitLabel != null) labelHeight + gap / 2 else 0f) + labelHeight / 2, capRoom)
                val xLabelTop = size.height - labelHeight
                val bottom = xLabelTop - max(gap, labelHeight / 2 + BarGridWidth.toPx())
                val maxTicks = ((bottom - top) / (labelHeight * BAR_TICK_SPACING)).toInt().coerceIn(MIN_BAR_TICKS, MAX_BAR_TICKS)
                val ticks = ChartMath.niceTicks(0.0, totals.max(), maxTicks)
                val numberFormat = NumberFormat.getNumberInstance().apply {
                    minimumFractionDigits = ticks.decimals
                    maximumFractionDigits = ticks.decimals
                }
                val tickLabels = List(ticks.count) { textMeasurer.measure(numberFormat.formatWithMinus(ticks.valueAt(it)), labelStyle) }
                val plotLeft = max(tickLabels.maxOf { it.size.width }, unitLabel?.size?.width ?: 0) + gap
                val plotWidth = size.width - plotLeft
                if (plotWidth <= 0f || bottom <= top) {
                    // Too small for a plot (e.g. mid-animation): draw nothing rather than inverted geometry.
                    hit.slot = 0f
                    return@drawWithCache onDrawBehind { }
                }
                val yMap = YAxisMap(top, bottom, ticks.min, ticks.max)
                val slot = plotWidth / entries.size
                val barWidth = min(MaxBarWidth.toPx(), slot * BAR_FILL_RATIO)
                val radius = min(BarRadius.toPx(), barWidth / 2)
                val segmentGap = SegmentGap.toPx()
                hit.left = plotLeft
                hit.slot = slot

                // One path per visible layer of each bar; only the top layer gets rounded corners.
                val barPaths = ArrayList<Path>()
                val barColors = ArrayList<Color>()
                val barOwners = ArrayList<Int>()
                entries.forEachIndexed { bar, entry ->
                    val left = plotLeft + slot * bar + (slot - barWidth) / 2
                    val topLayer = entry.values.indices.lastOrNull { barValue(entry.values[it]) > 0.0 } ?: return@forEachIndexed
                    var base = bottom
                    var running = 0.0
                    for (layer in 0..topLayer) {
                        val value = barValue(entry.values[layer])
                        if (value <= 0.0) continue
                        running += value
                        val layerTop = yMap.y(running)
                        val layerBottom = if (base < bottom) base - segmentGap else base
                        if (layerBottom - layerTop > 0f) {
                            val r = if (layer == topLayer) min(radius, layerBottom - layerTop) else 0f
                            barPaths += Path().apply {
                                addRoundRect(
                                    RoundRect(
                                        left = left,
                                        top = layerTop,
                                        right = left + barWidth,
                                        bottom = layerBottom,
                                        topLeftCornerRadius = CornerRadius(r),
                                        topRightCornerRadius = CornerRadius(r),
                                        bottomRightCornerRadius = CornerRadius.Zero,
                                        bottomLeftCornerRadius = CornerRadius.Zero,
                                    ),
                                )
                            }
                            barColors += segments.getOrNull(layer)?.color ?: colors.axisLabel
                            barOwners += bar
                        }
                        base = layerTop
                    }
                }

                val selected = selectedIndex?.takeIf { it in entries.indices }
                val fullLabels = entries.map { textMeasurer.measure(it.label, labelStyle) }
                val shortLabels = if (entries.all { it.shortLabel != null }) {
                    entries.map { textMeasurer.measure(it.shortLabel.orEmpty(), labelStyle) }
                } else {
                    null
                }
                val plan = barLabelPlan(
                    fullLabels.map { it.size.width.toFloat() },
                    shortLabels?.map { it.size.width.toFloat() },
                    slot,
                    gap,
                    selected,
                )
                val categoryLabels = if (plan.short && shortLabels != null) shortLabels else fullLabels
                val capLabel = selected?.let { textMeasurer.measure(format.format(totals[it]), capStyle) }
                val capOffset = selected?.let { index ->
                    val label = capLabel ?: return@let Offset.Zero
                    val center = plotLeft + slot * (index + 0.5f)
                    val x = (center - label.size.width / 2).coerceIn(0f, max(0f, size.width - label.size.width))
                    Offset(x, yMap.y(totals[index]) - gap / 2 - label.size.height)
                } ?: Offset.Zero
                val gridWidth = BarGridWidth.toPx()

                onDrawBehind {
                    for (i in 0 until ticks.count) {
                        val y = yMap.y(ticks.valueAt(i))
                        drawLine(colors.grid, Offset(plotLeft, y), Offset(size.width, y), gridWidth)
                        val label = tickLabels[i]
                        drawText(label, topLeft = Offset(plotLeft - gap - label.size.width, y - label.size.height / 2))
                    }
                    unitLabel?.let { drawText(it, topLeft = Offset(plotLeft - gap - it.size.width, 0f)) }
                    for (i in barPaths.indices) {
                        val alpha = if (selected == null || barOwners[i] == selected) 1f else DIM_ALPHA
                        drawPath(barPaths[i], barColors[i], alpha = alpha)
                    }
                    for (i in categoryLabels.indices) {
                        if (i !in plan.shown) continue
                        val label = categoryLabels[i]
                        val center = plotLeft + slot * (i + 0.5f)
                        val x = (center - label.size.width / 2).coerceIn(0f, max(0f, size.width - label.size.width))
                        drawText(label, topLeft = Offset(x, xLabelTop))
                    }
                    capLabel?.let { drawText(it, topLeft = capOffset) }
                }
            },
    )
}

/**
 * One horizontal bar split into proportional parts (e.g. an app's foreground / background / cached time), with a
 * legend of label and formatted value per part. The spoken description lists every part. A part with a non-finite
 * value (unknown) is left out; a negative one counts as 0.
 *
 * @param emptyText the spoken description when no part is left (the bar shows its empty track).
 */
@Composable
fun BreakdownBar(
    segments: List<BreakdownSegment>,
    modifier: Modifier = Modifier,
    format: ValueFormatter = NumberFormatter(),
    showLegend: Boolean = true,
    emptyText: String = stringResource(R.string.component_chart_empty),
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val itemTemplate = stringResource(R.string.component_breakdown_item)
    val parts = remember(segments) { listedParts(segments) }
    val summary = remember(parts, format, itemTemplate, emptyText) { breakdownSummary(parts, format, itemTemplate, emptyText) }
    Column(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = summary },
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(BreakdownHeight)
                .clip(MaterialTheme.shapes.extraSmall)
                .drawWithCache {
                    val drawn = parts.filter { it.value > 0.0 }
                    val total = drawn.sumOf { it.value }
                    val gap = SegmentGap.toPx()
                    val usable = max(0f, size.width - gap * (drawn.size - 1).coerceAtLeast(0))
                    val lefts = FloatArray(drawn.size)
                    val widths = FloatArray(drawn.size)
                    var x = 0f
                    drawn.forEachIndexed { i, part ->
                        lefts[i] = x
                        widths[i] = (part.value / total * usable).toFloat()
                        x += widths[i] + gap
                    }
                    onDrawBehind {
                        if (drawn.isEmpty()) drawRect(track)
                        // Parts run from the start edge, like the legend: mirrored in RTL.
                        val rtl = layoutDirection == LayoutDirection.Rtl
                        for (i in drawn.indices) {
                            val left = if (rtl) size.width - lefts[i] - widths[i] else lefts[i]
                            drawRect(drawn[i].color, Offset(left, 0f), Size(widths[i], size.height))
                        }
                    }
                },
        )
        if (showLegend) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
            ) {
                parts.forEach { segment ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                    ) {
                        LegendItem(segment.label, key = { LegendSwatch(segment.color) })
                        Text(
                            format.format(segment.value),
                            style = MaterialTheme.typography.numericLabel,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

/** A [BarChart]'s spoken summary: the highest and the latest total ([totals] as drawn), or [emptyText] with none above 0. */
internal fun barSummary(labels: List<String>, totals: List<Double>, format: ValueFormatter, template: String, emptyText: String): String {
    if (totals.none { it > 0.0 }) return emptyText
    val highest = totals.indices.maxBy { totals[it] }
    return template.format(labels[highest], format.format(totals[highest]), labels.last(), format.format(totals.last()))
}

/** A stack value as drawn: negative, NaN and infinite values count as 0. */
private fun barValue(value: Double): Double = if (value.isFinite() && value > 0.0) value else 0.0

/** The parts a [BreakdownBar] shows and speaks: non-finite (unknown) values left out, negatives as 0. */
internal fun listedParts(segments: List<BreakdownSegment>): List<BreakdownSegment> =
    segments.filter { it.value.isFinite() }.map { if (it.value < 0.0) it.copy(value = 0.0) else it }

/** "Label: value" for every listed part, or [emptyText] when there is none. */
internal fun breakdownSummary(
    parts: List<BreakdownSegment>,
    format: ValueFormatter,
    itemTemplate: String,
    emptyText: String,
): String = if (parts.isEmpty()) {
    emptyText
} else {
    parts.joinToString(", ") { itemTemplate.format(it.label, format.format(it.value)) }
}
