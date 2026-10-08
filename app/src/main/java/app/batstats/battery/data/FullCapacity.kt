package app.batstats.battery.data

import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.measurement.CapacityEstimator
import app.batstats.battery.measurement.HealthSummary

/** Full capacity for drain conversion: usable counter-derived capacity, then the stored Health estimate. */
fun resolveFullUah(counterUah: Long?, levelPct: Int?, storedEstimateUah: Long?): Long? =
    HealthSummary.counterFullUah(counterUah, levelPct) ?: storedEstimateUah

/** The Health rule's confidence-weighted median of recent stored session estimates, without design capacity. */
fun storedFullUah(sessions: List<ChargeSession>): Long? = CapacityEstimator.combine(
    sessions.mapNotNull { HealthSummary.storedEstimate(it.capacityEstimateMah, it.capacityConfidence, it.capacityBasis) },
)?.fullUah
