package app.batstats.battery.data.sampling

import app.batstats.battery.apps.AppUsageStatus
import app.batstats.battery.data.SessionDrain
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.measurement.Boundary
import app.batstats.battery.measurement.CapacityBasis
import app.batstats.battery.measurement.CapacityConfidence
import app.batstats.battery.measurement.Observation
import app.batstats.battery.measurement.ObservationEngine
import app.batstats.battery.measurement.PowerState
import org.junit.Assert.*
import org.junit.Test

class SessionReportTest {
    private val engine = ObservationEngine()

    private fun sample(level: Int, charge: Long, plugged: Int, status: Int, elapsed: Long, screenOn: Boolean = true) = BatterySample(
        timestamp = 1_000_000 + elapsed, levelPercent = level, status = status, plugged = plugged,
        currentNowUa = -500_000, chargeCounterUah = charge, voltageMv = 4000, temperatureDeciC = 300, health = 2,
        screenOn = screenOn, elapsedMs = elapsed, uptimeMs = elapsed, observationId = "run", source = "BatteryManager",
    )

    private fun BatterySample.point(power: PowerState, boundary: Boundary = Boundary.SAMPLE, uptime: Long = uptimeMs!!) =
        Observation(timestamp, elapsedMs!!, uptime, levelPercent, chargeCounterUah, currentNowUa, voltageMv, power, screenOn, false,
            "run", 30_000, boundary)

    @Test fun chargerTypeFollowsExtraPlugged() {
        assertEquals(ChargerType.AC, ChargerType.of(1))
        assertEquals(ChargerType.USB, ChargerType.of(2))
        assertEquals(ChargerType.WIRELESS, ChargerType.of(4))
        assertEquals(ChargerType.DOCK, ChargerType.of(8))
        assertNull(ChargerType.of(0))
        assertNull(ChargerType.of(null))
    }

    @Test fun chargeSessionReportsDeltaRateEnergyCapacityAndCharger() {
        val first = sample(level = 40, charge = 2_000_000, plugged = 2, status = 2, elapsed = 0)
        val session = SessionReport.open(first.point(PowerState.CHARGING), first)
        assertEquals(SessionType.CHARGE, session.type)
        assertEquals("USB", session.chargerType)
        assertEquals(40, session.startLevel)
        assertEquals(1, session.activeKey)
        engine.accept(first.point(PowerState.CHARGING))
        // 2 h at 1 A: +2,000 mAh over a 40 % span → a 5,000 mAh battery, HIGH confidence.
        val last = sample(level = 80, charge = 4_000_000, plugged = 2, status = 2, elapsed = 7_200_000)
        val summary = engine.accept(last.point(PowerState.CHARGING, uptime = 60_000)) // mostly suspended: no observation gap
        val report = SessionReport.report(session, last, summary, SessionExtremes(peakPowerMw = 4_200, peakTemperatureDeciC = 330))
        assertEquals(2_000_000L, report.deltaUah)
        assertEquals(1_000_000L, report.avgCurrentUa)
        assertEquals(8_000_000_000L, report.energyNwh) // 2,000 mAh × 4.0 V
        assertEquals(80, report.endLevel)
        assertEquals(last.timestamp, report.lastSampleTime)
        assertEquals(4_200L, report.peakPowerMw)
        assertEquals(330, report.peakTemperatureDeciC)
        assertEquals(5_000, report.capacityEstimateMah)
        assertEquals(CapacityConfidence.HIGH.name, report.capacityConfidence)
        assertEquals(CapacityBasis.COUNTER_SPAN.name, report.capacityBasis)
        assertEquals("USB", report.chargerType)
    }

