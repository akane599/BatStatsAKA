package app.batstats.battery.measurement

/**
 * The battery's current capacity and health as the app shows them (Now's Health card; the Health screen reuses it):
 * [estimate] combines the stored per-session estimates, [healthPercent] compares it with [designUah] when one is known.
 */
data class HealthSummary(val estimate: CapacityEstimate, val designUah: Long?, val healthPercent: Double?) {
    companion object {
        /** How many of the newest sessions the combined estimate looks at. */
        const val SESSIONS = 50

        private const val MIN_LEVEL_FOR_COUNTER = 10

        /**
         * A session row's stored estimate (`capacityEstimateMah` + confidence/basis names), parsed tolerantly: an
         * unknown confidence drops the estimate, an unknown basis reads as [CapacityBasis.COUNTER_SPAN].
         */
        fun storedEstimate(mah: Int?, confidence: String?, basis: String?): CapacityEstimate? {
            if (mah == null || mah <= 0) return null
            val level = CapacityConfidence.entries.firstOrNull { it.name == confidence } ?: return null
            val source = CapacityBasis.entries.firstOrNull { it.name == basis } ?: CapacityBasis.COUNTER_SPAN
            return CapacityEstimate(mah * 1_000L, level, source)
        }

        /**
         * The confidence-weighted median of [estimates] ([CapacityEstimator.combine]; pass the newest [SESSIONS]
         * sessions' estimates, plus sysfs when read) and health against the design capacity: the Settings override
         * ([designOverrideMah], 0 = auto) or sysfs [chargeFullDesignUah]. Null without any estimate.
         */
        fun of(estimates: List<CapacityEstimate>, designOverrideMah: Int, chargeFullDesignUah: Long? = null): HealthSummary? {
            val combined = CapacityEstimator.combine(estimates) ?: return null
            val design = CapacityEstimator.designUah(designOverrideMah, chargeFullDesignUah)
            return HealthSummary(combined, design, CapacityEstimator.healthPercent(combined.fullUah, design))
        }

        /**
         * The fuel gauge's own full charge, counter × 100 ÷ level, when plausible (level ≥ 10 %, within
         * [CapacityEstimator.PLAUSIBLE_FULL_UAH]); else null. Converts drain rates to %/h without a session estimate.
         */
        fun counterFullUah(chargeUah: Long?, level: Int?): Long? {
            if (chargeUah == null || level == null || level < MIN_LEVEL_FOR_COUNTER) return null
            return (chargeUah * 100 / level).takeIf { it in CapacityEstimator.PLAUSIBLE_FULL_UAH }
        }
    }
}
