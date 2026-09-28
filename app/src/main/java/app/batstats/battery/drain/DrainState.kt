package app.batstats.battery.drain

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** Shown for a value that is missing or not finite. */
const val NO_VALUE = "—"

/** The typographic minus, as in the app's charts; `ui.components.chart.MINUS_SIGN` is the same character. */
const val MINUS_SIGN = "−"

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

/** Two significant digits for small rates avoid turning nonzero drain into a displayed zero. */
fun formatDrainRate(rate: Double?, locale: Locale = Locale.getDefault()): String {
    val value = rate.finite() ?: return NO_VALUE
    val number = if (abs(value) < 1.0 && value != 0.0) {
        String.format(locale, "%.2g", abs(value)).let { if (value < 0) MINUS_SIGN + it else it }
    } else formatNumber(value, 0, locale)
    return "$number mA"
}

fun formatCharge(mah: Double?, locale: Locale = Locale.getDefault()): String {
    val value = mah.finite() ?: return NO_VALUE
    val number = if (abs(value) < 0.1 && value != 0.0) {
        String.format(locale, "%.1g", abs(value)).let { if (value < 0) MINUS_SIGN + it else it }
    } else formatNumber(value, 1, locale)
    return "$number mAh"
}

/** Calibrated current, signed as Android defines it: "+1,240 mA" charging, "−612 mA" draining. */
fun formatCurrent(ma: Double?, locale: Locale = Locale.getDefault()): String =
    ma.finite()?.let { "${formatSigned(it, 0, locale)} mA" } ?: NO_VALUE

/** Power magnitude (the current carries the direction): "2.4 W", "0.35 W" below 1 W. */
fun formatPower(mw: Double?, locale: Locale = Locale.getDefault()): String {
    val watts = mw.finite()?.let { abs(it) / 1_000 } ?: return NO_VALUE
    return "${formatNumber(watts, if (watts < 1) 2 else 1, locale)} W"
}

fun formatTemperature(celsius: Double?, fahrenheit: Boolean, locale: Locale = Locale.getDefault()): String {
    val value = celsius.finite() ?: return NO_VALUE
    return if (fahrenheit) "${formatNumber(value * 1.8 + 32, 1, locale)} °F" else "${formatNumber(value, 1, locale)} °C"
}

fun formatVoltage(mv: Int?, locale: Locale = Locale.getDefault()): String =
    mv?.let { "${formatNumber(it / 1_000.0, 2, locale)} V" } ?: NO_VALUE
