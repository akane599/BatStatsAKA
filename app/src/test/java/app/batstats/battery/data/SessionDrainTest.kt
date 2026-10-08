package app.batstats.battery.data

import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.data.sampling.SessionExtremes
import app.batstats.battery.data.sampling.SessionReport
import app.batstats.battery.measurement.Boundary
import app.batstats.battery.measurement.Observation
import app.batstats.battery.measurement.ObservationEngine
import app.batstats.battery.measurement.PowerState
import app.batstats.viewmodel.NowMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionDrainTest {
    private val hour = 3_600_000L

    private fun session(
        screenOnMs: Long = hour,
        screenOffMs: Long = 2 * hour,
        screenOnUah: Long? = 400_000,
        screenOffUah: Long? = 200_000,
        counterCoveredMs: Long = 3 * hour,
        cpuSuspendMs: Long? = 90 * 60_000L,
        observedMs: Long = 3 * hour,
    ) = ChargeSession(
        sessionId = "s", type = SessionType.DISCHARGE, startTime = 0, endTime = null, startLevel = 90, endLevel = 70,
        deltaUah = null, avgCurrentUa = null, estCapacityMah = null, observedMs = observedMs, counterCoveredMs = counterCoveredMs,
        screenOnMs = screenOnMs, screenOffMs = screenOffMs, screenOnUah = screenOnUah, screenOffUah = screenOffUah,
        cpuSuspendMs = cpuSuspendMs,
    )

    @Test fun ratesPercentPerHourAndDeepSleepFromTheRow() {
        val drain = SessionDrain.of(session(), fullUah = 4_000_000)
        assertEquals(DrainRate(hour, 400.0, 10.0), drain.screenOn)
        assertEquals(DrainRate(2 * hour, 100.0, 2.5), drain.screenOff)
        assertEquals(50.0, drain.deepSleepPercent!!, 1e-9)
    }

    @Test fun asymmetricCounterCoverageKeepsMeasuredScreenRateAndOverallRate() {
        val first = Observation(
            wallMs = 0, elapsedMs = 0, uptimeMs = 0, level = 90, chargeUah = 4_000_000,
            currentUa = -600_000, voltageMv = 4_000, power = PowerState.DISCHARGING,
            interactive = true, dozing = false, generation = "run",
        )
        val engine = ObservationEngine()
        engine.accept(first)
        // 120 s on fully covered, followed by 120 s off without a counter.
        engine.accept(first.copy(
            wallMs = 120_000, elapsedMs = 120_000, uptimeMs = 120_000, chargeUah = 3_980_000,
            interactive = false, boundary = Boundary.SCREEN,
        ))
        val summary = engine.accept(first.copy(
            wallMs = 240_000, elapsedMs = 240_000, uptimeMs = 240_000, chargeUah = null, interactive = false,
        ))
        val last = BatterySample(
            timestamp = 240_000, levelPercent = 90, status = 3, plugged = 0, currentNowUa = -600_000,
            chargeCounterUah = null, voltageMv = 4_000, temperatureDeciC = 300, health = 2, screenOn = false,
        )
        val report = SessionReport.report(session(), last, summary, SessionExtremes())
        assertEquals(120_000L, report.counterCoveredMs)
        assertEquals(20_000L, report.deltaUah)
        assertEquals(-600_000L, report.avgCurrentUa)
        val drain = SessionDrain.of(report, fullUah = 4_000_000)
        assertEquals(DrainRate(120_000, 600.0, 15.0), drain.screenOn)
        assertEquals(DrainRate(120_000, null, null), drain.screenOff)
        val now = NowMapping.sinceUnplug(report, monitoring = true, fullUah = 4_000_000)!!
        assertEquals(600.0, now.screenOn.currentMa!!, 0.0)
        assertEquals(15.0, now.screenOn.percentPerHour!!, 0.0)
        assertNull(now.screenOff.currentMa)
        assertNull(now.screenOff.percentPerHour)
    }

    @Test fun legacyNullCoverageStillRequiresBothBucketsFullyCovered() {
        val drain = SessionDrain.of(session(counterCoveredMs = 3 * hour - 1), fullUah = 4_000_000)
        assertEquals(DrainRate(hour, null, null), drain.screenOn)
        assertEquals(DrainRate(2 * hour, null, null), drain.screenOff)
    }

    @Test fun completeCoverageUsesExactDurationsWithoutCapacityOrWithExcessCoverage() {
        val drain = SessionDrain.of(session(counterCoveredMs = 3 * hour + 1), fullUah = null)
        assertEquals(DrainRate(hour, 400.0, null), drain.screenOn)
        assertEquals(DrainRate(2 * hour, 100.0, null), drain.screenOff)
    }

    @Test fun zeroDurationBucketStaysUnavailableWithCompleteCoverage() {
        val drain = SessionDrain.of(session(screenOnMs = 0, counterCoveredMs = 2 * hour), fullUah = 4_000_000)
        assertEquals(DrainRate(0, null, null), drain.screenOn)
        assertEquals(DrainRate(2 * hour, 100.0, 2.5), drain.screenOff)
    }

    @Test fun missingCounterDataOrUnderAMinuteGivesNoRate() {
        with(SessionDrain.of(session(screenOnUah = null, screenOnMs = 30_000, counterCoveredMs = 2 * hour + 30_000), 4_000_000)) {
            assertEquals(DrainRate(30_000, null, null), screenOn)
        }
        with(SessionDrain.of(session(screenOnMs = 59_999, counterCoveredMs = 2 * hour + 59_999), 4_000_000)) {
            assertEquals(DrainRate(59_999, null, null), screenOn)
        }
        with(SessionDrain.of(session(screenOnMs = 60_000, counterCoveredMs = 2 * hour + 60_000), 4_000_000)) {
            assertEquals(24_000.0, screenOn.currentMa!!, 1e-9)
        }
        with(SessionDrain.of(session(screenOnMs = 0, screenOffMs = 0, counterCoveredMs = 0, observedMs = 0, cpuSuspendMs = null), 4_000_000)) {
            assertNull(screenOn.currentMa)
            assertNull(screenOff.currentMa)
            assertNull(deepSleepPercent)
        }
    }
}
