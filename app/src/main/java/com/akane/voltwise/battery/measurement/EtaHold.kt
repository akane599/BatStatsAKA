package com.akane.voltwise.battery.measurement

import com.akane.voltwise.battery.data.BatteryRepository

/**
 * The time left / time to full a reading shows: Now's hero and the ongoing notification both hold the writer's
 * last estimate across captures that don't carry one, so their content does not flip every capture.
 *
 * Realtime first carries the raw capture (charging: Android's time to full; discharging: none), then the writer's
 * copy with its estimate; the next capture drops it again. The last estimate is kept while the power state holds,
 * for the greater of [HOLD_MS] and twice the longest expected capture interval seen since that estimate, counted down
 * from its original timestamp. The longer bound survives a switch from screen-off polling to faster screen-on
 * captures; a new estimate starts a new bound at its own cadence.
 */
object EtaHold {
    const val HOLD_MS = 60_000L

    /** [basis] is unused by the notification, which only needs [remainingMs]; Now's hero shows it. */
    data class Held(
        val remainingMs: Long,
        val basis: EtaBasis?,
        val power: PowerState,
        val atMs: Long,
        val holdMs: Long = HOLD_MS,
    )

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
        val holdMs = maxOf(HOLD_MS, 2 * reading.expectedIntervalMs)
        val held = when {
            eta != null -> Held(eta, EtaBasis.entries.firstOrNull { it.name == sample.etaBasis }, power, sample.timestamp, holdMs)
            else -> previous.held?.let {
                val bound = maxOf(it.holdMs, holdMs)
                it.takeIf { it.power == power && sample.timestamp - it.atMs in 0..bound }?.copy(holdMs = bound)
            }
        }
        return Reading(reading, held)
    }
}
