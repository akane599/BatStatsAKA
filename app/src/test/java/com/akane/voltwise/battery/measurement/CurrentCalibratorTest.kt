package com.akane.voltwise.battery.measurement

import com.akane.voltwise.battery.measurement.PowerState.CHARGING
import com.akane.voltwise.battery.measurement.PowerState.DISCHARGING
import org.junit.Assert.*
import org.junit.Test

class CurrentCalibratorTest {
    private val calibrator = CurrentCalibrator()
    private var elapsed = 0L
    private var uptime = 0L
    private var charge = 4_000_000L
    private val microamps = CurrentCalibration(CurrentUnit.MICROAMPS, CurrentSign.NORMAL)
    private val milliamps = CurrentCalibration(CurrentUnit.MILLIAMPS, CurrentSign.NORMAL)
    private val asMilliamps: (Long) -> Long = { it / 1000 }

    private fun reading(raw: Long?, power: PowerState, boundary: Boundary = Boundary.SAMPLE) = Observation(
        1_000_000 + elapsed, elapsed, uptime, 80, charge, raw, 4000, power, true, false, "one", 30_000, boundary,
    )

    private fun start(trueUa: Long = -300_000, report: (Long) -> Long = { it }) =
        calibrator.accept(reading(report(trueUa), if (trueUa < 0) DISCHARGING else CHARGING), if (trueUa < 0) 0 else 1)

    /**
     * Feeds [intervals] 30 s captures while [trueUa] (Android sign) flows, reported as report(trueUa).
     * The default 20 intervals at 300 mA close exactly one window (10 min, 50 mAh).
     */
    private fun feed(intervals: Int = 20, trueUa: Long = -300_000, report: (Long) -> Long = { it },
                     awakeShare: Double = 1.0, boundary: Boundary = Boundary.SAMPLE): CalibrationDecision? {
        var decision: CalibrationDecision? = null
        repeat(intervals) {
            elapsed += 30_000
            uptime += (30_000 * awakeShare).toLong()
            charge += trueUa * 30_000 / 3_600_000
            decision = calibrator.accept(reading(report(trueUa), if (trueUa < 0) DISCHARGING else CHARGING, boundary), if (trueUa < 0) 0 else 1)
        }
        return decision
    }

    /** One capture whose interval is a GAP, so a change of reporting does not straddle a window. */
    private fun gap(trueUa: Long = -300_000, report: (Long) -> Long = { it }) =
        feed(1, trueUa, report, boundary = Boundary.GAP)

    /** One capture [ms] later; the counter falls [counterDropUah] (unplugged unless [plugged] says otherwise). */
    private fun step(ms: Long, raw: Long?, power: PowerState = DISCHARGING, plugged: Int? = if (power == CHARGING) 1 else 0,
                     counterDropUah: Long = 100): CalibrationDecision? {
        elapsed += ms
        uptime += ms
        charge -= counterDropUah
        return calibrator.accept(reading(raw, power), plugged)
    }

    @Test fun microampDeviceWithAndroidSignIsConfirmedByThreeWindows() {
        start()
        assertNull(feed())
        assertEquals(1.0, calibrator.windows.single().k, 0.001)
        assertEquals(microamps, calibrator.windows.single().calibration)
        assertNull(feed())
        val decision = feed()
        assertEquals(CalibrationDecision(microamps, CalibrationBasis.COUNTER_WINDOWS, 3), decision)
        assertEquals(decision, calibrator.decision)
        assertFalse(calibrator.counterSuspect)
    }

    @Test fun milliampDeviceIsDetected() {
        start(report = asMilliamps)
        feed(report = asMilliamps)
        feed(report = asMilliamps)
        val decision = feed(report = asMilliamps)
        assertEquals(1000.0, calibrator.windows.last().k, 0.5)
        assertEquals(milliamps, decision?.calibration)
    }

    @Test fun invertedSignIsDetectedForBothUnits() {
        start(report = { -it })
        feed(report = { -it })
        feed(report = { -it })
        val micro = feed(report = { -it })
        assertEquals(-1.0, calibrator.windows.last().k, 0.001)
        assertEquals(CalibrationDecision(microamps.copy(sign = CurrentSign.INVERTED), CalibrationBasis.COUNTER_WINDOWS, 3), micro)

        val milli = CurrentCalibrator()
        fun capture(raw: Long) = milli.accept(reading(raw, DISCHARGING), 0)
        capture(300)
        var decision: CalibrationDecision? = null
        repeat(60) {
            elapsed += 30_000; uptime += 30_000; charge -= 2_500
            decision = capture(300)
        }
        assertEquals(CalibrationDecision(milliamps.copy(sign = CurrentSign.INVERTED), CalibrationBasis.COUNTER_WINDOWS, 3), decision)
    }

    @Test fun chargingWindowsAreEvidenceToo() {
        start(trueUa = 1_200_000)
        feed(trueUa = 1_200_000)
        feed(trueUa = 1_200_000)
        val decision = feed(trueUa = 1_200_000)
        assertEquals(PowerState.CHARGING, calibrator.windows.last().power)
        assertEquals(microamps, decision?.calibration)
    }

