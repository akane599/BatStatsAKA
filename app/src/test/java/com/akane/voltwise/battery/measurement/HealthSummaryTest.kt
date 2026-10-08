package com.akane.voltwise.battery.measurement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthSummaryTest {
    @Test fun storedEstimatesParseTolerantly() {
        assertEquals(
            CapacityEstimate(4_200_000, CapacityConfidence.MEDIUM, CapacityBasis.SYSFS),
            HealthSummary.storedEstimate(4_200, "MEDIUM", "SYSFS"),
        )
        // Unknown basis → counter span; unknown or missing confidence, or no value → no estimate.
        assertEquals(CapacityBasis.COUNTER_SPAN, HealthSummary.storedEstimate(4_200, "HIGH", "FUTURE")!!.basis)
        assertNull(HealthSummary.storedEstimate(4_200, "GARBAGE", "COUNTER_SPAN"))
        assertNull(HealthSummary.storedEstimate(4_200, null, null))
        assertNull(HealthSummary.storedEstimate(null, "HIGH", null))
        assertNull(HealthSummary.storedEstimate(0, "HIGH", null))
    }

    @Test fun theWeightedMedianAndHealthAgainstTheDesignOverride() {
        val estimates = listOf(
            CapacityEstimate(4_200_000, CapacityConfidence.MEDIUM, CapacityBasis.COUNTER_SPAN),
            CapacityEstimate(4_100_000, CapacityConfidence.HIGH, CapacityBasis.COUNTER_SPAN),
            CapacityEstimate(4_300_000, CapacityConfidence.LOW, CapacityBasis.COUNTER_SPAN),
        )
        with(HealthSummary.of(estimates, designOverrideMah = 0)!!) {
            assertEquals(4_100, estimate.fullMah)
            assertEquals(CapacityConfidence.HIGH, estimate.confidence)
            assertNull(designUah)
            assertNull(healthPercent)
        }
        with(HealthSummary.of(estimates, designOverrideMah = 5_000)!!) {
            assertEquals(5_000_000L, designUah)
            assertEquals(82.0, healthPercent!!, 1e-9)
        }
        // Sysfs design counts when Settings is on Auto.
        assertEquals(82.0, HealthSummary.of(estimates, 0, chargeFullDesignUah = 5_000_000)!!.healthPercent!!, 1e-9)
        assertNull(HealthSummary.of(emptyList(), 5_000))
    }

    @Test fun theCounterFullChargeNeedsAPlausibleValueAndTenPercent() {
        assertEquals(4_000_000L, HealthSummary.counterFullUah(2_800_000, 70))
        assertNull(HealthSummary.counterFullUah(400_000, 9))
        assertNull(HealthSummary.counterFullUah(10_000, 100)) // 10 mAh: the emulator's fake counter
        assertNull(HealthSummary.counterFullUah(null, 70))
        assertNull(HealthSummary.counterFullUah(2_800_000, null))
    }
}