    @Test fun dischargeSessionKeepsInMemoryFieldsAndCountsScreenOffSuspend() {
        val first = sample(level = 90, charge = 4_000_000, plugged = 0, status = 3, elapsed = 0)
        val session = SessionReport.open(first.point(PowerState.DISCHARGING), first).copy(appUsageStatus = AppUsageStatus.PENDING)
        assertNull(session.chargerType)
        var extremes = SessionExtremes()
        var previous = engine.summary
        fun add(next: BatterySample, uptime: Long, boundary: Boundary = Boundary.SAMPLE) {
            val summary = engine.accept(next.point(PowerState.DISCHARGING, boundary, uptime))
            extremes = extremes.plus(-2_000.0, next.temperatureDeciC, summary.cpuSuspendMs - previous.cpuSuspendMs,
                screenOffBefore = previous.latest?.interactive == false)
            previous = summary
        }
        engine.accept(first.point(PowerState.DISCHARGING)); previous = engine.summary
        add(sample(89, 3_990_000, 0, 3, 60_000), uptime = 50_000) // screen on: 10 s suspend, not screen-off
        add(sample(89, 3_985_000, 0, 3, 120_000, screenOn = false), uptime = 100_000, Boundary.SCREEN)
        val last = sample(88, 3_960_000, 0, 3, 420_000, screenOn = false)
        add(last, uptime = 110_000) // screen off: 290 s suspended
        val report = SessionReport.report(session, last, previous, extremes)
        assertEquals(AppUsageStatus.PENDING, report.appUsageStatus)
        assertEquals(40_000L, report.deltaUah)
        assertEquals(290_000L, report.screenOffSuspendMs)
        assertEquals(310_000L, report.cpuSuspendMs)
        assertEquals(2_000L, report.peakPowerMw)
        assertTrue(report.avgCurrentUa!! < 0)
        assertNull("A 2 % span gives no capacity estimate", report.capacityEstimateMah)
    }

    @Test fun fullyCoveredDischargeKeepsCapacityWhenClosedByChargingBoundary() {
        val first = sample(level = 90, charge = 4_000_000, plugged = 0, status = 3, elapsed = 0)
        val session = SessionReport.open(first.point(PowerState.DISCHARGING), first)
        engine.accept(first.point(PowerState.DISCHARGING))
        // Constant 800 mA drain: 1,600 mAh over 40 points gives 4,000 mAh capacity.
        val middle = sample(level = 70, charge = 3_200_000, plugged = 0, status = 3, elapsed = 3_600_000)
        engine.accept(middle.point(PowerState.DISCHARGING, uptime = 30_000))
        val last = sample(level = 50, charge = 2_400_000, plugged = 0, status = 3, elapsed = 7_200_000)
        val summary = engine.accept(last.point(PowerState.DISCHARGING, uptime = 60_000))
        val current = SessionReport.report(session, last, summary, SessionExtremes())
        assertEquals(current.observedMs, current.counterCoveredMs)
        assertEquals(4_000, current.capacityEstimateMah)
        assertEquals(CapacityConfidence.HIGH.name, current.capacityConfidence)
        assertEquals(CapacityBasis.COUNTER_SPAN.name, current.capacityBasis)

        val boundary = sample(level = 49, charge = 2_390_000, plugged = 1, status = 2, elapsed = 7_230_000)
        val closed = SessionReport.reportPowerBoundary(
            current, boundary, boundary.point(PowerState.CHARGING, Boundary.POWER, uptime = 90_000),
            engine, SessionExtremes(),
        )
        assertEquals(0, engine.summary.gaps)
        assertEquals(7_230_000L, closed.observedMs)
        assertEquals(7_200_000L, closed.counterCoveredMs)
        assertEquals(current.deltaUah, closed.deltaUah)
        assertEquals(49, closed.endLevel)
        assertEquals(boundary.timestamp, closed.lastSampleTime)
        assertEquals("Power boundary must keep the last same-state capacity", current.capacityEstimateMah, closed.capacityEstimateMah)
        assertEquals(current.capacityConfidence, closed.capacityConfidence)
        assertEquals(current.capacityBasis, closed.capacityBasis)
    }

