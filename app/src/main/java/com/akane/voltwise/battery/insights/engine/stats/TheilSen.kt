package com.akane.voltwise.battery.insights.engine.stats

import kotlin.math.sqrt

object TheilSen {
    data class Trend(val slope: Double, val mannKendallZ: Double)

    /** Median pairwise slope, in value per millisecond. Equal timestamps cannot define a slope. */
    fun slope(values: List<TimedValue>): Double? = trend(values)?.slope

    /** Slope and continuity-corrected Mann–Kendall z use finite pairs at distinct timestamps. */
    fun trend(values: List<TimedValue>): Trend? {
        val valid = values.filter { it.value.isFinite() }.sortedBy { it.atMs }
        val slopes = mutableListOf<Double>()
        var score = 0.0
        for (left in valid.indices) {
            for (right in left + 1 until valid.size) {
                val elapsed = valid[right].atMs.toDouble() - valid[left].atMs.toDouble()
                if (elapsed > 0.0) {
                    val slope = (valid[right].value - valid[left].value) / elapsed
                    if (slope.isFinite()) {
                        slopes += slope
                        score += when {
                            valid[right].value > valid[left].value -> 1.0
                            valid[right].value < valid[left].value -> -1.0
                            else -> 0.0
                        }
                    }
                }
            }
        }
        val slope = median(slopes) ?: return null
        val n = valid.size.toDouble()
        // Uncorrected variance is conservative when capacity values tie.
        val variance = n * (n - 1) * (2 * n + 5) / 18.0
        val correctedScore = when {
            score < 0.0 -> score + 1.0
            score > 0.0 -> score - 1.0
            else -> 0.0
        }
        return Trend(slope, correctedScore / sqrt(variance))
    }
}
