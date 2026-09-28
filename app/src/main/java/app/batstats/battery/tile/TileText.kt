package app.batstats.battery.tile

import java.text.NumberFormat
import java.util.Locale

/**
 * Pure text for [MonitorTileService]'s subtitle (API 29+) or whole label (older versions): calibrated
 * current and power while monitoring, e.g. "−612 mA · 2.4 W" (discharging) or "+850 mA · 3.3 W"
 * (charging). Kept free of Android framework types so it can be unit tested directly.
 */
internal object TileText {
    private const val MINUS_SIGN = "−"
    private const val SEPARATOR = " · "

    /**
     * [offText] while monitoring is off; [noReadingText] while monitoring but no capture has landed
     * yet ([currentMa]/[powerMw] null); otherwise the signed current (whole mA) and the unsigned
     * power magnitude (one decimal, in W from [powerMw]).
     */
    fun of(
        isMonitoring: Boolean,
        currentMa: Double?,
        powerMw: Double?,
        offText: String,
        noReadingText: String,
        unitMa: String,
        unitW: String,
        locale: Locale = Locale.getDefault(),
    ): String = when {
        !isMonitoring -> offText
        currentMa == null || powerMw == null -> noReadingText
        else -> formatSigned(currentMa, 0, locale) + " " + unitMa + SEPARATOR +
            formatMagnitude(powerMw / 1000.0, 1, locale) + " " + unitW
    }

    private fun numberFormat(decimals: Int, locale: Locale): NumberFormat = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = decimals
        maximumFractionDigits = decimals
    }

    /** "+850", "−612", a bare "0" when it rounds to zero. */
    private fun formatSigned(value: Double, decimals: Int, locale: Locale): String {
        val format = numberFormat(decimals, locale)
        val magnitude = format.format(kotlin.math.abs(value))
        val zero = format.format(0.0)
        return when {
            magnitude == zero -> zero
            value < 0 -> MINUS_SIGN + magnitude
            else -> "+$magnitude"
        }
    }

    /** Always non-negative: direction is already carried by the current figure. */
    private fun formatMagnitude(value: Double, decimals: Int, locale: Locale): String =
        numberFormat(decimals, locale).format(kotlin.math.abs(value))
}