    @Test fun fullyCoveredDischargeKeepsDrainRatesWhenClosedByChargingBoundary() {
        val first = sample(level = 90, charge = 4_000_000, plugged = 0, status = 3, elapsed = 0)
        val session = SessionReport.open(first.point(PowerState.DISCHARGING), first)
        engine.accept(first.point(PowerState.DISCHARGING))
        val screenOff = sample(level = 70, charge = 3_200_000, plugged = 0, status = 3,
            elapsed = 3_600_000, screenOn = false)
        engine.accept(screenOff.point(PowerState.DISCHARGING, Boundary.SCREEN, uptime = 30_000))
        val last = sample(level = 50, charge = 2_400_000, plugged = 0, status = 3,
            elapsed = 7_200_000, screenOn = false)
        val summary = engine.accept(last.point(PowerState.DISCHARGING, uptime = 60_000))
        val extremes = SessionExtremes(screenOffSuspendMs = 3_570_000)
        val current = SessionReport.report(session, last, summary, extremes)
        assertEquals(current.observedMs, current.counterCoveredMs)

        val boundary = sample(level = 49, charge = 2_390_000, plugged = 1, status = 2,
            elapsed = 7_230_000, screenOn = false)
        val closed = SessionReport.reportPowerBoundary(
            current, boundary, boundary.point(PowerState.CHARGING, Boundary.POWER, uptime = 61_000),
            engine, extremes,
        )
        val drain = SessionDrain.of(closed, null)
        assertNotNull("Screen-on current must survive the charging boundary", drain.screenOn.currentMa)
        assertNotNull("Screen-off current must survive the charging boundary", drain.screenOff.currentMa)
        assertEquals(800.0, drain.screenOn.currentMa!!, 0.0)
        assertEquals(800.0, drain.screenOff.currentMa!!, 0.0)
        assertTrue("Screen durations must not exceed counter coverage",
            closed.screenOnMs + closed.screenOffMs <= closed.counterCoveredMs)
        assertEquals(current.screenOnMs, closed.screenOnMs)
        assertEquals(current.screenOffMs, closed.screenOffMs)
        assertTrue("Screen-off suspend must not exceed its screen duration",
            closed.screenOffSuspendMs!! <= closed.screenOffMs)
        assertEquals("Screen-off suspend must exclude the charging boundary",
            current.screenOffSuspendMs, closed.screenOffSuspendMs)
        assertEquals(current.cpuSuspendMs!! + 29_000, closed.cpuSuspendMs)
        assertEquals(current.screenOnUah, closed.screenOnUah)
        assertEquals(current.screenOffUah, closed.screenOffUah)
        assertEquals(7_230_000L, closed.observedMs)
        assertEquals(7_200_000L, closed.counterCoveredMs)
        val capacityDrain = SessionDrain.of(closed, 4_000_000)
        assertEquals(20.0, capacityDrain.screenOn.percentPerHour!!, 0.0)
        assertEquals(20.0, capacityDrain.screenOff.percentPerHour!!, 0.0)
    }

    @Test fun partialCounterCoverageKeepsMeasuredChargeButWithholdsCapacityFields() {
        for (intervalCount in listOf(4, 10)) {
            engine.reset()
            val first = sample(level = 90, charge = 4_000_000, plugged = 0, status = 3, elapsed = 0)
                .copy(chargeCounterUah = null)
            val session = SessionReport.open(first.point(PowerState.DISCHARGING), first)
            engine.accept(first.point(PowerState.DISCHARGING))
            var last = first
            for (index in 1..intervalCount) {
                last = sample(
                    level = 90 - 40 * index / intervalCount,
                    charge = 4_000_000L - 1_600_000L * index / intervalCount,
                    plugged = 0,
                    status = 3,
                    elapsed = index * 30_000L,
                )
                engine.accept(last.point(PowerState.DISCHARGING))
            }
            // One uncovered interval leaves 75 % or 90 % coverage of the full 40-point level drop.
            val report = SessionReport.report(session, last, engine.summary, SessionExtremes())
            assertEquals(intervalCount * 30_000L, report.observedMs)
            assertEquals((intervalCount - 1) * 30_000L, report.counterCoveredMs)
            assertEquals(1_600_000L * (intervalCount - 1) / intervalCount, report.deltaUah)
            assertNotNull(report.avgCurrentUa)
            assertNull(report.capacityEstimateMah)
            assertNull(report.capacityConfidence)
            assertNull(report.capacityBasis)
            val boundary = last.copy(timestamp = last.timestamp + 30_000, elapsedMs = last.elapsedMs!! + 30_000,
                uptimeMs = last.uptimeMs!! + 30_000, plugged = 1, status = 2)
            val closed = SessionReport.reportPowerBoundary(
                report, boundary, boundary.point(PowerState.CHARGING, Boundary.POWER), engine, SessionExtremes(),
            )
            assertNull(closed.capacityEstimateMah)
            assertNull(closed.capacityConfidence)
            assertNull(closed.capacityBasis)
        }
    }

    @Test fun extremesKeepMaximaAndIgnoreMissingValues() {
        val extremes = SessionExtremes()
            .plus(-1_500.0, 310, 0, screenOffBefore = false)
            .plus(null, null, 5_000, screenOffBefore = true)
            .plus(900.0, 290, 7_000, screenOffBefore = false)
        assertEquals(SessionExtremes(peakPowerMw = 1_500, peakTemperatureDeciC = 310, screenOffSuspendMs = 5_000), extremes)
    }
}
