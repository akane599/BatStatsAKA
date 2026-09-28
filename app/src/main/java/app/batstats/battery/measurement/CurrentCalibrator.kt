package app.batstats.battery.measurement

import kotlin.math.abs

/**
 * One closed evidence window. [k] = counter-derived current ÷ mean raw `CURRENT_NOW` over the
 * window's covered intervals: the multiplier that turns raw readings into Android-signed µA
 * (|k| 0.5–2 → µA, 500–2000 → mA, k < 0 → inverted). [calibration] is null when |k| fits neither.
 * [chargeUah] is |Σ Δq| and [coveredMs] the covered time; [power] is CHARGING or DISCHARGING.
 */
data class CalibrationWindow(
    val k: Double,
    val calibration: CurrentCalibration?,
    val chargeUah: Long,
    val coveredMs: Long,
    val power: PowerState,
)

enum class CalibrationBasis {
    /** 3 of the last 4 counter windows agree and none contradicts: unit and sign. */
    COUNTER_WINDOWS,

    /** ≥ 20 unplugged readings in a row were positive while the counter fell: sign only; the unit stays µA. */
    POSITIVE_WHILE_DISCHARGING,
}

/** What the evidence currently supports; [agreeingWindows] is 0 for the fast path. */
data class CalibrationDecision(
    val calibration: CurrentCalibration,
    val basis: CalibrationBasis,
    val agreeingWindows: Int,
)

/**
 * Detects how a device reports `CURRENT_NOW` by comparing ∫raw I dt with the charge counter's Δq.
 * Feed every observation in order with the raw (uncalibrated) `currentUa` and its `EXTRA_PLUGGED`
 * value; read [decision].
 *
 * Covered interval: both ends have raw current and counter, same generation and power
 * (CHARGING or DISCHARGING), no observation gap (ObservationEngine's rule), a plausible counter
 * step, and the CPU awake for ≥ 90 % of it, so the endpoint currents stand for the interval
 * (a suspended interval's awake-time reading would overstate ∫I dt). A window sums covered intervals
 * of one power direction (a direction change discards it) and closes at |Δq| ≥ 20 mAh and ≥ 10 min.
 *
 * Fast path: ≥ 20 consecutive unplugged (`plugged == 0`) readings with raw > 0, and — when the
 * counter is reported — a counter that fell across them, mark the sign inverted. Plugged readings
 * (e.g. status "discharging" during a charge hold, current ≈ 0) and missing currents neither count
 * nor reset the run; an unplugged reading ≤ 0 resets it.
 *
 * This only decides: storing, overrides and the Undo notice belong to the caller, and [decision]
 * turning null later means "no conclusion now", not "revert". The counter itself is sanity-checked
 * ([counterSuspect]) but never scaled.
 */
class CurrentCalibrator {
    private data class OpenWindow(
        val power: PowerState,
        val deltaUah: Long = 0,
        val rawUaMs: Double = 0.0,
        val coveredMs: Long = 0,
    )

    private var previous: Observation? = null
    private var open: OpenWindow? = null
    private val closed = ArrayDeque<CalibrationWindow>()
    private var positiveStreak = 0
    private var streakFirstUah: Long? = null
    private var streakLastUah: Long? = null

    /** The last [WINDOWS_KEPT] closed windows, oldest first. */
    val windows: List<CalibrationWindow> get() = closed.toList()

    var decision: CalibrationDecision? = null
        private set

    /**
     * True while the counter implies an implausible full capacity (counter ÷ level outside
     * [CapacityEstimator.PLAUSIBLE_FULL_UAH], e.g. a counter in mAh); such readings are not evidence.
     */
    var counterSuspect: Boolean = false
        private set

    /** @param plugged `BatteryManager.EXTRA_PLUGGED` at this capture (0 = unplugged), null if unknown */
    fun accept(point: Observation, plugged: Int?): CalibrationDecision? {
        val before = previous
        previous = point
        val raw = point.currentUa
        if (plugged == 0 && raw != null) countUnplugged(raw, point.chargeUah)
        val level = point.level
        val chargeUah = point.chargeUah
        if (level != null && level >= MIN_CHECK_LEVEL && chargeUah != null) {
            counterSuspect = chargeUah * 100 / level !in CapacityEstimator.PLAUSIBLE_FULL_UAH
        }
        if (before != null) addInterval(before, point)
        decision = decide()
        return decision
    }

