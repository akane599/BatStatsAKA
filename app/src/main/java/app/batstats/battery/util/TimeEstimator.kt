package app.batstats.battery.util

import android.content.Context
import app.batstats.R
import app.batstats.battery.measurement.EtaHold

object TimeEstimator {
    /**
     * The widgets' estimate: the held one ([EtaHold]), so the text doesn't drop to nothing between the raw capture
     * and the writer's copy of it. "Full in" while charging (status 2), "remaining" otherwise; null without one.
     */
    fun etaString(context: Context, reading: EtaHold.Reading): String? {
        val remaining = reading.remainingMs ?: return null
        val charging = reading.reading.sample?.status == 2
        return context.getString(if (charging) R.string.monitor_eta_full else R.string.monitor_eta_remaining, duration(remaining))
    }

    /** Rounded to 5 minutes, at least 1: "2h 5m", "40m". */
    fun duration(remainingMs: Long): String {
        val minutes = ((remainingMs / 60_000 + 2) / 5 * 5).coerceAtLeast(1)
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }
}
