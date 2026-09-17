package app.batstats.battery.drain

import app.batstats.battery.measurement.ObservationSummary
import app.batstats.battery.measurement.ObservedBucket
import app.batstats.battery.measurement.PowerState
import java.text.DateFormat
import java.util.Date

/** One set of interval labels/values shared by the monitoring screen and notification. */
object MonitoringText {
    fun state(power: PowerState) = when (power) {
        PowerState.CHARGING -> "Charging"
        PowerState.DISCHARGING -> "Discharging"
        PowerState.PLUGGED -> "Plugged in · not charging"
        PowerState.UNKNOWN -> "Power state unavailable"
    }
    fun bucket(bucket: ObservedBucket): String =
        "${formatDrainRate(bucket.rateMa)} · ${formatCharge(bucket.chargeMah)} · ${formatDuration(bucket.durationMs)}"
    fun coverage(bucket: ObservedBucket): String = when {
        bucket.durationMs == 0L -> "No period observed"
        bucket.chargeCoveredMs == 0L -> "Charge unavailable"
        else -> "Counter coverage ${formatDuration(bucket.chargeCoveredMs)} / ${formatDuration(bucket.durationMs)}"
    }
    fun since(summary: ObservationSummary): String = summary.startedAt?.let {
        "Observed since ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}"
    } ?: "Waiting for an observation"
    fun expanded(summary: ObservationSummary): String = buildString {
        appendLine("━━ Drain while discharging ━━")
        appendLine("Screen on: ${bucket(summary.screenOn)}")
        appendLine(coverage(summary.screenOn))
        appendLine("Screen off: ${bucket(summary.screenOff)}")
        appendLine(coverage(summary.screenOff))
        appendLine()
        appendLine("━━ Observed device activity ━━")
        appendLine("CPU suspend: ${formatDuration(summary.cpuSuspendMs)} / ${formatDuration(summary.cpuObservedMs)}")
        appendLine("Android Doze: ${formatDuration(summary.dozeMs)}")
        appendLine("Charging: ${formatDuration(summary.chargingMs)} · gained ${formatCharge(summary.charging.chargeMah)}")
        appendLine()
        appendLine("━━ Observation window ━━")
        appendLine(since(summary))
        appendLine("Discharge: ${bucket(summary.discharge)}")
        appendLine(coverage(summary.discharge))
        if (summary.gaps > 0) appendLine("${summary.gaps} gaps excluded")
        append("Screen off includes noninteractive AOD. CPU suspend and Doze are separate.")
    }
}
