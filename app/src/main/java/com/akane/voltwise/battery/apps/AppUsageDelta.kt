package com.akane.voltwise.battery.apps

/** Result of comparing a discharge session's start/end app-usage snapshots. */
data class AppUsageDeltaResult(
    val basis: AppUsageBasis,
    val rows: List<AppUsageRow>,
    val deviceWakers: List<DeviceWaker> = emptyList(),
    val captureStartMs: Long? = null,
    val captureEndMs: Long? = null,
)

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
        val selected = TopApps.selectSessionRows(rawRows, topN)
        val rows = selected.map { row ->
            val hints = end.tagHints[row.uid]?.takeUnless { row.isOthers }
            row.copy(topWakelockTag = hints?.wakelock, topAlarmTag = hints?.alarm, topJobName = hints?.job)
        }
        return AppUsageDeltaResult(basis, rows, wakerDelta(baseline, end, basis), baseline?.capturedAt, end.capturedAt)
    }

    private fun sameWindow(baseline: AppUsageSnapshot, end: AppUsageSnapshot): Boolean =
        baseline.windowStartedAt != null && end.windowStartedAt != null &&
            baseline.windowStartedAt == end.windowStartedAt &&
            baseline.windowStartCount != null && end.windowStartCount != null &&
            baseline.windowStartCount == end.windowStartCount

    /** A same-window dump whose total power went backwards (beyond floating-point rounding)
     * didn't really keep its window; treat it like a reset instead of reporting a bogus
     * negative-turned-zero delta. Per-uid fields are allowed to dip within a stable window
     * (batterystats redistributes/rounds them) — that's exactly what the clamp below is for.
     *
     * The totals are summed only over uids present in *both* snapshots: a uid that was
     * uninstalled between baseline and end drops out of `end.rows` entirely, and would otherwise
     * make the total look like it went backwards even though every remaining app's power only
     * rose — a false reset, not a real one. */
    private fun totalDecreased(baseline: List<AppUsageRow>, end: List<AppUsageRow>): Boolean {
        val baselineByUid = baseline.associateBy { it.uid }
        val endByUid = end.associateBy { it.uid }
        val commonUids = baselineByUid.keys intersect endByUid.keys
        val baselineTotal = commonUids.sumOf { baselineByUid.getValue(it).powerMah }
        val endTotal = commonUids.sumOf { endByUid.getValue(it).powerMah }
        return endTotal < baselineTotal - POWER_EPSILON
    }

    private fun clampedDelta(baselineRows: List<AppUsageRow>, endRows: List<AppUsageRow>): List<AppUsageRow> {
        val baselineByUid = baselineRows.associateBy { it.uid }
        // Schema-v6 baselines have every extended column null. Without a support marker,
        // keep those snapshots unknown; otherwise absent sparse checkin records mean zero.
        val extendedCountersSupported = baselineRows.any { row ->
            row.wakeupAlarms != null || row.partialWakelockCount != null || row.partialWakelockBgMs != null ||
                row.jobCount != null || row.jobMs != null || row.syncCount != null || row.fgServiceMs != null ||
                row.topMs != null || row.mobileActiveMs != null || row.gpsMs != null || row.sensorMs != null
        }
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
                wakeupAlarms = nullableDelta(end.wakeupAlarms, base?.wakeupAlarms, extendedCountersSupported),
                partialWakelockCount = nullableDelta(end.partialWakelockCount, base?.partialWakelockCount, extendedCountersSupported),
                partialWakelockBgMs = nullableDelta(end.partialWakelockBgMs, base?.partialWakelockBgMs, extendedCountersSupported),
                jobCount = nullableDelta(end.jobCount, base?.jobCount, extendedCountersSupported),
                jobMs = nullableDelta(end.jobMs, base?.jobMs, extendedCountersSupported),
                syncCount = nullableDelta(end.syncCount, base?.syncCount, extendedCountersSupported),
                fgServiceMs = nullableDelta(end.fgServiceMs, base?.fgServiceMs, extendedCountersSupported),
                topMs = nullableDelta(end.topMs, base?.topMs, extendedCountersSupported),
                mobileActiveMs = nullableDelta(end.mobileActiveMs, base?.mobileActiveMs, extendedCountersSupported),
                gpsMs = nullableDelta(end.gpsMs, base?.gpsMs, extendedCountersSupported),
                sensorMs = nullableDelta(end.sensorMs, base?.sensorMs, extendedCountersSupported),
            )
        }
    }

    /** `max(0, end - base)`; null only when both sides are unknown, missing side counts as zero. */
    private fun clampField(end: Long?, base: Long?): Long? = when {
        end == null && base == null -> null
        else -> ((end ?: 0L) - (base ?: 0L)).coerceAtLeast(0L)
    }

    /** Sparse baseline records start at zero; unsupported snapshots/endpoints and resets stay unknown. */
    private fun nullableDelta(end: Long?, base: Long?, extendedCountersSupported: Boolean): Long? = when {
        end == null || !extendedCountersSupported -> null
        else -> (end - (base ?: 0L)).takeIf { it >= 0L }
    }

    private fun wakerDelta(baseline: AppUsageSnapshot?, end: AppUsageSnapshot, basis: AppUsageBasis): List<DeviceWaker> {
        if (baseline == null || basis != AppUsageBasis.DELTA) return emptyList()
        val byName = baseline.deviceWakers.associateBy { it.kind to it.name }
        val (kernels, reasons) = end.deviceWakers.mapNotNull { waker ->
            val base = byName[waker.kind to waker.name]
            if (base == null && baseline.wakersComplete != true) return@mapNotNull null
            waker.copy(count = (waker.count - (base?.count ?: 0L)).coerceAtLeast(0L),
                totalMs = (waker.totalMs - (base?.totalMs ?: 0L)).coerceAtLeast(0L))
        }.filter { it.count > 0 || it.totalMs > 0 }.partition { it.kind == "KERNEL_WAKELOCK" }
        // Reserve six time-ranked kernels and four count-ranked reasons; lend unused slots to the other kind.
        val selectedKernels = kernels.sortedWith(
            compareByDescending<DeviceWaker> { it.totalMs }.thenBy { it.name },
        ).take(6 + (4 - reasons.size).coerceAtLeast(0))
        val selectedReasons = reasons.sortedWith(
            compareByDescending<DeviceWaker> { it.count }.thenBy { it.name },
        ).take(10 - selectedKernels.size)
        return selectedKernels + selectedReasons
    }
}
