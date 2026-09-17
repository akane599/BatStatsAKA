package app.batstats.battery.measurement

import org.junit.Assert.*
import org.junit.Test

class ObservationEngineTest {
    private fun point(t: Long, charge: Long? = 4_000_000 - t / 60,
                      screen: Boolean = true, power: PowerState = PowerState.DISCHARGING,
                      boundary: Boundary = Boundary.SAMPLE, uptime: Long = t,
                      generation: String = "one", wall: Long = 1_000_000 + t, doze: Boolean = false) =
        Observation(wall, t, uptime, 80, charge, -60_000, 4000, power, screen, doze, generation, boundary = boundary)

    @Test fun screenNeverOffMeansNoScreenOffTimeOrConsumption() {
        val engine = ObservationEngine()
        engine.accept(point(0))
        val result = engine.accept(point(60_000))
        assertEquals(60_000L, result.screenOn.durationMs)
        assertEquals(1.0, result.screenOn.chargeMah!!, 0.001)
        assertEquals(60.0, result.screenOn.rateMa!!, 0.001)
        assertEquals(0L, result.screenOff.durationMs)
        assertNull(result.screenOff.chargeMah)
        assertNull(result.screenOff.rateMa)
    }
    @Test fun screenTransitionClosesPreviousStateThenStartsNext() {
        val engine = ObservationEngine()
        engine.accept(point(0))
        engine.accept(point(60_000, screen = false, boundary = Boundary.SCREEN))
        val result = engine.accept(point(120_000, screen = true, boundary = Boundary.SCREEN))
        assertEquals(60_000L, result.screenOn.durationMs)
        assertEquals(60_000L, result.screenOff.durationMs)
        assertEquals(1.0, result.screenOn.chargeMah!!, 0.001)
        assertEquals(1.0, result.screenOff.chargeMah!!, 0.001)
        assertEquals(2.0, result.discharge.chargeMah!!, 0.001)
        assertEquals(8.0, result.discharge.estimatedEnergyMwh!!, 0.001)
    }
    @Test fun resetMidPeriodDoesNotReuseEarlierCharge() {
        val engine = ObservationEngine()
        engine.accept(point(0))
        engine.accept(point(60_000))
        engine.reset()
        val result = engine.accept(point(120_000))
        assertEquals(0L, result.observedMs)
        assertNull(result.discharge.chargeMah)
        assertEquals(1_120_000L, result.startedAt)
    }
    @Test fun chargingAndUnpluggingDoNotEnterDischargeAverage() {
        val engine = ObservationEngine()
        engine.accept(point(0, power = PowerState.CHARGING))
        engine.accept(point(60_000, boundary = Boundary.POWER))
        val result = engine.accept(point(120_000))
        assertEquals(60_000L, result.chargingMs)
        assertEquals(60_000L, result.discharge.durationMs)
        assertEquals(60.0, result.discharge.rateMa!!, 0.001)
    }
    @Test fun cpuSuspendIsElapsedMinusUptimeWithinObservedIntervalNotSinceBoot() {
        val engine = ObservationEngine()
        engine.accept(point(600_000, uptime = 100_000, screen = false))
        val result = engine.accept(point(660_000, uptime = 140_000, screen = false))
        assertEquals(20_000L, result.cpuSuspendMs)
        assertEquals(60_000L, result.cpuObservedMs)
        assertEquals(0L, result.dozeMs)
    }
    @Test fun dozeDoesNotProveCpuSuspend() {
        val engine = ObservationEngine()
        engine.accept(point(0, doze = true, screen = false))
        val result = engine.accept(point(60_000, doze = true, screen = false))
        assertEquals(60_000L, result.dozeMs)
        assertEquals(0L, result.cpuSuspendMs)
    }
    @Test fun missingCounterLeavesDurationButNoMadeUpCharge() {
        val engine = ObservationEngine()
        engine.accept(point(0, charge = null))
        val result = engine.accept(point(60_000))
        assertEquals(60_000L, result.observedMs)
        assertEquals(1, result.counterGaps)
        assertNull(result.discharge.chargeMah)
    }
    @Test fun validZeroIsDistinctFromMissing() {
        val engine = ObservationEngine()
        engine.accept(point(0, charge = 1_000_000))
        val result = engine.accept(point(60_000, charge = 1_000_000))
        assertEquals(0.0, result.discharge.chargeMah!!, 0.0)
        assertEquals(0.0, result.discharge.rateMa!!, 0.0)
    }
    @Test fun counterResetDoesNotBecomeNegativeOrZeroDrain() {
        val engine = ObservationEngine()
        engine.accept(point(0, charge = 1_000_000))
        val result = engine.accept(point(60_000, charge = 4_000_000))
        assertNull(result.discharge.chargeMah)
        assertEquals(1, result.counterGaps)
    }
    @Test fun longAwakeGapAndUnobservedTransitionAreExcluded() {
        val engine = ObservationEngine()
        engine.accept(point(0))
        engine.accept(point(600_000))
        val result = engine.accept(point(660_000, screen = false))
        assertEquals(0L, result.observedMs)
        assertEquals(2, result.gaps)
        assertNull(result.screenOff.chargeMah)
    }
    @Test fun restartRebootAndWallClockChangesDoNotBridgeWindows() {
        for (next in listOf(point(60_000, generation = "two"), point(-1), point(60_000, wall = 10_000_000))) {
            val engine = ObservationEngine()
            engine.accept(point(0))
            val result = engine.accept(next)
            assertEquals(0L, result.observedMs)
            assertEquals(1, result.gaps)
        }
    }
    @Test fun shortAndDuplicateTransitionsDoNotInventSessionsOrRates() {
        val engine = ObservationEngine()
        engine.accept(point(0))
        engine.accept(point(0, screen = false, boundary = Boundary.SCREEN))
        engine.accept(point(10, screen = true, boundary = Boundary.SCREEN))
        val result = engine.accept(point(20))
        assertEquals(10L, result.screenOn.durationMs)
        assertEquals(10L, result.screenOff.durationMs)
        assertNull(result.discharge.rateMa)
        engine.stop()
        assertTrue(engine.summary.stopped)
        engine.accept(point(60_000))
        assertEquals(20L, engine.summary.observedMs)
    }
}
