package app.batstats.battery.drain

import android.content.Context
import app.batstats.R
import app.batstats.battery.measurement.ObservationSummary
import app.batstats.battery.measurement.ObservedBucket
import app.batstats.battery.measurement.PowerState
import java.text.DateFormat
import java.util.Date
import java.util.Calendar

/** Resource-backed observation text for the old monitoring screens; the notification uses [NotificationContent]. */
class MonitoringText(private val resolve: (Int, Array<out Any>) -> String) {
    constructor(context: Context) : this({ id, arguments -> context.getString(id, *arguments) })
    private fun text(id: Int, vararg arguments: Any) = resolve(id, arguments)
    fun state(power: PowerState) = text(when (power) {
        PowerState.CHARGING -> R.string.session_charging
        PowerState.DISCHARGING -> R.string.session_discharging
        PowerState.PLUGGED -> R.string.session_plugged
        PowerState.UNKNOWN -> R.string.session_unknown
    })
    fun bucket(bucket: ObservedBucket): String =
        "${formatDrainRate(bucket.rateMa)} · ${formatCharge(bucket.chargeMah)} · ${formatDuration(bucket.durationMs)}"
    fun coverage(bucket: ObservedBucket): String = when {
        bucket.durationMs == 0L -> text(R.string.monitor_no_period)
        bucket.chargeCoveredMs == 0L -> text(R.string.monitor_charge_unavailable)
        else -> text(R.string.monitor_coverage, formatDuration(bucket.chargeCoveredMs), formatDuration(bucket.durationMs))
    }
    fun since(summary: ObservationSummary): String {
        val start = summary.startedAt ?: return text(R.string.monitor_waiting_observation)
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        return text(R.string.monitor_since, format.format(Date(start))) +
            (summary.latest?.let { "\n" + text(R.string.monitor_through, format.format(Date(it.wallMs))) } ?: "")
    }
    fun window(summary: ObservationSummary): String {
        val start = summary.startedAt ?: return text(R.string.monitor_waiting_observation)
        val end = summary.latest?.wallMs ?: return since(summary)
        val first = Calendar.getInstance().apply { timeInMillis = start }
        val last = Calendar.getInstance().apply { timeInMillis = end }
        val sameDay = first.get(Calendar.ERA) == last.get(Calendar.ERA) &&
            first.get(Calendar.YEAR) == last.get(Calendar.YEAR) &&
            first.get(Calendar.DAY_OF_YEAR) == last.get(Calendar.DAY_OF_YEAR)
        val dateTime = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val endText = if (sameDay) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(end)) else dateTime.format(Date(end))
        return text(R.string.monitor_window_range, dateTime.format(Date(start)), endText)
    }
    fun cpuSuspend(summary: ObservationSummary): String = if (summary.cpuObservedMs > 0)
        text(R.string.monitor_cpu_coverage, formatDuration(summary.cpuSuspendMs), formatDuration(summary.cpuObservedMs))
        else text(R.string.monitor_no_interval)
    fun doze(summary: ObservationSummary): String = if (summary.cpuObservedMs > 0) formatDuration(summary.dozeMs)
        else text(R.string.monitor_no_interval)
}
