package com.akane.voltwise.battery.insights.engine.stats

object TheilSen {
    /** Median pairwise slope, in value per millisecond. Equal timestamps cannot define a slope. */
    fun slope(values: List<TimedValue>): Double? {
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
        return median(slopes)
    }
}
