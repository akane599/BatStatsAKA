package app.batstats.battery.measurement

import app.batstats.battery.data.db.DailySummary
import java.time.Instant
import java.time.ZoneId

/**
 * What one stored interval adds to the daily summaries: from the previous persisted sample
 * ([startWallMs]) to this one ([endWallMs], whose reading is [endLevelPercent] and
 * [endTemperatureDeciC]). Durations in ms are discharging time by screen state; charges in µAh
 * are ≥ 0; [cpuSuspendMs] is non-null only when the interval was discharging.
 */
data class DayInterval(
    val startWallMs: Long,
    val endWallMs: Long,
    val screenOnMs: Long = 0,
    val screenOffMs: Long = 0,
    val screenOnDischargeUah: Long = 0,
    val screenOffDischargeUah: Long = 0,
    val chargedUah: Long = 0,
    val cpuSuspendMs: Long? = null,
    val endLevelPercent: Int? = null,
    val endTemperatureDeciC: Int? = null,
)

/**
 * Folds intervals into one [DailySummary] row per local day. An interval crossing local midnight
 * is split in proportion to the wall time on each side, with day boundaries from the zone's
 * rules, so 23 h and 25 h DST days get their real share. The end reading updates only its own day.
 */
object DailySummaryAggregator {
    fun epochDay(wallMs: Long, zone: ZoneId): Long = Instant.ofEpochMilli(wallMs).atZone(zone).toLocalDate().toEpochDay()

    /**
     * The interval ObservationEngine accounted between two summaries: [before] as it stood at the
     * previous persisted sample, [after] including this one (its `latest` is the end reading).
     * Gaps, restarts and resets add nothing. Null when [after] has no observation.
     */
    fun interval(before: ObservationSummary, after: ObservationSummary, endTemperatureDeciC: Int?): DayInterval? {
        val end = after.latest ?: return null
        fun grew(now: Long, then: Long) = (now - then).coerceAtLeast(0)
        val dischargeMs = grew(after.discharge.durationMs, before.discharge.durationMs)
        return DayInterval(
            startWallMs = before.latest?.wallMs ?: end.wallMs,
            endWallMs = end.wallMs,
            screenOnMs = grew(after.screenOn.durationMs, before.screenOn.durationMs),
            screenOffMs = grew(after.screenOff.durationMs, before.screenOff.durationMs),
            screenOnDischargeUah = grew(after.screenOn.chargeChangeUah, before.screenOn.chargeChangeUah),
            screenOffDischargeUah = grew(after.screenOff.chargeChangeUah, before.screenOff.chargeChangeUah),
            chargedUah = grew(after.charging.chargeChangeUah, before.charging.chargeChangeUah),
            cpuSuspendMs = if (dischargeMs > 0) grew(after.cpuSuspendMs, before.cpuSuspendMs) else null,
            endLevelPercent = end.level,
            endTemperatureDeciC = endTemperatureDeciC,
        )
    }

    /** The days [interval] touches: load these rows before [apply]. */
    fun days(interval: DayInterval, zone: ZoneId): LongRange = split(interval, zone).let { it.first().first..it.last().first }

    /**
     * Adds [interval] to [rows] (existing rows by epochDay; missing days start empty) and returns
     * every touched row with [updatedAt], in day order. Upsert them with the sample.
     */
    fun apply(rows: Map<Long, DailySummary>, interval: DayInterval, zone: ZoneId, updatedAt: Long): List<DailySummary> {
        val segments = split(interval, zone)
        val weights = segments.map { it.second }
        val screenOn = apportion(interval.screenOnMs, weights)
        val screenOff = apportion(interval.screenOffMs, weights)
        val screenOnUah = apportion(interval.screenOnDischargeUah, weights)
        val screenOffUah = apportion(interval.screenOffDischargeUah, weights)
        val charged = apportion(interval.chargedUah, weights)
        val suspend = interval.cpuSuspendMs?.let { apportion(it, weights) }
        val endDay = segments.last().first
        return segments.mapIndexed { i, (day, _) ->
            val row = rows[day] ?: DailySummary(epochDay = day)
            val added = row.copy(
                screenOnMs = row.screenOnMs + screenOn[i],
                screenOffMs = row.screenOffMs + screenOff[i],
                screenOnDischargeUah = row.screenOnDischargeUah + screenOnUah[i],
                screenOffDischargeUah = row.screenOffDischargeUah + screenOffUah[i],
                chargedUah = row.chargedUah + charged[i],
                cpuSuspendMs = suspend?.let { (row.cpuSuspendMs ?: 0L) + it[i] } ?: row.cpuSuspendMs,
                updatedAt = updatedAt,
            )
            if (day == endDay) added.withReading(interval.endLevelPercent, interval.endTemperatureDeciC) else added
        }
    }

    /**
     * Typical discharge rate in µA across [days] (e.g. the last 7): discharge ÷ discharging time.
     * Null under one hour of discharging or without any discharge. Seeds [DischargeEta].
     */
    fun typicalDischargeUa(days: List<DailySummary>): Double? {
        val ms = days.sumOf { it.screenOnMs + it.screenOffMs }
        val uah = days.sumOf { it.screenOnDischargeUah + it.screenOffDischargeUah }
        return if (ms >= 3_600_000 && uah > 0) uah * 3_600_000.0 / ms else null
    }

    private fun DailySummary.withReading(level: Int?, temperatureDeciC: Int?) = copy(
        minLevel = listOfNotNull(minLevel, level).minOrNull(),
        maxLevel = listOfNotNull(maxLevel, level).maxOrNull(),
        peakTemperatureDeciC = listOfNotNull(peakTemperatureDeciC, temperatureDeciC).maxOrNull(),
    )

    /**
     * (epochDay, wall ms on that day) for each local day the interval covers. An interval with
     * nothing to add, or running backwards, is a single zero-length segment on the end day.
     */
    private fun split(interval: DayInterval, zone: ZoneId): List<Pair<Long, Long>> {
        val start = interval.startWallMs
        val end = interval.endWallMs
        val empty = interval.screenOnMs == 0L && interval.screenOffMs == 0L && interval.screenOnDischargeUah == 0L &&
            interval.screenOffDischargeUah == 0L && interval.chargedUah == 0L && (interval.cpuSuspendMs ?: 0L) == 0L
        if (empty || end <= start) return listOf(epochDay(end, zone) to 0L)
        val segments = mutableListOf<Pair<Long, Long>>()
        var date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        var cursor = start
        while (cursor < end) {
            val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val segmentEnd = minOf(end, nextMidnight)
            segments += date.toEpochDay() to segmentEnd - cursor
            cursor = segmentEnd
            date = date.plusDays(1)
        }
        if (segments.last().first != epochDay(end, zone)) segments += epochDay(end, zone) to 0L
        return segments
    }

    /** Splits [value] by [weights], flooring each share; the last share takes the remainder. */
    private fun apportion(value: Long, weights: List<Long>): List<Long> {
        val total = weights.sum()
        if (total <= 0) return List(weights.size) { if (it == weights.lastIndex) value else 0 }
        val shares = weights.dropLast(1).map { (value.toDouble() * it / total).toLong() }
        return shares + (value - shares.sum())
    }
}
