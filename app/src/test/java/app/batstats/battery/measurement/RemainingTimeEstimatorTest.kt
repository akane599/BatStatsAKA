package app.batstats.battery.measurement

import org.junit.Assert.*
import org.junit.Test

class RemainingTimeEstimatorTest {
    private fun point(minute: Int, charge: Long? = 4_000_000 - minute * 10_000L,
                      power: PowerState = PowerState.DISCHARGING) = Observation(
        minute * 60_000L, minute * 60_000L, minute * 60_000L, 80, charge, -600_000,
        4000, power, true, false, "one", expectedIntervalMs = 60_000
    )
    @Test fun estimateNeedsTenMinutesOfStableCounterData() {
        val estimator = RemainingTimeEstimator()
        for (minute in 0..9) assertNull(estimator.accept(point(minute)))
        val estimate = estimator.accept(point(10))!!
        assertEquals(390 * 60_000L, estimate.remainingMs)
        assertEquals(10 * 60_000L, estimate.observedMs)
    }
    @Test fun missingResetChargingAndLongGapInvalidateEstimate() {
        for (next in listOf(point(11, null), point(11, 5_000_000),
            point(11, power = PowerState.CHARGING), point(20))) {
            val estimator = RemainingTimeEstimator()
            for (minute in 0..10) estimator.accept(point(minute))
            assertNull(estimator.accept(next))
        }
    }
    @Test fun highlyVariableDischargeIsNotShownAsStable() {
        val estimator = RemainingTimeEstimator()
        for (minute in 0..9) estimator.accept(point(minute))
        assertNull(estimator.accept(point(10, 3_500_000)))
    }
}