    @Test fun threeOfTheLastFourMustAgreeAndNoneMayContradict() {
        start()
        feed()
        feed()
        gap(report = asMilliamps)
        assertNull(feed(report = asMilliamps)) // µA, µA, mA
        assertEquals(milliamps, calibrator.windows.last().calibration)
        gap()
        assertNull(feed()) // µA, µA, mA, µA: three agree but one contradicts
        assertNull(feed()) // µA, mA, µA, µA
        assertNull(feed()) // mA, µA, µA, µA
        assertEquals(4, calibrator.windows.size)
        assertEquals(CalibrationDecision(microamps, CalibrationBasis.COUNTER_WINDOWS, 4), feed())
    }

    @Test fun inconclusiveWindowNeitherAgreesNorContradicts() {
        start(report = { it / 10 })
        feed(report = { it / 10 })
        assertEquals(10.0, calibrator.windows.single().k, 0.01)
        assertNull(calibrator.windows.single().calibration)
        gap()
        feed()
        assertNull(feed())
        assertEquals(CalibrationDecision(microamps, CalibrationBasis.COUNTER_WINDOWS, 3), feed())
    }

    @Test fun windowClosesOnlyAtTwentyMahAndTenMinutes() {
        start(trueUa = -60_000) // 500 µAh per 30 s: 20 mAh takes 20 minutes
        feed(39, trueUa = -60_000)
        assertTrue(calibrator.windows.isEmpty())
        feed(1, trueUa = -60_000)
        assertEquals(20_000L, calibrator.windows.single().chargeUah)
        assertEquals(1_200_000L, calibrator.windows.single().coveredMs)

        val fast = CurrentCalibrator() // 3 A: 20 mAh within the first interval, but only 10 min closes it
        fast.accept(reading(-3_000_000, DISCHARGING), 0)
        repeat(19) {
            elapsed += 30_000; uptime += 30_000; charge -= 25_000
            fast.accept(reading(-3_000_000, DISCHARGING), 0)
        }
        assertTrue(fast.windows.isEmpty())
        elapsed += 30_000; uptime += 30_000; charge -= 25_000
        fast.accept(reading(-3_000_000, DISCHARGING), 0)
        assertEquals(600_000L, fast.windows.single().coveredMs)
        assertEquals(500_000L, fast.windows.single().chargeUah)
    }

    @Test fun intervalsSpentAsleepAreNotEvidence() {
        start()
        feed(120, awakeShare = 0.5)
        assertTrue(calibrator.windows.isEmpty())
        assertNull(calibrator.decision)
    }

    @Test fun implausibleCounterIsFlaggedAndNeverUsedOrScaled() {
        charge = 60_000_000 // 75 Ah implied at 80 %
        start()
        feed(60)
        assertTrue(calibrator.counterSuspect)
        assertTrue(calibrator.windows.isEmpty())
        assertNull(calibrator.decision)
    }

    @Test fun twentyPositiveUnpluggedReadingsMarkTheSignInverted() {
        repeat(19) { assertNull(step(2_000, 150_000)) }
        val decision = step(2_000, 150_000)
        assertEquals(CalibrationDecision(microamps.copy(sign = CurrentSign.INVERTED), CalibrationBasis.POSITIVE_WHILE_DISCHARGING, 0), decision)
    }

    @Test fun chargeHoldWhilePluggedIsNotUnplugged() {
        // Status "discharging" (3) while plugged in, e.g. a charge limit holding at ~0 mA.
        repeat(25) { assertNull(step(2_000, 20_000, DISCHARGING, plugged = 1)) }
        repeat(25) { assertNull(step(2_000, 20_000, DISCHARGING, plugged = null)) }
        // The same readings unplugged, with the counter falling, do mark the sign.
        repeat(19) { assertNull(step(2_000, 20_000)) }
        assertEquals(CurrentSign.INVERTED, step(2_000, 20_000)?.calibration?.sign)
    }

    @Test fun fastPathNeedsTheCounterToFallWhenItIsReported() {
        repeat(25) { assertNull(step(2_000, 150_000, counterDropUah = 0)) }
        repeat(25) { assertNull(step(2_000, 150_000, counterDropUah = -100)) }
        val noCounter = CurrentCalibrator()
        var decision: CalibrationDecision? = null
        repeat(20) {
            elapsed += 2_000; uptime += 2_000
            decision = noCounter.accept(reading(150_000, DISCHARGING).copy(chargeUah = null), 0)
        }
        assertEquals(CalibrationBasis.POSITIVE_WHILE_DISCHARGING, decision?.basis)
    }

    @Test fun aNonPositiveUnpluggedReadingRestartsTheFastPath() {
        repeat(19) { step(2_000, 150_000) }
        assertNull(step(2_000, 0))
        repeat(19) { assertNull(step(2_000, 150_000)) }
        assertNull(step(2_000, 500_000, CHARGING)) // charging neither counts nor resets
        assertNull(step(2_000, null)) // missing neither counts nor resets
        assertEquals(CalibrationBasis.POSITIVE_WHILE_DISCHARGING, step(2_000, 150_000)?.basis)
    }

    @Test fun aNormalWindowSuppressesTheFastPath() {
        start()
        feed()
        repeat(25) { step(2_000, 150_000) }
        assertNull(calibrator.decision)
    }

    @Test fun resetForgetsAllEvidence() {
        start()
        repeat(3) { feed() }
        calibrator.reset()
        assertNull(calibrator.decision)
        assertTrue(calibrator.windows.isEmpty())
    }
}
