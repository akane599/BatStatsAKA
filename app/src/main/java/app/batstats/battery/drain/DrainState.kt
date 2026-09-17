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
fun formatDrainRate(rate: Double?): String = rate?.let { String.format(Locale.getDefault(), "%.0f mA", it) } ?: "—"
fun formatCharge(mah: Double?): String = mah?.let { String.format(Locale.getDefault(), "%.1f mAh", it) } ?: "—"
