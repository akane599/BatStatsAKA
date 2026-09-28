package app.batstats.ui.components.chart

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Axis ticks every [step] from [min] to [max] (both multiples of [step]); [decimals] is what the labels need. */
@Immutable
data class NiceTicks(val min: Double, val max: Double, val step: Double) {
    val count: Int get() = if (step > 0.0) ((max - min) / step).roundToInt() + 1 else 1
    val decimals: Int get() = ChartMath.decimalsFor(step)

    fun valueAt(index: Int): Double = (min + index * step).let { if (it == 0.0) 0.0 else it }

    fun values(): List<Double> = List(count, ::valueAt)
}

/** Time-axis ticks: [values] (epoch ms, ascending) roughly [stepMs] apart, labelled at [granularity]. */
@Immutable
class TimeTicks(val stepMs: Long, val values: LongArray, val granularity: TimeGranularity)

/** Pure chart math: nice value ticks, calendar-aligned time ticks and min/max downsampling. No Android types. */
object ChartMath {
    private const val SECOND = 1_000L
    private const val MINUTE = 60 * SECOND
    private const val HOUR = 60 * MINUTE
    const val DAY_MS = 24 * HOUR
    private const val MONTH_NOMINAL = 30 * DAY_MS
    private const val EPSILON = 1e-9
    private const val MAX_TICKS = 1_000

    private val FIXED_STEPS = longArrayOf(
        SECOND, 2 * SECOND, 5 * SECOND, 10 * SECOND, 15 * SECOND, 30 * SECOND,
        MINUTE, 2 * MINUTE, 5 * MINUTE, 10 * MINUTE, 15 * MINUTE, 30 * MINUTE,
        HOUR, 2 * HOUR, 3 * HOUR, 6 * HOUR, 12 * HOUR,
    )
    private val DAY_STEPS = intArrayOf(1, 2, 7, 14)
    private val MONTH_STEPS = intArrayOf(1, 2, 3, 6, 12)

    // 2.5 only from 25 up: 0/25/50/75/100 reads well, 0/2.5/5 puts decimals on integer data.
    private val MANTISSAS = doubleArrayOf(1.0, 2.0, 2.5, 5.0)

    /**
     * At most [maxCount] (≥ 2) ticks on a 1/2/2.5/5 × 10ⁿ step, covering [min]..[max]. Equal bounds are padded
     * (±10 %, or ±1 around zero); non-finite input yields 0..1.
     */
    fun niceTicks(min: Double, max: Double, maxCount: Int): NiceTicks {
        val (lo, hi) = sanitize(min, max) ?: return NiceTicks(0.0, 1.0, 1.0)
        val count = maxCount.coerceAtLeast(2)
        forEachStep((hi - lo) / (count - 1)) { step ->
            val first = floor(lo / step + EPSILON) * step
            val last = ceil(hi / step - EPSILON) * step
            if (((last - first) / step).roundToInt() + 1 <= count) return NiceTicks(clean(first), clean(last), step)
        }
        return NiceTicks(lo, hi, hi - lo)
    }

    /** Exactly [count] (≥ 2) ticks on the smallest nice step that covers [min]..[max]; for a second, aligned axis. */
    fun alignedTicks(min: Double, max: Double, count: Int): NiceTicks {
        val (lo, hi) = sanitize(min, max) ?: return NiceTicks(0.0, 1.0, 1.0)
        val n = count.coerceAtLeast(2)
        forEachStep((hi - lo) / (n - 1)) { step ->
            val first = floor(lo / step + EPSILON) * step
            if (first + (n - 1) * step >= hi - EPSILON * step) return NiceTicks(clean(first), clean(first + (n - 1) * step), step)
        }
        return NiceTicks(lo, hi, (hi - lo) / (n - 1))
    }

    /**
     * Ticks for two axes that share gridlines: the same count (3..[maxCount]) on both, picked to waste the least
     * axis span beyond the data. An axis with bounds ([leftBounds] / [rightBounds], e.g. 0..100 for level %) never gets
     * a tick outside them: counts that would need one are skipped, and if every count does, the bounded axis is split
     * evenly over its bounds (3 ticks).
     */
    fun sharedTicks(
        left: ClosedFloatingPointRange<Double>,
        right: ClosedFloatingPointRange<Double>,
        maxCount: Int,
        leftBounds: ClosedFloatingPointRange<Double>? = null,
        rightBounds: ClosedFloatingPointRange<Double>? = null,
    ): Pair<NiceTicks, NiceTicks> {
        var best: Pair<NiceTicks, NiceTicks>? = null
        var bestWaste = Double.MAX_VALUE
        for (n in maxCount.coerceAtLeast(3) downTo 3) {
            val l = alignedTicks(left.start, left.endInclusive, n)
            val r = alignedTicks(right.start, right.endInclusive, n)
            if (!within(l, leftBounds) || !within(r, rightBounds)) continue
            val waste = waste(l, left) + waste(r, right)
            if (waste < bestWaste - EPSILON) {
                best = l to r
                bestWaste = waste
            }
        }
        return best ?: (fallbackTicks(left, leftBounds) to fallbackTicks(right, rightBounds))
    }

