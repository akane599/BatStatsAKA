package app.batstats.battery.drain

import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.measurement.PowerState

/**
 * The time left / time to full the notification shows. Realtime first carries the raw capture (charging: Android's
 * time to full; discharging: none), then the writer's copy with its estimate; the next capture drops it again. The
 * last estimate is kept while the power state holds, for [HOLD_MS], counted down to the current reading, so the
 * notification's content (its update key) does not flip every capture.
 *
 * Mirrors `viewmodel.NowMapping.withEta` (Now's hero), which this package can't reuse without depending on the
 * viewmodel layer and its chart types; keep the two rules in step.
 */
object EtaHold {
    const val HOLD_MS = 60_000L

    data class Held(val remainingMs: Long, val power: PowerState, val atMs: Long)

    /** A reading with the estimate it shows. */
    data class Reading(val reading: BatteryRepository.Realtime = BatteryRepository.Realtime(), val held: Held? = null) {
        /** The held estimate counted down to this reading; null without one. */
        val remainingMs: Long?
            get() {
                val sample = reading.sample ?: return null
                return held?.let { (it.remainingMs - (sample.timestamp - it.atMs)).coerceAtLeast(0) }
            }
    }

    fun next(previous: Reading, reading: BatteryRepository.Realtime): Reading {
        val sample = reading.sample ?: return previous.copy(reading = reading)
        val power = reading.powerState
        val eta = sample.etaMs?.takeIf { it > 0 }
        val held = when {
            eta != null -> Held(eta, power, sample.timestamp)
            else -> previous.held?.takeIf { it.power == power && sample.timestamp - it.atMs in 0..HOLD_MS }
        }
        return Reading(reading, held)
    }
}
