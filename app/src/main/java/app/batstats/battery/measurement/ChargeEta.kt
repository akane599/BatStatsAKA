package app.batstats.battery.measurement

import kotlin.math.roundToLong

/**
 * Time to full. Android's own estimate wins whenever the sampler has one (API 28+). Otherwise:
 * a time-weighted EWMA (τ [ETA_TAU_MS]) of the live counter-derived charge rate up to 80 %, then
 * 80→100 % at the taper learned for this charger; until one is learned, at half the live rate
 * (above 80 % the live rate already reflects the taper). The rate restarts with each charger
 * connection; percent → µAh comes from counter ÷ level, so levels under 10 % give no model value.
 *
 * Taper learning: within one connection, from the observed step into ≥ 80 % to the step into
 * 100 %, as ms per percent, averaged with any earlier value for that charger. Steps across a CPU
 * suspend count; a non-charging interval, GAP or step across an observation gap
 * (ObservationEngine's rule) spoils the charge's timing and teaches nothing.
 *
 * @param learnedTaperMsPerPercent earlier [ChargeEta.learnedTaperMsPerPercent], persisted by the caller
 */
class ChargeEta(
    learnedTaperMsPerPercent: Map<Int, Long> = emptyMap(),
    tauMs: Long = ETA_TAU_MS,
) {
    private data class LevelStep(val level: Int, val elapsedMs: Long)

    private val taper = learnedTaperMsPerPercent.toMutableMap()
    private val rate = RateEwma(tauMs)
    private var previous: Observation? = null
    private var previousPlugged: Int? = null
    private var taperFrom: LevelStep? = null
    private var taperTo: LevelStep? = null

    /** Charger (`EXTRA_PLUGGED` value) → learned 80→100 % taper in ms per percent. */
    val learnedTaperMsPerPercent: Map<Int, Long> get() = taper.toMap()

    /** Forgets the live rate and any taper in progress; learned tapers stay. */
    fun reset() {
        rate.reset()
        previous = null
        previousPlugged = null
        clearTaper()
    }

    /**
     * Feed every observation in order.
     * @param plugged `BatteryManager.EXTRA_PLUGGED` at this capture (1 AC, 2 USB, 4 wireless, 8 dock; 0 unplugged)
     * @param androidRemainingMs `computeChargeTimeRemaining()` on API 28+, else null; -1/0 count as unavailable
     * @return null while not charging or without a usable estimate
     */
    fun accept(point: Observation, plugged: Int?, androidRemainingMs: Long?): EtaEstimate? {
        val before = previous?.takeIf { it.generation == point.generation && plugged == previousPlugged }
        previous = point
        previousPlugged = plugged
        if (before == null || plugged == null || plugged <= 0) {
            rate.reset()
            clearTaper()
        } else {
            learnRate(before, point)
            trackTaper(before, point, plugged)
        }
        return estimate(point, plugged, androidRemainingMs)
    }

    private fun learnRate(before: Observation, point: Observation) {
        if (before.power != PowerState.CHARGING || point.power != PowerState.CHARGING || skipsTrend(before, point)) return
        val elapsedMs = point.elapsedMs - before.elapsedMs
        BatteryReading.dischargedUah(point.chargeUah, before.chargeUah, elapsedMs)?.let { rate.add(it, elapsedMs) }
    }

    private fun trackTaper(before: Observation, point: Observation, plugged: Int) {
        val from = before.level
        val to = point.level
        val stepped = from != null && to != null && to > from
        val powered = point.power == PowerState.CHARGING || point.power == PowerState.PLUGGED
        // whittle: trust reported CHARGING; filter hidden holds only with a device-grounded duration bound.
        if (before.power != PowerState.CHARGING || point.boundary == Boundary.GAP || !powered ||
            (stepped && skipsTrend(before, point))) {
            clearTaper()
            return
        }
        if (from == null || to == null || !stepped || to < TAPER_START_PERCENT) return
        val step = LevelStep(to, point.elapsedMs)
        when {
            from < TAPER_START_PERCENT -> {
                taperFrom = step
                taperTo = null
            }
            taperFrom != null -> taperTo = step
        }
        if (to >= 100) learnTaper(plugged)
    }

    private fun learnTaper(plugged: Int) {
        val start = taperFrom
        val end = taperTo
        clearTaper()
        if (start == null || end == null || end.level < 100) return
        val sample = (end.elapsedMs - start.elapsedMs) / (end.level - start.level)
        taper[plugged] = taper[plugged]?.let { (it + sample) / 2 } ?: sample
    }

    private fun clearTaper() {
        taperFrom = null
        taperTo = null
    }

    private fun estimate(point: Observation, plugged: Int?, androidRemainingMs: Long?): EtaEstimate? {
        if (point.power != PowerState.CHARGING) return null
        androidRemainingMs?.takeIf { it in 1..MAX_ETA_MS }?.let { return EtaEstimate(it, EtaBasis.ANDROID, 0, null) }
        val level = point.level?.takeIf { it in MIN_LEVEL_PERCENT..99 } ?: return null
        val chargeUah = point.chargeUah ?: return null
        val rateUa = rate.rateUa?.takeIf { it > 0 && rate.liveMs >= MIN_LIVE_MS } ?: return null
        val liveMsPerPercent = chargeUah.toDouble() / level * 3_600_000 / rateUa
        val learned = plugged?.let(taper::get)
        val taperMsPerPercent = learned?.toDouble()
            ?: if (level >= TAPER_START_PERCENT) liveMsPerPercent else liveMsPerPercent / DEFAULT_TAPER_RATE_SHARE
        val remainingMs = (TAPER_START_PERCENT - level).coerceAtLeast(0) * liveMsPerPercent +
            (100 - maxOf(level, TAPER_START_PERCENT)) * taperMsPerPercent
        if (remainingMs > MAX_ETA_MS) return null
        val basis = if (learned != null) EtaBasis.TAPER_MODEL else EtaBasis.LIVE_RATE
        return EtaEstimate(remainingMs.roundToLong(), basis, rate.liveMs, rateUa.roundToLong())
    }

    private companion object {
        const val TAPER_START_PERCENT = 80
        const val MIN_LEVEL_PERCENT = 10
        const val MIN_LIVE_MS = 5 * 60_000L

        /** Assumed average 80→100 % rate, as a share of the live rate, until a taper is learned. */
        const val DEFAULT_TAPER_RATE_SHARE = 0.5
    }
}