    private fun within(ticks: NiceTicks, bounds: ClosedFloatingPointRange<Double>?): Boolean {
        if (bounds == null) return true
        val tolerance = EPSILON * max(1.0, ticks.step)
        return ticks.min >= bounds.start - tolerance && ticks.max <= bounds.endInclusive + tolerance
    }

    private fun fallbackTicks(data: ClosedFloatingPointRange<Double>, bounds: ClosedFloatingPointRange<Double>?): NiceTicks {
        if (bounds == null || !(bounds.endInclusive > bounds.start)) return alignedTicks(data.start, data.endInclusive, 3)
        return NiceTicks(clean(bounds.start), clean(bounds.endInclusive), (bounds.endInclusive - bounds.start) / 2)
    }

    /** Fraction digits a label needs to show [step] exactly (0.25 → 2, 2.5 → 1, 500 → 0). */
    fun decimalsFor(step: Double): Int {
        if (!step.isFinite() || step <= 0.0) return 0
        var scaled = step
        var decimals = 0
        while (decimals < 10 && abs(scaled - Math.rint(scaled)) > 1e-6 * max(1.0, abs(scaled))) {
            scaled *= 10
            decimals++
        }
        return decimals
    }

    /**
     * At most [maxCount] time ticks in [startMs]..[endMs], aligned to wall-clock boundaries in [zone]: seconds to
     * 12 h steps on round local times, then local midnights (every 1/2 days, or Mondays weekly/fortnightly),
     * then month starts.
     */
    fun timeTicks(startMs: Long, endMs: Long, maxCount: Int, zone: TimeZone = TimeZone.getDefault()): TimeTicks {
        if (endMs <= startMs) return TimeTicks(0, LongArray(0), TimeGranularity.MINUTES)
        val count = maxCount.coerceAtLeast(1)
        val span = endMs - startMs
        FIXED_STEPS.firstOrNull { span / it + 1 <= count }?.let { step ->
            val granularity = when {
                step < MINUTE -> TimeGranularity.SECONDS
                step < HOUR -> TimeGranularity.MINUTES
                else -> TimeGranularity.HOURS
            }
            return TimeTicks(step, fixedTicks(startMs, endMs, step, zone), granularity)
        }
        DAY_STEPS.firstOrNull { span / (it * DAY_MS) + 1 <= count }?.let { days ->
            return TimeTicks(days * DAY_MS, dayTicks(startMs, endMs, days, zone), TimeGranularity.DAYS)
        }
        val months = MONTH_STEPS.firstOrNull { span / (it * MONTH_NOMINAL) + 1 <= count }
            ?: (ceil(span.toDouble() / (count * 12 * MONTH_NOMINAL)).toInt() * 12)
        return TimeTicks(months * MONTH_NOMINAL, monthTicks(startMs, endMs, months, zone), TimeGranularity.MONTHS)
    }

    /**
     * Reduces [points] (sorted by time) to at most four per time bucket: each bucket's first, lowest, highest and
     * last reading, in time order. Extremes survive, gap markers survive (repeats collapse), and a run split by a
     * gap is reduced on each side separately. Input with ≤ 4 × [buckets] points is returned as is.
     */
    fun downsampleMinMax(points: List<TimePoint>, buckets: Int): List<TimePoint> {
        if (buckets <= 0 || points.size <= buckets * 4) return points
        val start = points.first().timeMs
        val span = points.last().timeMs - start + 1
        val out = ArrayList<TimePoint>(buckets * 4 + 16)
        val run = BucketRun(out)
        var bucket = -1L
        for (point in points) {
            if (isGap(point)) {
                run.flush()
                if (out.isNotEmpty() && !isGap(out.last())) out += point
                continue
            }
            val index = (point.timeMs - start) * buckets / span
            if (index != bucket) {
                run.flush()
                bucket = index
            }
            run.add(point)
        }
        run.flush()
        return out
    }

    /** [downsampleMinMax] off the main thread; call it from the ViewModel or a `produceState` before charting. */
    suspend fun downsampleMinMaxAsync(
        points: List<TimePoint>,
        buckets: Int,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): List<TimePoint> = withContext(dispatcher) { downsampleMinMax(points, buckets) }

