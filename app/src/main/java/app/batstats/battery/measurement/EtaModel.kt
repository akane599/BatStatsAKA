package app.batstats.battery.measurement

import kotlin.math.exp

/** What an ETA was computed from, so the UI can say so. */
enum class EtaBasis {
    /** Time-weighted EWMA of the live counter-derived rate. */
    LIVE_RATE,

    /** Mostly the 7-day typical discharge rate the estimator was seeded with. */
    TYPICAL_7D,

    /** Android's `BatteryManager.computeChargeTimeRemaining()` (API 28+). */
    ANDROID,

    /** Live rate to 80 %, then the 80→100 % taper learned for this charger. */
    TAPER_MODEL,
}

/**
 * [remainingMs] until empty (discharge) or full (charge). [observedMs] is the live counter time
 * behind the rate (0 for a pure seed or Android's value); [rateUa] is that rate in µA, positive
 * in the estimated direction, or null when Android supplied the estimate.
 */
data class EtaEstimate(val remainingMs: Long, val basis: EtaBasis, val observedMs: Long, val rateUa: Long?)

/** τ of both ETA rate averages. */
const val ETA_TAU_MS = 45 * 60_000L
internal const val MAX_ETA_MS = 7 * 86_400_000L

/**
 * True when [after] cannot extend a trend from [before]: an observation gap by ObservationEngine's
 * own rule ([observationGap]: restart, GAP capture, clock discontinuity, unconfirmed state change,
 * or an *awake* time beyond max(3 × the expected interval, 120 s)), or no forward elapsed time.
 * Callers skip such intervals; they never reset a trend. An interval the CPU slept through is
 * not a gap: its counter Δq is real drain or charge and counts.
 */
internal fun skipsTrend(before: Observation, after: Observation): Boolean =
    after.elapsedMs <= before.elapsedMs || observationGap(before, after) != null

/**
 * Time-weighted EWMA of a counter-derived rate, kept as decayed sums: before each interval adds
 * its Δq (µAh) and Δt (ms), the older sums decay by e^(−Δt/τ). The rate is Σq ÷ Σt, so a steady
 * rate reads exactly from the first interval and quantised counters average out.
 *
 * A seed is a prior rate weighted as τ of observation made before the first live interval. It
 * may be given at any time: it then gets the weight it would have kept had it come first,
 * τ · e^(−live time/τ), so a late seed fades exactly like an early one. A new seed replaces the old.
 */
internal class RateEwma(private val tauMs: Long) {
    private var seedUah = 0.0
    private var seedMs = 0.0
    private var liveChargeUah = 0.0
    private var liveTimeMs = 0.0

    /** Undecayed live time and charge folded in since the last reset. */
    var liveMs = 0L
        private set
    var liveUah = 0L
        private set

    val rateUa: Double?
        get() = (seedMs + liveTimeMs).takeIf { it > 0 }?.let { (seedUah + liveChargeUah) * 3_600_000 / it }

    /** Share of the average owed to live intervals: 0 = seed only, 1 = live only. */
    val liveShare: Double
        get() = (seedMs + liveTimeMs).takeIf { it > 0 }?.let { liveTimeMs / it } ?: 0.0

    fun seed(rateUa: Double) {
        seedMs = tauMs * exp(-liveMs.toDouble() / tauMs)
        seedUah = rateUa * seedMs / 3_600_000
    }

    fun add(deltaUah: Long, durationMs: Long) {
        val decay = exp(-durationMs.toDouble() / tauMs)
        seedUah *= decay
        seedMs *= decay
        liveChargeUah = liveChargeUah * decay + deltaUah
        liveTimeMs = liveTimeMs * decay + durationMs
        liveMs += durationMs
        liveUah += deltaUah
    }

    fun reset() {
        seedUah = 0.0
        seedMs = 0.0
        liveChargeUah = 0.0
        liveTimeMs = 0.0
        liveMs = 0
        liveUah = 0
    }
}
