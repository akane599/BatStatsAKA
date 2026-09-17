package app.batstats.battery.util

import app.batstats.battery.data.db.BatterySample

object TimeEstimator {
    /** Stored alongside its observation so widgets and screens use the same estimate. */
    fun etaString(sample: BatterySample?): String? {
        val remaining = sample?.etaMs ?: return null
        val minutes = ((remaining / 60_000 + 2) / 5 * 5).coerceAtLeast(1)
        val duration = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
        return if (sample.status == 2) "Est. full in $duration" else "Est. remaining: $duration"
    }
}
