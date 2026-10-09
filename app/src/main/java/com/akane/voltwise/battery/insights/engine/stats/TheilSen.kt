package com.akane.voltwise.battery.insights.engine.stats

object TheilSen {
    data class Trend(val slope: Double, val negativeSlopeShare: Double)

    /** Median pairwise slope, in value per millisecond. Equal timestamps cannot define a slope. */
    fun slope(values: List<TimedValue>): Double? = trend(values)?.slope

    /** Slope and negative share use the same finite pairs at distinct timestamps. */
    fun trend(values: List<TimedValue>): Trend? {
        val valid = values.filter { it.value.isFinite() }.sortedBy { it.atMs }
        val slopes = mutableListOf<Double>()
        for (left in valid.indices) {
            for (right in left + 1 until valid.size) {
                val elapsed = valid[right].atMs.toDouble() - valid[left].atMs.toDouble()
                if (elapsed > 0.0) {
                    val slope = (valid[right].value - valid[left].value) / elapsed
                    if (slope.isFinite()) slopes += slope
                }
            }
        }
        val slope = median(slopes) ?: return null
        return Trend(slope, slopes.count { it < 0.0 }.toDouble() / slopes.size)
    }
}
