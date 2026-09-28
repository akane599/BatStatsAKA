package app.batstats.battery.measurement

import app.batstats.battery.measurement.PersistPolicy.State
import org.junit.Assert.*
import org.junit.Test

class PersistPolicyTest {
    private val discharging = 3
    private val charging = 2
    private val last = State(elapsedMs = 100_000, status = discharging, plugged = 0, levelPercent = 80, generation = "one")
    private fun at(offsetMs: Long, status: Int = discharging, plugged: Int? = 0, level: Int? = 80, generation: String? = "one") =
        State(100_000 + offsetMs, status, plugged, level, generation)
    private fun decide(current: State, boundary: Boundary = Boundary.SAMPLE, screenOn: Boolean = true, poll: Boolean = true,
                       previous: State? = last) = PersistPolicy.decide(previous, current, boundary, screenOn, poll)

    @Test fun firstCaptureAndANewMonitoringRunArePersisted() {
        assertEquals(PersistReason.FIRST, decide(at(1_000), previous = null))
        assertEquals(PersistReason.FIRST, decide(at(1_000, generation = "two"), poll = false))
    }

    @Test fun screenDozeAndGapBoundariesArePersistedImmediately() {
        assertEquals(PersistReason.SCREEN, decide(at(500), Boundary.SCREEN, screenOn = false, poll = false))
        assertEquals(PersistReason.SCREEN, decide(at(500), Boundary.SCREEN, screenOn = true, poll = false))
        assertEquals(PersistReason.DOZE, decide(at(500), Boundary.DOZE, screenOn = false, poll = false))
        assertEquals(PersistReason.GAP, decide(at(500), Boundary.GAP, poll = true))
    }

    @Test fun statusOrPluggedChangeIsTheOnlyPowerReason() {
        assertEquals(PersistReason.POWER, decide(at(500), Boundary.POWER, poll = false))
        assertEquals(PersistReason.POWER, decide(at(500, status = charging, plugged = 1), poll = false))
        // Same status, another charger (AC → USB) still counts.
        val onAc = at(0, status = charging, plugged = 1)
        assertEquals(PersistReason.POWER, decide(at(500, status = charging, plugged = 2), poll = false, previous = onAc))
        assertEquals(PersistReason.POWER, decide(at(500, plugged = null), poll = true))
    }

    @Test fun levelChangeIsPersisted() {
        assertEquals(PersistReason.LEVEL, decide(at(500, level = 79), poll = false))
        assertEquals(PersistReason.LEVEL, decide(at(500, level = null), screenOn = false, poll = false))
    }

    @Test fun screenOnPollsArePersistedAtMostEveryThirtySeconds() {
        assertNull(decide(at(2_000)))
        assertNull(decide(at(29_999)))
        assertEquals(PersistReason.SCREEN_ON_INTERVAL, decide(at(30_000)))
        assertEquals(PersistReason.SCREEN_ON_INTERVAL, decide(at(95_000)))
    }

    @Test fun everyScreenOffPollIsPersisted() {
        assertEquals(PersistReason.SCREEN_OFF_POLL, decide(at(1_000), screenOn = false))
        assertEquals(PersistReason.SCREEN_OFF_POLL, decide(at(300_000), screenOn = false))
    }

    @Test fun voltageOrTemperatureOnlyBroadcastsStayRealtime() {
        assertNull(decide(at(1_000), poll = false))
        assertNull(decide(at(120_000), poll = false))
        assertNull(decide(at(1_000), screenOn = false, poll = false))
        assertNull(decide(at(600_000), screenOn = false, poll = false))
    }
}
