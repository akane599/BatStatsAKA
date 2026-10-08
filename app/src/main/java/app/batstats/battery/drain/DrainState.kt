package app.batstats.battery.drain

import app.batstats.ui.format.formatRate
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** Shown for a value that is missing or not finite. */
const val NO_VALUE = "—"

/** The typographic minus, as in the app's charts; `ui.components.chart.MINUS_SIGN` is the same character. */
const val MINUS_SIGN = "−"

/**
 * A formatted reading: its number and unit kept apart, so a view can draw the unit smaller (as Now's StatCell
 * does). [toString] is the plain "number unit" text.
 */
data class Quantity(val number: String, val unit: String? = null) {
    override fun toString() = if (unit == null) number else "$number $unit"

    companion object {
        val NONE = Quantity(NO_VALUE)
    }
}

fun formatDuration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        hours >= 24 -> "${hours / 24}d ${hours % 24}h"
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

private fun numberFormat(decimals: Int, locale: Locale, grouping: Boolean = true) = NumberFormat.getNumberInstance(locale).apply {
    minimumFractionDigits = decimals
    maximumFractionDigits = decimals
    isGroupingUsed = grouping
}

private fun NumberFormat.formatWithMinus(value: Double): String {
    val magnitude = format(abs(value))
    return if (value < 0 && magnitude != format(0.0)) MINUS_SIGN + magnitude else magnitude
}

/** Grouped, exactly [decimals] fraction digits, [MINUS_SIGN] for negatives; a value that rounds to zero has no sign. */
fun formatNumber(value: Double, decimals: Int, locale: Locale = Locale.getDefault(), grouping: Boolean = true): String =
    numberFormat(decimals, locale, grouping).formatWithMinus(value)

/** "+1,240" into the battery, "−612" out of it, a bare "0" when it rounds to zero. */
fun formatSigned(value: Double, decimals: Int, locale: Locale = Locale.getDefault()): String {
    val format = numberFormat(decimals, locale)
    val text = format.formatWithMinus(value)
    return if (value > 0 && text != format.format(0.0)) "+$text" else text
}

private fun Double?.finite(): Double? = this?.takeIf(Double::isFinite)

/** Three significant digits for a value shown in the larger unit (A, Ah): "0.612", "1.24", "12.3", "123". */
private fun significantDecimals(value: Double) = when {
    abs(value) < 1 -> 3
    abs(value) < 10 -> 2
    abs(value) < 100 -> 1
    else -> 0
}

/** Two significant digits for small values avoid turning a nonzero reading into a displayed zero. */
private fun smallOrGrouped(value: Double, smallBelow: Double, pattern: String, decimals: Int, locale: Locale): String =
    if (abs(value) < smallBelow && value != 0.0) {
        String.format(locale, pattern, abs(value)).let { if (value < 0) MINUS_SIGN + it else it }
    } else formatNumber(value, decimals, locale)

fun drainRateQuantity(rate: Double?, locale: Locale = Locale.getDefault()): Quantity? =
    rate.finite()?.let { Quantity(smallOrGrouped(it, 1.0, "%.2g", 0, locale), "mA") }

/** A drain as a share of the full charge per hour, in the app's rate format (Now's DrainCell): "10.5 %/h", "0.95 %/h". */
fun percentPerHourQuantity(perHour: Double?, locale: Locale = Locale.getDefault()): Quantity? =
    perHour.finite()?.let { Quantity(formatRate(it, locale), "%/h") }

fun chargeQuantity(mah: Double?, locale: Locale = Locale.getDefault()): Quantity? =
    mah.finite()?.let { Quantity(smallOrGrouped(it, 0.1, "%.1g", 1, locale), "mAh") }

/** Calibrated current, signed as Android defines it: "+1,240" charging, "−612" draining (mA). */
fun currentQuantity(ma: Double?, locale: Locale = Locale.getDefault()): Quantity? =
    ma.finite()?.let { Quantity(formatSigned(it, 0, locale), "mA") }

/** The same current in amps, three significant digits: "+1.24 A". */
fun currentAmpsQuantity(ma: Double?, locale: Locale = Locale.getDefault()): Quantity? =
    ma.finite()?.let { it / 1_000 }?.let { Quantity(formatSigned(it, significantDecimals(it), locale), "A") }

/** A drain rate in amps (unsigned), three significant digits: "0.42 A". */
fun drainAmpsQuantity(ma: Double?, locale: Locale = Locale.getDefault()): Quantity? =
    ma.finite()?.let { it / 1_000 }?.let { Quantity(formatNumber(it, significantDecimals(it), locale), "A") }

/** A session's charge: "412 mAh" (one decimal below 10 mAh), or in Ah, three significant digits: "1.23 Ah". */
fun sessionChargeQuantity(mah: Double?, locale: Locale = Locale.getDefault()): Quantity? =
    mah.finite()?.let { Quantity(formatNumber(it, if (abs(it) < 10) 1 else 0, locale), "mAh") }

fun sessionChargeAhQuantity(mah: Double?, locale: Locale = Locale.getDefault()): Quantity? =
    mah.finite()?.let { it / 1_000 }?.let { Quantity(formatNumber(it, significantDecimals(it), locale), "Ah") }

/** Power magnitude (the current carries the direction): "2.4", "0.35" below 1 W. */
fun powerQuantity(mw: Double?, locale: Locale = Locale.getDefault()): Quantity? =
    mw.finite()?.let { abs(it) / 1_000 }?.let { Quantity(formatNumber(it, if (it < 1) 2 else 1, locale), "W") }

fun temperatureQuantity(celsius: Double?, fahrenheit: Boolean, locale: Locale = Locale.getDefault()): Quantity? =
    celsius.finite()?.let {
        if (fahrenheit) Quantity(formatNumber(it * 1.8 + 32, 1, locale), "°F") else Quantity(formatNumber(it, 1, locale), "°C")
    }

fun voltageQuantity(mv: Int?, locale: Locale = Locale.getDefault()): Quantity? =
    mv?.let { Quantity(formatNumber(it / 1_000.0, 2, locale), "V") }

fun formatDrainRate(rate: Double?, locale: Locale = Locale.getDefault()): String = drainRateQuantity(rate, locale)?.toString() ?: NO_VALUE
fun formatCharge(mah: Double?, locale: Locale = Locale.getDefault()): String = chargeQuantity(mah, locale)?.toString() ?: NO_VALUE
fun formatCurrent(ma: Double?, locale: Locale = Locale.getDefault()): String = currentQuantity(ma, locale)?.toString() ?: NO_VALUE
fun formatPower(mw: Double?, locale: Locale = Locale.getDefault()): String = powerQuantity(mw, locale)?.toString() ?: NO_VALUE
fun formatVoltage(mv: Int?, locale: Locale = Locale.getDefault()): String = voltageQuantity(mv, locale)?.toString() ?: NO_VALUE
fun formatTemperature(celsius: Double?, fahrenheit: Boolean, locale: Locale = Locale.getDefault()): String =
    temperatureQuantity(celsius, fahrenheit, locale)?.toString() ?: NO_VALUE
