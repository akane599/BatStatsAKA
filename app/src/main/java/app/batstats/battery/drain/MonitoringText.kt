package app.batstats.battery.drain

import android.content.Context
import app.batstats.R
import app.batstats.battery.measurement.ObservationSummary
import app.batstats.battery.measurement.ObservedBucket
import app.batstats.battery.measurement.PowerState
import java.text.DateFormat
import java.util.Date

/** One resource-backed presentation shared by monitoring screens and notification. */
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
    fun cpuSuspend(summary: ObservationSummary): String = if (summary.cpuObservedMs > 0)
        text(R.string.monitor_cpu_coverage, formatDuration(summary.cpuSuspendMs), formatDuration(summary.cpuObservedMs))
        else text(R.string.monitor_no_interval)
    fun doze(summary: ObservationSummary): String = if (summary.cpuObservedMs > 0) formatDuration(summary.dozeMs)
        else text(R.string.monitor_no_interval)
    fun expanded(summary: ObservationSummary): String = buildString {
        appendLine("━━ ${text(R.string.monitor_drain_heading)} ━━")
        appendLine(text(R.string.monitor_screen_on, bucket(summary.screenOn)))
        appendLine(coverage(summary.screenOn))
        appendLine(text(R.string.monitor_screen_off, bucket(summary.screenOff)))
        appendLine(coverage(summary.screenOff))
        appendLine()
        appendLine("━━ ${text(R.string.monitor_activity_heading)} ━━")
        appendLine(text(R.string.monitor_cpu, cpuSuspend(summary)))
        appendLine(text(R.string.monitor_doze, doze(summary)))
        appendLine(text(R.string.monitor_charging, formatDuration(summary.chargingMs), formatCharge(summary.charging.chargeMah)))
        appendLine()
        appendLine("━━ ${text(R.string.monitor_window_heading)} ━━")
        appendLine(since(summary))
        appendLine(text(R.string.monitor_discharge, bucket(summary.discharge)))
        appendLine(coverage(summary.discharge))
        if (summary.gaps > 0) appendLine(text(R.string.monitor_gaps_excluded, summary.gaps))
        append(text(R.string.monitor_aod_brief))
    }
}
