package app.batstats.battery.apps

/** One app of [TopApps]: its power over the window and its share of every app's power (0..1). */
data class TopAppRow(val uid: Int, val packageName: String, val powerMah: Double, val share: Float)

/**
 * The biggest users in one per-app dump, over the window [basis] says: since the discharge session's baseline
 * ([AppUsageBasis.DELTA]), or absolute values when there is no usable baseline. [capturedAt] is the dump's time.
 */
data class TopApps(val rows: List<TopAppRow>, val basis: AppUsageBasis, val capturedAt: Long) {
    companion object {
        const val COUNT = 3

        /**
         * The top [count] real apps of [usage] (the folded "others" row only counts toward the total). [baseline] is
         * used only when it is older than [usage]: a dump taken before the session's baseline predates the session.
         * Null when no app used power.
         */
        fun of(usage: AppUsageSnapshot, baseline: AppUsageSnapshot?, count: Int = COUNT): TopApps? {
            val result = AppUsageDelta.compute(baseline?.takeIf { usage.capturedAt > it.capturedAt }, usage)
            val total = result.rows.sumOf { it.powerMah.coerceAtLeast(0.0) }
            val top = result.rows.filter { !it.isOthers && it.powerMah > 0 }.take(count)
            if (total <= 0 || top.isEmpty()) return null
            return TopApps(
                rows = top.map { TopAppRow(it.uid, it.packageName, it.powerMah, (it.powerMah / total).toFloat()) },
                basis = result.basis,
                capturedAt = usage.capturedAt,
            )
        }
    }
}
