package app.batstats.battery.data

import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
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

    @Test fun partialCounterCoverageScalesTheRateAndNoCapacityMeansNoPercent() {
        // The counter covered half the discharge time: the charge was measured over half of each duration.
        val drain = SessionDrain.of(session(counterCoveredMs = 90 * 60_000L), fullUah = null)
        assertEquals(800.0, drain.screenOn.currentMa!!, 1e-9)
        assertNull(drain.screenOn.percentPerHour)
    }

    @Test fun missingCounterDataOrUnderAMinuteGivesNoRate() {
        with(SessionDrain.of(session(screenOnUah = null, screenOnMs = 30_000, counterCoveredMs = 2 * hour + 30_000), 4_000_000)) {
            assertEquals(DrainRate(30_000, null, null), screenOn)
        }
        with(SessionDrain.of(session(screenOnMs = 0, screenOffMs = 0, counterCoveredMs = 0, observedMs = 0, cpuSuspendMs = null), 4_000_000)) {
            assertNull(screenOn.currentMa)
            assertNull(screenOff.currentMa)
            assertNull(deepSleepPercent)
        }
    }
}
