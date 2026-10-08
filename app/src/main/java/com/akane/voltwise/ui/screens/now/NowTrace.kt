package com.akane.voltwise.ui.screens.now

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import com.akane.voltwise.R
import com.akane.voltwise.ui.components.Panel
import com.akane.voltwise.ui.components.SegmentedTabs
import com.akane.voltwise.ui.components.StatCell
import com.akane.voltwise.ui.components.statCellFitsInline
import com.akane.voltwise.ui.components.chart.ChartDefaults
import com.akane.voltwise.ui.components.chart.ChartScrubState
import com.akane.voltwise.ui.components.chart.ChartSeries
import com.akane.voltwise.ui.components.chart.MINUS_SIGN
import com.akane.voltwise.ui.components.chart.NumberFormatter
import com.akane.voltwise.ui.components.chart.TimeSeriesChart
import com.akane.voltwise.ui.components.chart.rememberChartScrubState
import com.akane.voltwise.ui.format.currentLocale
import com.akane.voltwise.ui.format.formatNumber
import com.akane.voltwise.ui.format.formatSigned
import com.akane.voltwise.ui.theme.batColors
import com.akane.voltwise.ui.theme.numericHeadline
import com.akane.voltwise.ui.theme.numericTitle
import com.akane.voltwise.ui.theme.spacing
import com.akane.voltwise.viewmodel.Readouts
import com.akane.voltwise.viewmodel.TraceRange
import com.akane.voltwise.viewmodel.TraceState

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

/** Live · 1h · 6h · 24h ([SegmentedTabs]). */
@Composable
private fun RangeSelector(selected: TraceRange, onSelect: (TraceRange) -> Unit, modifier: Modifier = Modifier) {
    val ranges = TraceRange.entries
    SegmentedTabs(
        labels = ranges.map { stringResource(rangeLabel(it)) },
        selectedIndex = ranges.indexOf(selected),
        onSelect = { onSelect(ranges[it]) },
        modifier = modifier,
        contentDescription = stringResource(R.string.now_range_label),
    )
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
            MINUS_SIGN + formatNumber(CURRENT_TEMPLATE, 0, locale),
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
            MINUS_SIGN + formatNumber(POWER_TEMPLATE, 2, locale),
        ),
        Readout(
            stringResource(R.string.now_readout_temperature),
            temperature?.let { formatNumber(it, 1, locale) } ?: noValue,
            stringResource(if (useFahrenheit) R.string.now_unit_fahrenheit else R.string.now_unit_celsius),
            if (useFahrenheit) formatNumber(FAHRENHEIT_TEMPLATE, 1, locale) else MINUS_SIGN + formatNumber(CELSIUS_TEMPLATE, 1, locale),
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
        val fits = remember(lines, quarter, labelStyle, valueStyle, density, spacing) {
            val available = with(density) { quarter.toPx() }
            // StatCell's own measure (dot, gaps, unit scale), so the grid choice follows the cell.
            lines.all { line ->
                statCellFitsInline(measurer, density, spacing, line.label, line.dot, line.template, line.unit, labelStyle, valueStyle, available)
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

private data class ReadoutLine(val label: String, val dot: Boolean, val template: String, val unit: String)

private class Readout(val label: String, val value: String, val unit: String, val template: String, val indicator: Color? = null) {
    @Composable
    fun Cell(style: TextStyle, modifier: Modifier) {
        StatCell(label, value, modifier, unit = unit, indicator = indicator, valueStyle = style, sizingTemplate = template)
    }
}
