package app.batstats.ui.screens.now

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import app.batstats.R
import app.batstats.ui.components.Panel
import app.batstats.ui.components.StatCell
import app.batstats.ui.components.chart.ChartDefaults
import app.batstats.ui.components.chart.ChartScrubState
import app.batstats.ui.components.chart.ChartSeries
import app.batstats.ui.components.chart.NumberFormatter
import app.batstats.ui.components.chart.TimeSeriesChart
import app.batstats.ui.components.chart.rememberChartScrubState
import app.batstats.ui.theme.batColors
import app.batstats.ui.theme.numericHeadline
import app.batstats.ui.theme.numericTitle
import app.batstats.ui.theme.spacing
import java.util.Locale
import kotlin.math.abs

private const val READOUTS = 4
private const val GRID_COLUMNS = 2

// Widest realistic values (StatCell sizing templates), so readouts keep their size while digits tick.
private const val CURRENT_TEMPLATE = 8_888.0
private const val POWER_TEMPLATE = 88.88
private const val CELSIUS_TEMPLATE = 88.8
private const val FAHRENHEIT_TEMPLATE = 188.8
private const val VOLTAGE_TEMPLATE = 8.88

/**
 * The memorable element: the current trace colored by energy direction (Live · 1h · 6h · 24h), then the four live
 * readouts. The scrub cursor clears when the range changes.
 */
@Composable
internal fun NowTracePanel(
    trace: TraceState,
    readouts: Readouts,
    useFahrenheit: Boolean,
    onSelectRange: (TraceRange) -> Unit,
    modifier: Modifier = Modifier,
    scrubState: ChartScrubState = rememberChartScrubState(),
) {
    LaunchedEffect(trace.range) { scrubState.clear() }
    Panel(modifier) {
        RangeSelector(trace.range, onSelectRange)
        TraceChart(trace, scrubState)
        ReadoutGrid(readouts, useFahrenheit, Modifier.padding(top = MaterialTheme.spacing.xs))
    }
}

@Composable
private fun TraceChart(trace: TraceState, scrubState: ChartScrubState) {
    val label = stringResource(R.string.now_trace_series)
    val unit = stringResource(R.string.now_unit_ma)
    val style = ChartDefaults.directionStyle()
    val series = remember(trace.points, trace.maxGapMs, label, unit, style) {
        listOf(ChartSeries(label, trace.points, style, unit = unit, format = NumberFormatter(unit, maxDecimals = 0), maxGapMs = trace.maxGapMs))
    }
    TimeSeriesChart(
        series = series,
        modifier = Modifier.fillMaxWidth(),
        window = trace.window,
        markLatest = true,
        scrubState = scrubState,
        emptyText = stringResource(if (trace.range == TraceRange.LIVE) R.string.now_trace_empty_live else R.string.now_trace_empty),
    )
}

