package app.batstats.battery.measurement

import org.junit.Assert.*
import org.junit.Test

class ChargeEtaTest {
    private val ac = 1
    private val usb = 2
    private var elapsed = 0L
    private var charge = 2_500_000L // 5 Ah battery: 50 000 µAh per percent, starting at 50 %

    private fun level() = (charge / 50_000).toInt()
    private fun point(power: PowerState = PowerState.CHARGING, boundary: Boundary = Boundary.SAMPLE) = Observation(
        1_000_000 + elapsed, elapsed, elapsed, level(), charge, 1_500_000, 4200, power, true, false, "one", 30_000, boundary,
    )

    /** [steps] captures 30 s apart; 12 500 µAh per step is 1.5 A (1 % per 2 min). */
    private fun ChargeEta.feed(steps: Int, plugged: Int = ac, android: Long? = null,
                               stepUah: () -> Long = { 12_500 }): EtaEstimate? {
        var estimate: EtaEstimate? = null
        repeat(steps) {
            elapsed += 30_000
            charge += stepUah()
            estimate = accept(point(if (level() >= 100) PowerState.PLUGGED else PowerState.CHARGING), plugged, android)
        }
        return estimate
    }

    /** From the current level to 100 %: 1 % per 2 min below 80 %, then 1 % per [taperMsPerPercent]. */
    private fun ChargeEta.chargeToFull(plugged: Int, taperMsPerPercent: Long) {
        val taperStepUah = 50_000 * 30_000 / taperMsPerPercent
        while (level() < 100) feed(1, plugged) { if (level() >= 80) taperStepUah else 12_500 }
    }

    @Test fun androidEstimateWinsWhenAvailable() {
        val eta = ChargeEta()
        assertEquals(EtaEstimate(3_600_000, EtaBasis.ANDROID, 0, null), eta.accept(point(), ac, androidRemainingMs = 3_600_000))
        assertNull(eta.accept(point(), ac, androidRemainingMs = -1))
        val model = eta.feed(12, android = 0)!!
        assertEquals(EtaBasis.LIVE_RATE, model.basis)
    }

    @Test fun liveRateRunsToEightyThenHalfRateTaperUntilOneIsLearned() {
        val eta = ChargeEta()
        eta.accept(point(), ac, null)
        assertNull(eta.feed(9)) // under 5 minutes of live data
        val estimate = eta.feed(3)!! // 53 %, 1.5 A → 2 min per percent
        assertEquals(53, level())
        assertEquals(EtaEstimate(27 * 120_000L + 20 * 240_000L, EtaBasis.LIVE_RATE, 360_000, 1_500_000), estimate)
    }

    @Test fun aboveEightyPercentTheLiveRateAlreadyIncludesTheTaper() {
        charge = 4_250_000
        val eta = ChargeEta()
        eta.accept(point(), ac, null)
        val estimate = eta.feed(12)!!
        assertEquals(88, level())
        assertEquals(12 * 120_000L, estimate.remainingMs)
        assertEquals(EtaBasis.LIVE_RATE, estimate.basis)
    }

    @Test fun learnedTaperForThisChargerGivesTheTaperModel() {
        val eta = ChargeEta(learnedTaperMsPerPercent = mapOf(ac to 300_000L))
        eta.accept(point(), ac, null)
        val estimate = eta.feed(12)!!
        assertEquals(EtaEstimate(27 * 120_000L + 20 * 300_000L, EtaBasis.TAPER_MODEL, 360_000, 1_500_000), estimate)

        val usbEta = ChargeEta(learnedTaperMsPerPercent = mapOf(ac to 300_000L))
        usbEta.accept(point(), usb, null)
        assertEquals(EtaBasis.LIVE_RATE, usbEta.feed(12, usb)?.basis)
    }

    @Test fun taperIsLearnedPerChargerFromChargesThatReachFull() {
        charge = 3_900_000 // 78 %
        val eta = ChargeEta(learnedTaperMsPerPercent = mapOf(ac to 111_000L))
        eta.accept(point(), usb, null)
        eta.chargeToFull(usb, taperMsPerPercent = 300_000)
        assertEquals(mapOf(ac to 111_000L, usb to 300_000L), eta.learnedTaperMsPerPercent)

        elapsed += 30_000; charge = 3_900_000
        eta.accept(point(PowerState.DISCHARGING), 0, null) // unplugged, drained, plugged back in
        eta.accept(point(), usb, null)
        eta.chargeToFull(usb, taperMsPerPercent = 150_000)
        assertEquals(225_000L, eta.learnedTaperMsPerPercent[usb]) // blended with the earlier charge
    }

    @Test fun partialChargesAndInterruptedTapersTeachNothing() {
        charge = 3_900_000
        val eta = ChargeEta()
        eta.accept(point(), usb, null)
        eta.feed(8, usb) // 80 %
        eta.feed(150, usb) { 5_000 } // 95 %
        eta.accept(point(PowerState.DISCHARGING), 0, null)
        assertTrue(eta.learnedTaperMsPerPercent.isEmpty())

        charge = 3_900_000
        eta.accept(point(), usb, null)
        eta.feed(8, usb)
        eta.feed(50, usb) { 5_000 }
        elapsed += 30_000
        eta.accept(point(boundary = Boundary.GAP), usb, null) // collection interrupted mid-taper
        eta.chargeToFull(usb, taperMsPerPercent = 300_000) // 85 → 100 %: the step into 80 % was not observed
        assertTrue(eta.learnedTaperMsPerPercent.isEmpty())
    }

    @Test fun anotherChargerRestartsTheLiveRate() {
        val eta = ChargeEta()
        eta.accept(point(), ac, null)
        assertNotNull(eta.feed(12, ac))
        assertNull(eta.feed(1, usb))
        assertNull(eta.feed(9, usb))
        assertNotNull(eta.feed(1, usb))
    }

    @Test fun notChargingFullOrTooLowGivesNoModelEstimate() {
        val eta = ChargeEta()
        eta.accept(point(), ac, null)
        eta.feed(12)
        elapsed += 30_000
        assertNull(eta.accept(point(PowerState.PLUGGED), ac, null))
        assertNull(eta.accept(point(PowerState.DISCHARGING), ac, null))
        assertNull(eta.accept(point().copy(level = 100), ac, null))
        assertNull(eta.accept(point().copy(level = 5), ac, null))
    }
}
