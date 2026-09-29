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

    /** Moves 30 s ahead, awake, at a steady [rateUa] drain (1000 µAh per step at 120 mA). */
    private fun advance(rateUa: Long) {
        elapsed += 30_000
        uptime += 30_000
        charge -= rateUa * 30_000 / 3_600_000
    }

    /** [steps] captures 30 s apart at a steady [rateUa] drain. */
    private fun run(steps: Int, rateUa: Long): EtaEstimate? {
        var estimate: EtaEstimate? = null
        repeat(steps) {
            advance(rateUa)
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

    @Test fun lateSeedWeighsAsIfGivenBeforeTheFirstCapture() {
        val early = DischargeEta().apply { seed(typicalDischargeUa = 200_000.0) }
        val late = DischargeEta()
        fun both() = early.accept(point()) to late.accept(point())
        both()
        repeat(20) { advance(120_000); both() }
        assertEquals(EtaBasis.LIVE_RATE, late.accept(point())?.basis) // unseeded after 10 min
        late.seed(typicalDischargeUa = 200_000.0) // e.g. the 7-day query finished late
        assertEquals(EtaBasis.TYPICAL_7D, late.accept(point())?.basis)
        var result = both()
        repeat(30) { advance(120_000); result = both() }
        val (fromEarly, fromLate) = result
        assertEquals(fromEarly!!.rateUa!!.toDouble(), fromLate!!.rateUa!!.toDouble(), 1.0)
        assertEquals(fromEarly.basis, fromLate.basis)

        val reseeded = DischargeEta().apply { seed(200_000.0); seed(100_000.0); seed(null); seed(-5.0) }
        assertEquals(100_000L, reseeded.accept(point())?.rateUa)
    }

    @Test fun olderRatesDecayWithATauOfFortyFiveMinutes() {
        eta.accept(point())
        run(900, 120_000) // 10 τ at 120 mA
        val estimate = run(90, 360_000)!! // then exactly τ at 360 mA
        val expected = 120_000 * exp(-1.0) + 360_000 * (1 - exp(-1.0))
        assertEquals(expected, estimate.rateUa!!.toDouble(), 500.0)
        assertEquals(remainingAt(expected), estimate.remainingMs.toDouble(), remainingAt(expected) * 0.003)
    }

    @Test fun suspendedIntervalIsRealDrainAndCounts() {
        eta.accept(point())
        val before = run(40, 120_000)!!
        // Two hours of CPU suspend between two polls (30 s awake): 50 mAh is 25 mA of idle drain.
        elapsed += 7_200_000
        uptime += 30_000
        charge -= 50_000
        val afterSleep = eta.accept(point())!!
        assertEquals(before.observedMs + 7_200_000, afterSleep.observedMs)
        assertTrue(afterSleep.rateUa!! in 25_000L..30_000L) // the long interval dominates the time weighting
        assertEquals(EtaBasis.LIVE_RATE, afterSleep.basis)
    }

    @Test fun observationGapIsSkippedNotAReset() {
        eta.accept(point())
        val before = run(40, 120_000)!!
        // Ten awake minutes without a capture: beyond max(3 × 30 s, 120 s), so not observed.
        elapsed += 600_000
        uptime += 600_000
        charge -= 100_000
        val afterGap = eta.accept(point())!!
        assertEquals(before.rateUa, afterGap.rateUa)
        assertEquals(before.observedMs, afterGap.observedMs)
        assertEquals(EtaBasis.LIVE_RATE, afterGap.basis)
        assertEquals(remainingAt(120_000.0), afterGap.remainingMs.toDouble(), 2.0)
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
