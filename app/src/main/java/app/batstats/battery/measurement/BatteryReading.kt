package app.batstats.battery.measurement

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Android BatteryManager contracts. No device-specific scaling is guessed: only [calibratedUa]
 * applies a unit/sign that CurrentCalibrator detected from counter evidence (or the user chose).
 */
object BatteryReading {
    fun percentage(level: Int, scale: Int): Int? =
        if (scale > 0 && level in 0..scale) (level * 100.0 / scale).roundToInt() else null

    fun currentUa(raw: Long): Long? = raw.takeIf { it != Long.MIN_VALUE && it in -100_000_000L..100_000_000L }

    /** Raw `CURRENT_NOW` → Android-signed µA: [currentUa]'s validity check first, then [calibration]. */
    fun calibratedUa(raw: Long?, calibration: CurrentCalibration): Long? =
        raw?.let(::currentUa)?.let(calibration::toMicroamps)

    fun chargeUah(raw: Long): Long? = raw.takeIf { it in 0..200_000_000L }
    fun energyNwh(raw: Long): Long? = raw.takeIf { it in 0..1_000_000_000_000L }
    fun voltageMv(raw: Int): Int? = raw.takeIf { it in 1..30_000 }
    fun temperatureDeciC(raw: Int): Int? = raw.takeIf { it in -500..1500 }

    /** Net battery power: positive into the battery, negative out. Derived, not an energy meter. */
    fun powerMw(currentUa: Long?, voltageMv: Int?): Double? =
        if (currentUa != null && voltageMv != null) currentUa.toDouble() * voltageMv / 1_000_000 else null

    /** Flag conflicting reports without guessing a vendor multiplier or flipping the sign. */
    fun directionConflicts(currentUa: Long?, power: PowerState): Boolean = currentUa != null &&
        ((power == PowerState.DISCHARGING && currentUa > 0) || (power == PowerState.CHARGING && currentUa < 0))

    fun powerState(status: Int, plugged: Int?): PowerState = when {
        plugged == null || plugged !in 0..15 || status !in 2..5 -> PowerState.UNKNOWN
        status == 3 -> PowerState.DISCHARGING // A connected supply may be insufficient; honor reported discharge.
        plugged == 0 && status != 2 -> PowerState.DISCHARGING
        plugged > 0 && status == 2 -> PowerState.CHARGING
        plugged > 0 -> PowerState.PLUGGED
        else -> PowerState.UNKNOWN
    }

    /** A counter move against the expected direction up to this size (3 mAh) is gauge noise. */
    const val COUNTER_BLIP_UAH = 3_000L

    /**
     * Counter decrease from [before] to [after] in µAh (charging callers swap the arguments).
     * A move the wrong way of up to [COUNTER_BLIP_UAH] is a blip and counts as zero; larger ones
     * (resets, direction changes) and implausible jumps are rejected; valid zero stays zero.
     */
    fun dischargedUah(before: Long?, after: Long?, elapsedMs: Long): Long? {
        if (before == null || after == null || elapsedMs <= 0) return null
        val delta = before - after
        return when {
            delta < -COUNTER_BLIP_UAH -> null
            delta < 0 -> 0
            delta <= 100_000_000.0 * elapsedMs / 3_600_000.0 -> delta
            else -> null
        }
    }
}

enum class PowerState { CHARGING, DISCHARGING, PLUGGED, UNKNOWN }
