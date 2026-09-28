package app.batstats.battery.apps

/** Result of comparing a discharge session's start/end app-usage snapshots. */
data class AppUsageDeltaResult(val basis: AppUsageBasis, val rows: List<AppUsageRow>)

/** Turns two `AppUsageSnapshot`s (or just the ended one) into the per-app rows a session shows. */
object AppUsageDelta {
    private const val POWER_EPSILON = 1e-6

    fun compute(baseline: AppUsageSnapshot?, end: AppUsageSnapshot, topN: Int = 30): AppUsageDeltaResult {
        val (basis, rawRows) = when {
            baseline == null -> AppUsageBasis.ABSOLUTE to end.rows
            !sameWindow(baseline, end) || totalDecreased(baseline.rows, end.rows) ->
                AppUsageBasis.WINDOW_RESET to end.rows
            else -> AppUsageBasis.DELTA to clampedDelta(baseline.rows, end.rows)
        }
        return AppUsageDeltaResult(basis, foldTopN(rawRows, topN))
    }

    private fun sameWindow(baseline: AppUsageSnapshot, end: AppUsageSnapshot): Boolean =
        baseline.windowStartedAt != null && end.windowStartedAt != null &&
            baseline.windowStartedAt == end.windowStartedAt &&
            baseline.windowStartCount != null && end.windowStartCount != null &&
            baseline.windowStartCount == end.windowStartCount

    /** A same-window dump whose total power went backwards (beyond floating-point rounding)
     * didn't really keep its window; treat it like a reset instead of reporting a bogus
     * negative-turned-zero delta. Per-uid fields are allowed to dip within a stable window
     * (batterystats redistributes/rounds them) — that's exactly what the clamp below is for. */
    private fun totalDecreased(baseline: List<AppUsageRow>, end: List<AppUsageRow>): Boolean =
        totalPower(end) < totalPower(baseline) - POWER_EPSILON

    private fun totalPower(rows: List<AppUsageRow>): Double = rows.sumOf { it.powerMah }

    private fun clampedDelta(baselineRows: List<AppUsageRow>, endRows: List<AppUsageRow>): List<AppUsageRow> {
        val baselineByUid = baselineRows.associateBy { it.uid }
        return endRows.map { end ->
            val base = baselineByUid[end.uid]
            end.copy(
                powerMah = (end.powerMah - (base?.powerMah ?: 0.0)).coerceAtLeast(0.0),
                cpuTimeMs = clampField(end.cpuTimeMs, base?.cpuTimeMs),
                foregroundTimeMs = clampField(end.foregroundTimeMs, base?.foregroundTimeMs),
                backgroundTimeMs = clampField(end.backgroundTimeMs, base?.backgroundTimeMs),
                wakelockTimeMs = clampField(end.wakelockTimeMs, base?.wakelockTimeMs),
                mobileBytes = clampField(end.mobileBytes, base?.mobileBytes),
                wifiBytes = clampField(end.wifiBytes, base?.wifiBytes),
            )
        }
    }

    /** `max(0, end - base)`; null only when both sides are unknown, missing side counts as zero. */
    private fun clampField(end: Long?, base: Long?): Long? = when {
        end == null && base == null -> null
        else -> ((end ?: 0L) - (base ?: 0L)).coerceAtLeast(0L)
    }

    private fun hasUsage(row: AppUsageRow): Boolean =
        row.powerMah > 0.0 || (row.cpuTimeMs ?: 0L) > 0L || (row.foregroundTimeMs ?: 0L) > 0L ||
            (row.backgroundTimeMs ?: 0L) > 0L || (row.wakelockTimeMs ?: 0L) > 0L ||
            (row.mobileBytes ?: 0L) > 0L || (row.wifiBytes ?: 0L) > 0L

    private fun foldTopN(rows: List<AppUsageRow>, topN: Int): List<AppUsageRow> {
        val sorted = rows.filter(::hasUsage).sortedByDescending { it.powerMah }
        val top = sorted.take(topN)
        val rest = sorted.drop(topN)
        if (rest.isEmpty()) return top
        val others = AppUsageRow(
            uid = -1,
            packageName = "",
            powerMah = rest.sumOf { it.powerMah },
            cpuTimeMs = foldLong(rest, AppUsageRow::cpuTimeMs),
            foregroundTimeMs = foldLong(rest, AppUsageRow::foregroundTimeMs),
            backgroundTimeMs = foldLong(rest, AppUsageRow::backgroundTimeMs),
            wakelockTimeMs = foldLong(rest, AppUsageRow::wakelockTimeMs),
            mobileBytes = foldLong(rest, AppUsageRow::mobileBytes),
            wifiBytes = foldLong(rest, AppUsageRow::wifiBytes),
            isOthers = true,
        )
        return top + others
    }

    /** Sum of the non-null values, or null when none of the folded rows reported this field. */
    private fun foldLong(rows: List<AppUsageRow>, field: (AppUsageRow) -> Long?): Long? =
        rows.mapNotNull(field).takeIf { it.isNotEmpty() }?.sum()
}
