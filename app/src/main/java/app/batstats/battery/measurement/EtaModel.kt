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
private const val SLEEP_GAP_MIN_MS = 120_000L

/**
 * True when [after] cannot extend a trend from [before]: another monitoring run, a GAP capture,
 * no forward elapsed time, or a sleep gap — elapsed beyond max(120 s, 3 × the expected interval),
 * where two endpoint readings say little about the time between. Callers skip such intervals;
 * they never reset a trend.
 */
internal fun skipsTrend(before: Observation, after: Observation): Boolean {
    val elapsedMs = after.elapsedMs - before.elapsedMs
    return after.generation != before.generation || after.boundary == Boundary.GAP || elapsedMs <= 0 ||
        elapsedMs > maxOf(SLEEP_GAP_MIN_MS, before.expectedIntervalMs * 3)
}

/**
 * Time-weighted EWMA of a counter-derived rate, kept as decayed sums: before each interval adds
 * its Δq (µAh) and Δt (ms), the older sums decay by e^(−Δt/τ). The rate is Σq ÷ Σt, so a steady
 * rate reads exactly from the first interval and quantised counters average out. A seed counts
 * as τ of prior observation and fades as live data arrives.
 */
internal class RateEwma(private val tauMs: Long) {
    private var chargeUah = 0.0
    private var timeMs = 0.0
    private var liveTimeMs = 0.0

    /** Undecayed live time and charge folded in since the last reset. */
    var liveMs = 0L
        private set
    var liveUah = 0L
        private set

    val rateUa: Double? get() = if (timeMs > 0) chargeUah * 3_600_000 / timeMs else null

    /** Share of the average owed to live intervals: 0 = seed only, 1 = live only. */
    val liveShare: Double get() = if (timeMs > 0) liveTimeMs / timeMs else 0.0

    /** Takes effect only while no live data exists. */
    fun seed(rateUa: Double) {
        if (liveMs > 0) return
        chargeUah = rateUa * tauMs / 3_600_000
        timeMs = tauMs.toDouble()
        liveTimeMs = 0.0
    }

    fun add(deltaUah: Long, durationMs: Long) {
        val decay = exp(-durationMs.toDouble() / tauMs)
        chargeUah = chargeUah * decay + deltaUah
        timeMs = timeMs * decay + durationMs
        liveTimeMs = liveTimeMs * decay + durationMs
        liveMs += durationMs
        liveUah += deltaUah
    }

    fun reset() {
        chargeUah = 0.0
        timeMs = 0.0
        liveTimeMs = 0.0
        liveMs = 0
        liveUah = 0
    }
}
