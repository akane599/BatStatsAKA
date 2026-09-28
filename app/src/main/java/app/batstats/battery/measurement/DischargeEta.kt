package app.batstats.battery.measurement

import kotlin.math.roundToLong

/**
 * Time to empty: the charge counter ÷ a time-weighted EWMA (τ [ETA_TAU_MS]) of the counter-derived
 * discharge rate. Sleep gaps, GAP captures, restarts, counter resets and non-discharging intervals
 * are skipped, never a reset, so the trend survives screen-off and charging. [seed] with the
 * 7-day typical rate gives an estimate before live data; the basis says which one dominates.
 * Without a seed, 10 minutes and 5 mAh of live counter data are needed first.
 */
class DischargeEta(tauMs: Long = ETA_TAU_MS) {
    private val rate = RateEwma(tauMs)
    private var previous: Observation? = null

    /**
     * [typicalDischargeUa]: 7-day typical drain in µA (positive), e.g.
     * [DailySummaryAggregator.typicalDischargeUa]. Ignored once live data exists or when not positive.
     */
    fun seed(typicalDischargeUa: Double?) {
        if (typicalDischargeUa != null && typicalDischargeUa > 0) rate.seed(typicalDischargeUa)
    }

    /** Drops live data and the seed, e.g. after the user resets observation. */
    fun reset() {
        rate.reset()
        previous = null
    }

    /** Feed every observation in order; null while not discharging or without a usable rate. */
    fun accept(point: Observation): EtaEstimate? {
        val before = previous
        previous = point
        if (before != null && before.power == PowerState.DISCHARGING && point.power == PowerState.DISCHARGING &&
            !skipsTrend(before, point)
        ) {
            val elapsedMs = point.elapsedMs - before.elapsedMs
            BatteryReading.dischargedUah(before.chargeUah, point.chargeUah, elapsedMs)?.let { rate.add(it, elapsedMs) }
        }
        if (point.power != PowerState.DISCHARGING) return null
        val chargeUah = point.chargeUah ?: return null
        val rateUa = rate.rateUa?.takeIf { it > 0 } ?: return null
        val basis = if (rate.liveShare >= 0.5) EtaBasis.LIVE_RATE else EtaBasis.TYPICAL_7D
        if (basis == EtaBasis.LIVE_RATE && (rate.liveMs < MIN_LIVE_MS || rate.liveUah < MIN_LIVE_UAH)) return null
        val remainingMs = chargeUah * 3_600_000 / rateUa
        if (remainingMs !in 60_000.0..MAX_ETA_MS.toDouble()) return null
        return EtaEstimate(remainingMs.roundToLong(), basis, rate.liveMs, rateUa.roundToLong())
    }

    private companion object {
        const val MIN_LIVE_MS = 10 * 60_000L
        const val MIN_LIVE_UAH = 5_000L
    }
}
