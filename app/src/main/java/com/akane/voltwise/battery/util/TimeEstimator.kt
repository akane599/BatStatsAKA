package com.akane.voltwise.battery.util

import android.content.Context
import com.akane.voltwise.R
import com.akane.voltwise.battery.measurement.EtaHold
import com.akane.voltwise.ui.format.durationText
import com.akane.voltwise.ui.format.formatNumber
import java.util.Locale

object TimeEstimator {
    /**
     * The widgets' estimate: the held one ([EtaHold]), so the text doesn't drop to nothing between the raw capture
     * and the writer's copy of it. "Full in" while charging (status 2), "remaining" otherwise; null without one.
     */
    fun etaString(context: Context, reading: EtaHold.Reading, locale: Locale = Locale.getDefault()): String? {
        val remaining = reading.remainingMs ?: return null
        val charging = reading.reading.sample?.status == 2
        val duration = duration(remaining, locale) { id, args -> context.getString(id, *args) }
        return context.getString(if (charging) R.string.monitor_eta_full else R.string.monitor_eta_remaining, duration)
    }

    /**
     * Rounded to 5 minutes, at least 1, in the app's localized duration templates ([durationText]): "2 h 5 min",
     * "40 min". String resources go through [resolve], so JVM tests can supply the real templates.
     */
    fun duration(remainingMs: Long, locale: Locale, resolve: (Int, Array<out Any>) -> String): String {
        val minutes = ((remainingMs / 60_000 + 2) / 5 * 5).coerceAtLeast(1)
        val text = durationText(minutes * 60_000)
        return resolve(text.template, text.numbers.map { formatNumber(it.toDouble(), 0, locale) }.toTypedArray())
    }
}