    /** Index of the first point at or after [timeMs] (points.size when none). */
    fun firstIndexAtOrAfter(points: List<TimePoint>, timeMs: Long): Int {
        var lo = 0
        var hi = points.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (points[mid].timeMs < timeMs) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * The reading closest in time to [timeMs] among its two neighbours, or -1 when there is none within
     * [maxDistanceMs]. A neighbour across a gap marker doesn't count: the readout never jumps over a break.
     */
    fun nearestIndex(points: List<TimePoint>, timeMs: Long, maxDistanceMs: Long = Long.MAX_VALUE): Int {
        val at = firstIndexAtOrAfter(points, timeMs)
        val after = if (at < points.size && !isGap(points[at])) at else -1
        val before = if (at > 0 && !isGap(points[at - 1])) at - 1 else -1
        val best = when {
            before < 0 && after < 0 -> return -1
            before < 0 -> after
            after < 0 -> before
            timeMs - points[before].timeMs <= points[after].timeMs - timeMs -> before
            else -> after
        }
        return if (abs(points[best].timeMs - timeMs) > maxDistanceMs) -1 else best
    }

    internal fun isGap(point: TimePoint): Boolean = point.value?.isFinite() != true

    private fun sanitize(a: Double, b: Double): Pair<Double, Double>? {
        if (!a.isFinite() || !b.isFinite()) return null
        var lo = min(a, b)
        var hi = max(a, b)
        if (hi - lo <= abs(hi) * EPSILON) {
            val pad = if (lo == 0.0) 1.0 else abs(lo) * 0.1
            lo -= pad
            hi += pad
        }
        return lo to hi
    }

    private inline fun forEachStep(rawStep: Double, action: (Double) -> Unit) {
        val exponent = floor(log10(rawStep)).toInt()
        for (e in exponent..exponent + 1) {
            val magnitude = 10.0.pow(e)
            for (mantissa in MANTISSAS) {
                val step = mantissa * magnitude
                if (mantissa == 2.5 && step < 10.0) continue
                if (step >= rawStep * (1 - EPSILON)) action(step)
            }
        }
        action(10.0.pow(exponent + 2))
    }

    private fun waste(ticks: NiceTicks, data: ClosedFloatingPointRange<Double>): Double {
        val span = ticks.max - ticks.min
        return if (span <= 0.0) 1.0 else 1.0 - (data.endInclusive - data.start) / span
    }

    private fun clean(value: Double): Double = if (value == 0.0) 0.0 else value

    private fun localToUtc(localMs: Long, zone: TimeZone): Long = localMs - zone.getOffset(localMs - zone.getOffset(localMs))

    private fun fixedTicks(startMs: Long, endMs: Long, step: Long, zone: TimeZone): LongArray {
        val ticks = ArrayList<Long>()
        var local = Math.floorDiv(startMs + zone.getOffset(startMs), step) * step
        while (ticks.size < MAX_TICKS) {
            val utc = localToUtc(local, zone)
            if (utc > endMs) break
            if (utc >= startMs && (ticks.isEmpty() || utc > ticks.last())) ticks += utc
            local += step
        }
        return ticks.toLongArray()
    }

    private fun dayTicks(startMs: Long, endMs: Long, days: Int, zone: TimeZone): LongArray {
        val ticks = ArrayList<Long>()
        var day = Math.floorDiv(startMs + zone.getOffset(startMs), DAY_MS)
        while (ticks.size < MAX_TICKS) {
            val utc = localToUtc(day * DAY_MS, zone)
            if (utc > endMs) break
            // Epoch day 4 (1970-01-05) was a Monday.
            val aligned = when (days) {
                1 -> true
                7, 14 -> Math.floorMod(day - 4, days.toLong()) == 0L
                else -> Math.floorMod(day, days.toLong()) == 0L
            }
            if (aligned && utc >= startMs) ticks += utc
            day++
        }
        return ticks.toLongArray()
    }

    private fun monthTicks(startMs: Long, endMs: Long, months: Int, zone: TimeZone): LongArray {
        val calendar = Calendar.getInstance(zone).apply {
            timeInMillis = startMs
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val ticks = ArrayList<Long>()
        while (ticks.size < MAX_TICKS && calendar.timeInMillis <= endMs) {
            val monthIndex = calendar.get(Calendar.YEAR) * 12 + calendar.get(Calendar.MONTH)
            if (calendar.timeInMillis >= startMs && monthIndex % months == 0) ticks += calendar.timeInMillis
            calendar.add(Calendar.MONTH, 1)
        }
        return ticks.toLongArray()
    }

    /** Accumulates one bucket's first/min/max/last reading and writes them out in time order. */
    private class BucketRun(private val out: MutableList<TimePoint>) {
        private var first: TimePoint? = null
        private var low: TimePoint? = null
        private var high: TimePoint? = null
        private var last: TimePoint? = null

        fun add(point: TimePoint) {
            val value = point.value ?: return
            if (first == null) first = point
            if (low.let { it == null || value < (it.value ?: value) }) low = point
            if (high.let { it == null || value > (it.value ?: value) }) high = point
            last = point
        }

        fun flush() {
            val start = first ?: return
            val kept = listOfNotNull(start, low, high, last).distinct().sortedBy { it.timeMs }
            out += kept
            first = null
            low = null
            high = null
            last = null
        }
    }
}
