package app.batstats.battery.measurement

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.exp

class DischargeEtaTest {
    private val eta = DischargeEta()
    private var elapsed = 0L
    private var uptime = 0L
    private var charge = 3_000_000L

    private fun point(power: PowerState = PowerState.DISCHARGING, boundary: Boundary = Boundary.SAMPLE,
                      generation: String = "one") = Observation(
        1_000_000 + elapsed, elapsed, uptime, 60, charge, -1, 4000, power, true, false, generation, 30_000, boundary,
    )

    /** [steps] captures 30 s apart at a steady [rateUa] drain (1000 µAh per step at 120 mA). */
    private fun run(steps: Int, rateUa: Long): EtaEstimate? {
        var estimate: EtaEstimate? = null
        repeat(steps) {
            elapsed += 30_000
            uptime += 30_000
            charge -= rateUa * 30_000 / 3_600_000
            estimate = eta.accept(point())
        }
        return estimate
    }

    private fun remainingAt(rateUa: Double) = charge * 3_600_000 / rateUa

    @Test fun withoutASeedTenMinutesOfLiveDataAreNeeded() {
        assertNull(eta.accept(point()))
        assertNull(run(19, 120_000))
        val estimate = run(1, 120_000)!!
        assertEquals(EtaBasis.LIVE_RATE, estimate.basis)
        assertEquals(600_000L, estimate.observedMs)
        assertEquals(120_000L, estimate.rateUa)
        assertEquals(remainingAt(120_000.0), estimate.remainingMs.toDouble(), 2.0)
    }

    @Test fun seedGivesATypicalEstimateUntilLiveDataDominates() {
        eta.seed(typicalDischargeUa = 200_000.0)
        val seeded = eta.accept(point())!!
        assertEquals(EtaBasis.TYPICAL_7D, seeded.basis)
        assertEquals(0L, seeded.observedMs)
        assertEquals(remainingAt(200_000.0), seeded.remainingMs.toDouble(), 2.0)
        val early = run(50, 120_000)!! // 25 min: the seed still outweighs live data
        assertEquals(EtaBasis.TYPICAL_7D, early.basis)
        assertTrue(early.rateUa!! in 120_001L..199_999L)
        val later = run(20, 120_000)!! // 35 min: live data dominates (τ ln 2 ≈ 31 min)
        assertEquals(EtaBasis.LIVE_RATE, later.basis)
        assertEquals(2_100_000L, later.observedMs)
    }

    @Test fun seedIsIgnoredOnceLiveDataExists() {
        eta.accept(point())
        run(20, 120_000)
        eta.seed(typicalDischargeUa = 500_000.0)
        assertEquals(120_000L, run(1, 120_000)?.rateUa)
    }

    @Test fun olderRatesDecayWithATauOfFortyFiveMinutes() {
        eta.accept(point())
        run(900, 120_000) // 10 τ at 120 mA
        val estimate = run(90, 360_000)!! // then exactly τ at 360 mA
        val expected = 120_000 * exp(-1.0) + 360_000 * (1 - exp(-1.0))
        assertEquals(expected, estimate.rateUa!!.toDouble(), 500.0)
        assertEquals(remainingAt(expected), estimate.remainingMs.toDouble(), remainingAt(expected) * 0.003)
    }

    @Test fun sleepGapIsSkippedNotAReset() {
        eta.accept(point())
        val before = run(40, 120_000)!!
        // Two hours asleep between two polls: elapsed far beyond 3 × the 30 s interval.
        elapsed += 7_200_000
        uptime += 30_000
        charge -= 50_000
        val afterSleep = eta.accept(point())!!
        assertEquals(before.rateUa, afterSleep.rateUa)
        assertEquals(before.observedMs, afterSleep.observedMs)
        assertEquals(EtaBasis.LIVE_RATE, afterSleep.basis)
        assertEquals(remainingAt(120_000.0), afterSleep.remainingMs.toDouble(), 2.0)
        assertEquals(before.observedMs + 30_000, run(1, 120_000)?.observedMs)
    }

    @Test fun gapsRestartsAndCounterResetsAreSkippedWithoutLosingTheTrend() {
        eta.accept(point())
        run(40, 120_000)
        elapsed += 30_000; uptime += 30_000; charge -= 20_000
        assertEquals(120_000L, eta.accept(point(boundary = Boundary.GAP))?.rateUa)
        elapsed += 30_000; uptime += 30_000; charge -= 20_000
        assertEquals(120_000L, eta.accept(point(generation = "two"))?.rateUa)
        elapsed += 30_000; uptime += 30_000; charge += 500_000 // counter reset upward
        assertEquals(120_000L, eta.accept(point(generation = "two"))?.rateUa)
    }

    @Test fun chargingHidesTheEstimateButKeepsTheTrend() {
        eta.accept(point())
        run(40, 120_000)
        elapsed += 30_000; uptime += 30_000; charge += 10_000
        assertNull(eta.accept(point(PowerState.CHARGING)))
        elapsed += 30_000; uptime += 30_000
        val unplugged = eta.accept(point())!!
        assertEquals(120_000L, unplugged.rateUa)
        assertEquals(EtaBasis.LIVE_RATE, unplugged.basis)
    }

    @Test fun implausibleEstimatesAreHidden() {
        eta.seed(typicalDischargeUa = 1_000.0) // 3 Ah at 1 mA: longer than a week
        assertNull(eta.accept(point()))
        val noCounter = DischargeEta()
        noCounter.seed(typicalDischargeUa = 200_000.0)
        assertNull(noCounter.accept(point().copy(chargeUah = null)))
        assertNotNull(noCounter.accept(point()))
    }

    @Test fun resetDropsLiveDataAndSeed() {
        eta.seed(typicalDischargeUa = 200_000.0)
        eta.accept(point())
        run(40, 120_000)
        eta.reset()
        assertNull(eta.accept(point()))
    }
}
