package com.akane.voltwise.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Colors for Canvas charts, read through `MaterialTheme.chartColors`. The power trace is colored by energy
 * direction: [charge] where energy flows in, [drain] where it flows out. Every line color is ≥3:1 on every surface
 * tier in both the default and OLED schemes (`ThemeContrastTest`); fills and [grid] are decorative.
 */
@Immutable
data class ChartColors(
    /** Trace segments where energy flows in. */
    val charge: Color,
    /** Trace segments where energy flows out. */
    val drain: Color,
    /** Area under [charge] segments. */
    val chargeFill: Color,
    /** Area under [drain] segments. */
    val drainFill: Color,
    /** Temperature series. */
    val temperature: Color,
    /** Voltage series. */
    val voltage: Color,
    /** Battery level line drawn alongside the power trace (neutral, so direction colors stay dominant). */
    val level: Color,
    /** Second stacked layer next to [drain] (screen-off drain under screen-on drain in History bars). */
    val drainSecondary: Color,
    /** Gridlines and the zero baseline. */
    val grid: Color,
    /** Axis tick labels and legends (pair with `MaterialTheme.typography.numericLabel`). */
    val axisLabel: Color,
)

private const val FILL_ALPHA = 0.18f
private val LevelLine = Color(0xFFC9D1DC)
// Darker amber: ≥3.5:1 on every surface tier, ≥2.2:1 against [ChartColors.drain] so stacked layers separate.
private val DrainSecondary = Color(0xFFA8743A)

/** Builds the chart palette from the active scheme so grid/labels follow OLED surfaces. */
fun chartColors(scheme: ColorScheme, bat: BatColors = BatColors.Default): ChartColors = ChartColors(
    charge = bat.charge,
    drain = bat.drain,
    chargeFill = bat.charge.copy(alpha = FILL_ALPHA),
    drainFill = bat.drain.copy(alpha = FILL_ALPHA),
    temperature = bat.heat,
    voltage = bat.info,
    level = LevelLine,
    drainSecondary = DrainSecondary,
    grid = scheme.outlineVariant,
    axisLabel = scheme.onSurfaceVariant,
)