/** Live · 1h · 6h · 24h as quiet tabs, the selected one on a raised pill; each option is a 48 dp target. */
@Composable
private fun RangeSelector(selected: TraceRange, onSelect: (TraceRange) -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.now_range_label)
    Row(
        modifier
            .fillMaxWidth()
            .selectableGroup()
            .semantics { contentDescription = description },
    ) {
        TraceRange.entries.forEach { range ->
            val isSelected = range == selected
            Box(
                Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .selectable(selected = isSelected, onClick = { onSelect(range) }, role = Role.Tab)
                    .padding(MaterialTheme.spacing.xxs),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                    )
                }
                Text(
                    stringResource(rangeLabel(range)),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun rangeLabel(range: TraceRange): Int = when (range) {
    TraceRange.LIVE -> R.string.now_range_live
    TraceRange.HOUR -> R.string.now_range_hour
    TraceRange.SIX_HOURS -> R.string.now_range_six_hours
    TraceRange.DAY -> R.string.now_range_day
}

/**
 * Current · Power · Temperature · Voltage: four across when every label and every widest value with its unit fit a
 * quarter on one line at full size (wide panes); otherwise a 2×2 grid with larger values (phones, large font). So a
 * label never ellipsizes, a unit never drops under its value, and all four values share one size.
 */
@Composable
internal fun ReadoutGrid(readouts: Readouts, useFahrenheit: Boolean, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    val noValue = stringResource(R.string.component_no_value)
    val current = readouts.currentMa
    val temperature = readouts.temperatureC?.let { if (useFahrenheit) it * 9 / 5 + 32 else it }
    val cells = listOf(
        Readout(
            stringResource(R.string.now_readout_current),
            current?.let { formatSigned(it, 0, locale) } ?: noValue,
            stringResource(R.string.now_unit_ma),
            MINUS + formatNumber(CURRENT_TEMPLATE, 0, locale),
            indicator = when {
                current == null || current == 0.0 -> MaterialTheme.colorScheme.onSurfaceVariant
                current > 0 -> MaterialTheme.batColors.charge
                else -> MaterialTheme.batColors.drain
            },
        ),
        Readout(
            stringResource(R.string.now_readout_power),
            readouts.powerW?.let { formatSigned(it, 2, locale) } ?: noValue,
            stringResource(R.string.now_unit_w),
            MINUS + formatNumber(POWER_TEMPLATE, 2, locale),
        ),
        Readout(
            stringResource(R.string.now_readout_temperature),
            temperature?.let { formatTemperature(it, locale) } ?: noValue,
            stringResource(if (useFahrenheit) R.string.now_unit_fahrenheit else R.string.now_unit_celsius),
            if (useFahrenheit) formatNumber(FAHRENHEIT_TEMPLATE, 1, locale) else MINUS + formatNumber(CELSIUS_TEMPLATE, 1, locale),
        ),
        Readout(
            stringResource(R.string.now_readout_voltage),
            readouts.voltageV?.let { formatNumber(it, 2, locale) } ?: noValue,
            stringResource(R.string.now_unit_v),
            formatNumber(VOLTAGE_TEMPLATE, 2, locale),
        ),
    )
    val spacing = MaterialTheme.spacing
    val labelStyle = MaterialTheme.typography.labelMedium
    val valueStyle = MaterialTheme.typography.numericTitle
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val quarter = (maxWidth - spacing.md * (READOUTS - 1)) / READOUTS
        val lines = cells.map { ReadoutLine(it.label, it.indicator != null, it.template, it.unit) }
        val fits = remember(lines, quarter, labelStyle, valueStyle, density) {
            val available = with(density) { quarter.toPx() }
            // StatCell's indicator dot and its gap (8 + 8 dp); the unit sits 4 dp after the value.
            val indicator = with(density) { (spacing.xs + spacing.xs).toPx() }
            val unitGap = with(density) { spacing.xxs.toPx() }
            val unitStyle = valueStyle.copy(fontSize = valueStyle.fontSize * UNIT_SCALE)
            lines.all { line ->
                val label = measurer.measure(line.label, labelStyle).size.width + if (line.dot) indicator else 0f
                val value = measurer.measure(line.template, valueStyle).size.width + unitGap +
                    measurer.measure(line.unit, unitStyle).size.width
                label <= available && value <= available
            }
        }
        if (fits) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                cells.forEach { it.Cell(MaterialTheme.typography.numericTitle, Modifier.weight(1f)) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                cells.chunked(GRID_COLUMNS).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                        row.forEach { it.Cell(MaterialTheme.typography.numericHeadline, Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

private fun formatTemperature(value: Double, locale: Locale): String =
    if (value < 0) MINUS + formatNumber(abs(value), 1, locale) else formatNumber(value, 1, locale)

private data class ReadoutLine(val label: String, val dot: Boolean, val template: String, val unit: String)

private class Readout(val label: String, val value: String, val unit: String, val template: String, val indicator: Color? = null) {
    @Composable
    fun Cell(style: TextStyle, modifier: Modifier) {
        StatCell(label, value, modifier, unit = unit, indicator = indicator, valueStyle = style, sizingTemplate = template)
    }
}
