package com.akane.voltwise.battery.measurement

import com.akane.voltwise.battery.measurement.CapacityBasis.COUNTER_SPAN
import com.akane.voltwise.battery.measurement.CapacityBasis.SYSFS
import com.akane.voltwise.battery.measurement.CapacityConfidence.HIGH
import com.akane.voltwise.battery.measurement.CapacityConfidence.LOW
import com.akane.voltwise.battery.measurement.CapacityConfidence.MEDIUM
import org.junit.Assert.*
import org.junit.Test

class CapacityEstimatorTest {
    private fun session(deltaUah: Long?, start: Int?, end: Int?, covered: Double = 1.0) =
        CapacityEstimator.fromSession(deltaUah, start, end, observedMs = 3_600_000, counterCoveredMs = (3_600_000 * covered).toLong())

    @Test fun fullyCoveredCounterSpanScalesToAFullChargeWithUnchangedConfidenceTiers() {
        assertEquals(CapacityEstimate(5_000_000, HIGH, COUNTER_SPAN), session(2_000_000, 50, 90))
        assertEquals(CapacityEstimate(5_000_000, HIGH, COUNTER_SPAN), session(2_000_000, 90, 50)) // discharge
        assertEquals(CapacityEstimate(5_000_000, MEDIUM, COUNTER_SPAN), session(1_000_000, 60, 80))
        assertEquals(CapacityEstimate(5_000_000, LOW, COUNTER_SPAN), session(750_000, 70, 85))
        assertEquals(5_000, session(750_000, 70, 85)?.fullMah)
    }

    @Test fun partialCounterChargeCannotEstimateCapacityOverTheWholeLevelSpan() {
        // A 4,000 mAh battery changing by 40 points: only the covered fraction of 1,600 mAh is known.
        assertNull(session(1_200_000, 50, 90, covered = 0.75))
        assertNull(session(1_440_000, 50, 90, covered = 0.9))
        assertNull(session(1_200_000, 90, 50, covered = 0.75))
        assertNull(session(1_440_000, 90, 50, covered = 0.9))
        assertEquals(CapacityEstimate(4_000_000, HIGH, COUNTER_SPAN), session(1_600_000, 90, 50))
    }

    @Test fun fullCoverageBoundaryHasZeroMillisecondTolerance() {
        assertNull(CapacityEstimator.fromSession(1_600_000, 90, 50, observedMs = 3_600_000, counterCoveredMs = 3_599_999))
        assertEquals(
            CapacityEstimate(4_000_000, HIGH, COUNTER_SPAN),
            CapacityEstimator.fromSession(1_600_000, 90, 50, observedMs = 3_600_000, counterCoveredMs = 3_600_000),
        )
        assertEquals(
            CapacityEstimate(4_000_000, HIGH, COUNTER_SPAN),
            CapacityEstimator.fromSession(1_600_000, 90, 50, observedMs = 3_600_000, counterCoveredMs = 3_600_001),
        )
    }

    @Test fun shortSpansPoorCoverageAndImplausibleValuesGiveNoEstimate() {
        assertNull(session(450_000, 70, 79))
        assertNull(session(2_000_000, 50, 90, covered = 0.7))
        assertNull(session(null, 50, 90))
        assertNull(session(0, 50, 90))
        assertNull(session(2_000_000, null, 90))
        assertNull(session(40_000, 50, 90)) // 100 mAh "battery": a counter in mAh or a reset
        assertNull(session(30_000_000, 50, 90)) // 75 Ah
        assertNull(CapacityEstimator.fromSession(2_000_000, 50, 90, observedMs = 0, counterCoveredMs = 0))
    }

    @Test fun sysfsFullChargeIsAMediumConfidenceSourceAndNeverScaled() {
        assertEquals(CapacityEstimate(4_800_000, MEDIUM, SYSFS), CapacityEstimator.fromSysfs(4_800_000))
        assertNull(CapacityEstimator.fromSysfs(4_800)) // looks like mAh: rejected, not multiplied
        assertNull(CapacityEstimator.fromSysfs(null))
    }

    @Test fun designCapacityOverrideWinsOverSysfs() {
        assertEquals(4_500_000L, CapacityEstimator.designUah(overrideMah = 4_500, chargeFullDesignUah = 5_000_000))
        assertEquals(5_000_000L, CapacityEstimator.designUah(overrideMah = 0, chargeFullDesignUah = 5_000_000))
        assertNull(CapacityEstimator.designUah(overrideMah = 0, chargeFullDesignUah = 5_000))
        assertNull(CapacityEstimator.designUah(overrideMah = 0, chargeFullDesignUah = null))
    }

    @Test fun healthIsEstimateOverDesign() {
        assertEquals(90.0, CapacityEstimator.healthPercent(4_500_000, 5_000_000)!!, 1e-9)
        assertEquals(104.0, CapacityEstimator.healthPercent(5_200_000, 5_000_000)!!, 1e-9)
        assertNull(CapacityEstimator.healthPercent(4_500_000, null))
        assertNull(CapacityEstimator.healthPercent(4_500_000, 0))
    }

    @Test fun combinedValueIsTheConfidenceWeightedMedian() {
        val estimates = listOf(
            CapacityEstimate(4_400_000, LOW, COUNTER_SPAN),
            CapacityEstimate(4_900_000, HIGH, COUNTER_SPAN),
            CapacityEstimate(4_000_000, LOW, COUNTER_SPAN),
            CapacityEstimate(4_800_000, HIGH, COUNTER_SPAN),
            CapacityEstimate(4_200_000, LOW, COUNTER_SPAN),
        )
        // Plain median: 4.4 Ah. Weights 1/1/1/3/3 move it to 4.8 Ah.
        assertEquals(CapacityEstimate(4_800_000, HIGH, COUNTER_SPAN), CapacityEstimator.combine(estimates))
        assertNull(CapacityEstimator.combine(emptyList()))
    }

    @Test fun combinedConfidenceIsTheStrongestEstimateNearTheMedian() {
        val scattered = listOf(
            CapacityEstimate(4_000_000, MEDIUM, SYSFS),
            CapacityEstimate(4_100_000, LOW, COUNTER_SPAN),
            CapacityEstimate(6_000_000, HIGH, COUNTER_SPAN),
        )
        assertEquals(CapacityEstimate(4_100_000, MEDIUM, COUNTER_SPAN), CapacityEstimator.combine(scattered))
    }
}
