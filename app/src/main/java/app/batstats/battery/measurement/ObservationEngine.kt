package app.batstats.battery.measurement

import kotlin.math.abs

/** One captured reading. elapsed/uptime are monotonic Android clocks, in milliseconds. */
data class Observation(
    val wallMs: Long,
    val elapsedMs: Long,
    val uptimeMs: Long,
    val level: Int?,
    val chargeUah: Long?,
    val currentUa: Long?,
    val voltageMv: Int?,
    val power: PowerState,
    val interactive: Boolean,
    val dozing: Boolean,
    val generation: String,
    val expectedIntervalMs: Long = 30_000,
    val boundary: Boundary = Boundary.SAMPLE
)

enum class Boundary { SAMPLE, SCREEN, POWER, DOZE, GAP }

data class ObservedBucket(
    val durationMs: Long = 0,
    val chargeCoveredMs: Long = 0,
    val chargeChangeUah: Long = 0,
    val energyCoveredMs: Long = 0,
    val energyMwh: Double = 0.0
) {
    val chargeMah: Double? get() = if (chargeCoveredMs > 0) chargeChangeUah / 1000.0 else null
    val rateMa: Double? get() = if (chargeCoveredMs >= 60_000) chargeChangeUah * 3600.0 / chargeCoveredMs else null
    val estimatedEnergyMwh: Double? get() = if (energyCoveredMs > 0) energyMwh else null
    operator fun plus(other: ObservedBucket) = ObservedBucket(
        durationMs + other.durationMs, chargeCoveredMs + other.chargeCoveredMs,
        chargeChangeUah + other.chargeChangeUah, energyCoveredMs + other.energyCoveredMs,
        energyMwh + other.energyMwh
    )
}

data class ObservationSummary(
    val startedAt: Long? = null,
    val latest: Observation? = null,
    val screenOn: ObservedBucket = ObservedBucket(),
    val screenOff: ObservedBucket = ObservedBucket(),
    val charging: ObservedBucket = ObservedBucket(),
    val pluggedMs: Long = 0,
    val unknownMs: Long = 0,
    val cpuSuspendMs: Long = 0,
    val cpuObservedMs: Long = 0,
    val dozeMs: Long = 0,
    val gaps: Int = 0,
    val counterGaps: Int = 0,
    val lastIssue: String? = null,
    val stopped: Boolean = true
) {
    val chargingMs: Long get() = charging.durationMs
    val discharge: ObservedBucket get() = screenOn + screenOff
    val observedMs: Long get() = discharge.durationMs + chargingMs + pluggedMs + unknownMs
}

/** Single-owner interval accounting. A new engine/reset never inherits pre-observation counters. */
class ObservationEngine {
    private var previous: Observation? = null
    var summary = ObservationSummary()
        private set

    fun reset() {
        previous = null
        summary = ObservationSummary()
    }

    fun stop() {
        previous = null
        summary = summary.copy(stopped = true)
    }

    fun accept(point: Observation): ObservationSummary {
        val before = previous
        previous = point
        if (before == null) {
            summary = summary.copy(startedAt = summary.startedAt ?: point.wallMs, latest = point, stopped = false)
            return summary
        }
        val elapsed = point.elapsedMs - before.elapsedMs
        val awake = point.uptimeMs - before.uptimeMs
        val clockShift = abs((point.wallMs - before.wallMs) - elapsed) > 5_000
        val missingTransition = (before.interactive != point.interactive && point.boundary != Boundary.SCREEN) ||
            (before.power != point.power && point.boundary != Boundary.POWER) ||
            (before.dozing != point.dozing && point.boundary != Boundary.DOZE)
        // Delays due to CPU suspend are expected. A long *awake* gap is not observed reliably.
        val gap = when {
            point.generation != before.generation -> "Monitoring restarted"
            elapsed < 0 || awake < 0 || awake > elapsed + 100 -> "Monotonic clock discontinuity"
            point.boundary == Boundary.GAP -> "Collection interrupted"
            clockShift -> "Wall clock changed; new interval baseline"
            missingTransition -> "State changed between observations"
            awake > maxOf(before.expectedIntervalMs * 3, 120_000L) -> "Gap in observation"
            else -> null
        }
        if (gap != null) {
            summary = summary.copy(latest = point, gaps = summary.gaps + 1, lastIssue = gap, stopped = false)
            return summary
        }
        if (elapsed == 0L) {
            summary = summary.copy(latest = point, stopped = false)
            return summary
        }

        val discharging = before.power == PowerState.DISCHARGING
        val charge = when {
            point.power != before.power -> null
            discharging -> BatteryReading.dischargedUah(before.chargeUah, point.chargeUah, elapsed)
            before.power == PowerState.CHARGING -> BatteryReading.dischargedUah(point.chargeUah, before.chargeUah, elapsed)
            else -> null
        }
        val voltage = if (before.voltageMv != null && point.voltageMv != null)
            (before.voltageMv + point.voltageMv) / 2.0 else null
        val bucket = ObservedBucket(
            durationMs = elapsed,
            chargeCoveredMs = if (charge != null) elapsed else 0,
            chargeChangeUah = charge ?: 0,
            energyCoveredMs = if (charge != null && voltage != null) elapsed else 0,
            energyMwh = if (charge != null && voltage != null) charge * voltage / 1_000_000 else 0.0
        )
        val counterGap = discharging && point.power == before.power && charge == null
        summary = summary.copy(
            latest = point,
            screenOn = if (discharging && before.interactive) summary.screenOn + bucket else summary.screenOn,
            screenOff = if (discharging && !before.interactive) summary.screenOff + bucket else summary.screenOff,
            charging = if (before.power == PowerState.CHARGING) summary.charging + bucket else summary.charging,
            pluggedMs = summary.pluggedMs + if (before.power == PowerState.PLUGGED) elapsed else 0,
            unknownMs = summary.unknownMs + if (before.power == PowerState.UNKNOWN) elapsed else 0,
            cpuSuspendMs = summary.cpuSuspendMs + (elapsed - awake).coerceIn(0, elapsed),
            cpuObservedMs = summary.cpuObservedMs + elapsed,
            dozeMs = summary.dozeMs + if (before.dozing) elapsed else 0,
            counterGaps = summary.counterGaps + if (counterGap) 1 else 0,
            lastIssue = if (counterGap) "Charge counter missing, reset or inconsistent; interval charge unavailable" else summary.lastIssue,
            stopped = false
        )
        return summary
    }
}
