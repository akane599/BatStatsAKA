package app.batstats.battery.drain

import app.batstats.battery.measurement.ObservationSummary
import java.util.Locale

typealias DrainState = ObservationSummary

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
/** Two significant digits for small rates avoid turning nonzero drain into a displayed zero. */
fun formatDrainRate(rate: Double?): String {
    val value = rate?.takeIf(Double::isFinite) ?: return "—"
    val pattern = if (kotlin.math.abs(value) in 0.0..<1.0 && value != 0.0) "%.2g mA" else "%.0f mA"
    return String.format(Locale.getDefault(), pattern, if (value == 0.0) 0.0 else value)
}
fun formatCharge(mah: Double?): String {
    val value = mah?.takeIf(Double::isFinite) ?: return "—"
    val pattern = if (kotlin.math.abs(value) in 0.0..<0.1 && value != 0.0) "%.1g mAh" else "%.1f mAh"
    return String.format(Locale.getDefault(), pattern, if (value == 0.0) 0.0 else value)
}
