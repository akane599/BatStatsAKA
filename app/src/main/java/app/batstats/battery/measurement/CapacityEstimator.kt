package app.batstats.battery.measurement

import kotlin.math.abs

enum class CapacityConfidence { LOW, MEDIUM, HIGH }

enum class CapacityBasis {
    /** A session's counter Δq over its level span. */
    COUNTER_SPAN,

    /** The fuel gauge's sysfs `charge_full` (root). */
    SYSFS,
}

/** A full-charge capacity estimate. Stored per session as mAh + confidence/basis names. */
data class CapacityEstimate(val fullUah: Long, val confidence: CapacityConfidence, val basis: CapacityBasis) {
    val fullMah: Int get() = ((fullUah + 500) / 1_000).toInt()
}

/**
 * Battery capacity and health from observed sessions and optional sysfs values. Every capacity is
 * µAh; sources in other units are rejected, never scaled.
 */
object CapacityEstimator {
    /** Full capacities outside this range (µAh) are treated as unit errors or counter resets. */
    val PLAUSIBLE_FULL_UAH = 300_000L..50_000_000L

    private const val MIN_SPAN_PERCENT = 10
    private const val MEDIUM_SPAN_PERCENT = 20
    private const val HIGH_SPAN_PERCENT = 40
    private const val MIN_COVERAGE = 0.75
    private const val HIGH_COVERAGE = 0.9

    /**
     * Δq ÷ Δlevel × 100 for one charge or discharge session ([deltaUah] ≥ 0, as
     * `ChargeSession.deltaUah`; coverage = [counterCoveredMs] ÷ [observedMs]).
     * HIGH: span ≥ 40 % and coverage ≥ 90 %; MEDIUM: span ≥ 20 %; LOW: span ≥ 10 %.
     * Null for a shorter span, coverage under 75 % (Δq would miss part of the span) or an
     * implausible result.
     */
    fun fromSession(deltaUah: Long?, startLevel: Int?, endLevel: Int?, observedMs: Long, counterCoveredMs: Long): CapacityEstimate? {
        if (deltaUah == null || deltaUah <= 0 || startLevel == null || endLevel == null || observedMs <= 0) return null
        val span = abs(endLevel - startLevel)
        val coverage = counterCoveredMs.toDouble() / observedMs
        if (span < MIN_SPAN_PERCENT || coverage < MIN_COVERAGE) return null
        val fullUah = deltaUah * 100 / span
        if (fullUah !in PLAUSIBLE_FULL_UAH) return null
        val confidence = when {
            span >= HIGH_SPAN_PERCENT && coverage >= HIGH_COVERAGE -> CapacityConfidence.HIGH
            span >= MEDIUM_SPAN_PERCENT -> CapacityConfidence.MEDIUM
            else -> CapacityConfidence.LOW
        }
        return CapacityEstimate(fullUah, confidence, CapacityBasis.COUNTER_SPAN)
    }

    /** sysfs `charge_full` in µAh: the gauge's own learned value, MEDIUM confidence. */
    fun fromSysfs(chargeFullUah: Long?): CapacityEstimate? =
        chargeFullUah?.takeIf { it in PLAUSIBLE_FULL_UAH }?.let { CapacityEstimate(it, CapacityConfidence.MEDIUM, CapacityBasis.SYSFS) }

    /** Design capacity in µAh: the Settings override (mAh, 0 = auto) wins over sysfs `charge_full_design` (µAh). */
    fun designUah(overrideMah: Int, chargeFullDesignUah: Long?): Long? =
        if (overrideMah > 0) overrideMah * 1_000L else chargeFullDesignUah?.takeIf { it in PLAUSIBLE_FULL_UAH }

    /** Estimate ÷ design × 100; may exceed 100 for a new battery. Null without a design capacity. */
    fun healthPercent(fullUah: Long, designUah: Long?): Double? =
        designUah?.takeIf { it > 0 }?.let { fullUah * 100.0 / it }

    /**
     * The current value from several estimates (the caller picks which, e.g. the latest sessions
     * plus sysfs): the confidence-weighted median (LOW 1, MEDIUM 2, HIGH 3; the lower median on
     * a tie), keeping that estimate's basis. Its confidence is the strongest among the estimates
     * within 10 % of it, so one outlier cannot raise it.
     */
    fun combine(estimates: List<CapacityEstimate>): CapacityEstimate? {
        if (estimates.isEmpty()) return null
        val sorted = estimates.sortedBy { it.fullUah }
        val total = sorted.sumOf { weight(it) }
        var cumulative = 0
        var median = sorted.last()
        for (estimate in sorted) {
            cumulative += weight(estimate)
            if (cumulative * 2 >= total) {
                median = estimate
                break
            }
        }
        val confidence = estimates.filter { abs(it.fullUah - median.fullUah) * 10 <= median.fullUah }.maxOf { it.confidence }
        return median.copy(confidence = confidence)
    }

    private fun weight(estimate: CapacityEstimate) = when (estimate.confidence) {
        CapacityConfidence.LOW -> 1
        CapacityConfidence.MEDIUM -> 2
        CapacityConfidence.HIGH -> 3
    }
}