    fun reset() {
        previous = null
        open = null
        closed.clear()
        positiveStreak = 0
        streakFirstUah = null
        streakLastUah = null
        counterSuspect = false
        decision = null
    }

    private fun countUnplugged(raw: Long, chargeUah: Long?) {
        if (raw <= 0) {
            positiveStreak = 0
            return
        }
        if (positiveStreak == 0) {
            streakFirstUah = null
            streakLastUah = null
        }
        positiveStreak++
        if (chargeUah != null) {
            if (streakFirstUah == null) streakFirstUah = chargeUah
            streakLastUah = chargeUah
        }
    }

    /** True without counter values; otherwise the counter must have fallen across the positive run. */
    private fun streakCounterFell(): Boolean {
        val first = streakFirstUah
        val last = streakLastUah
        return first == null || last == null || last < first
    }

    private fun addInterval(before: Observation, point: Observation) {
        val power = point.power
        val rawBefore = before.currentUa
        val rawAfter = point.currentUa
        val chargeBefore = before.chargeUah
        val chargeAfter = point.chargeUah
        if (counterSuspect || skipsTrend(before, point) || before.power != power ||
            (power != PowerState.CHARGING && power != PowerState.DISCHARGING) ||
            rawBefore == null || rawAfter == null || chargeBefore == null || chargeAfter == null
        ) return
        val elapsedMs = point.elapsedMs - before.elapsedMs
        if (point.uptimeMs - before.uptimeMs < MIN_AWAKE_SHARE * elapsedMs) return
        val plausible = if (power == PowerState.DISCHARGING) {
            BatteryReading.dischargedUah(chargeBefore, chargeAfter, elapsedMs)
        } else {
            BatteryReading.dischargedUah(chargeAfter, chargeBefore, elapsedMs)
        }
        if (plausible == null) return
        val current = open?.takeIf { it.power == power } ?: OpenWindow(power)
        val window = current.copy(
            deltaUah = current.deltaUah + (chargeAfter - chargeBefore),
            rawUaMs = current.rawUaMs + (rawBefore + rawAfter) / 2.0 * elapsedMs,
            coveredMs = current.coveredMs + elapsedMs,
        )
        if (abs(window.deltaUah) >= WINDOW_MIN_UAH && window.coveredMs >= WINDOW_MIN_MS) {
            close(window)
            open = null
        } else {
            open = window
        }
    }

    private fun close(window: OpenWindow) {
        val k = if (window.rawUaMs == 0.0) Double.NaN else window.deltaUah * 3_600_000.0 / window.rawUaMs
        closed.addLast(CalibrationWindow(k, classify(k), abs(window.deltaUah), window.coveredMs, window.power))
        while (closed.size > WINDOWS_KEPT) closed.removeFirst()
    }

    private fun decide(): CalibrationDecision? {
        val conclusive = closed.mapNotNull { it.calibration }
        val agreed = conclusive.distinct().singleOrNull()
        if (agreed != null && conclusive.size >= WINDOWS_TO_AGREE) {
            return CalibrationDecision(agreed, CalibrationBasis.COUNTER_WINDOWS, conclusive.size)
        }
        val fastPath = positiveStreak >= FAST_PATH_READINGS && streakCounterFell()
        if (fastPath && conclusive.none { it.sign == CurrentSign.NORMAL }) {
            return CalibrationDecision(CurrentCalibration(sign = CurrentSign.INVERTED), CalibrationBasis.POSITIVE_WHILE_DISCHARGING, 0)
        }
        return null
    }

    companion object {
        const val WINDOW_MIN_UAH = 20_000L
        const val WINDOW_MIN_MS = 10 * 60_000L
        const val WINDOWS_KEPT = 4
        const val WINDOWS_TO_AGREE = 3
        const val FAST_PATH_READINGS = 20
        private const val MIN_AWAKE_SHARE = 0.9
        private const val MIN_CHECK_LEVEL = 10

        /** |k| 0.5–2 → µA, 500–2000 → mA; the sign of k gives the sign convention. Else null. */
        fun classify(k: Double): CurrentCalibration? {
            val unit = when (abs(k)) {
                in 0.5..2.0 -> CurrentUnit.MICROAMPS
                in 500.0..2000.0 -> CurrentUnit.MILLIAMPS
                else -> return null
            }
            return CurrentCalibration(unit, if (k > 0) CurrentSign.NORMAL else CurrentSign.INVERTED)
        }
    }
}
