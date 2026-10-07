package app.batstats.battery.measurement

import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
import org.junit.Assert.*
import org.junit.Test

class TypicalDischargeSeedTest {
    private val hour = 3_600_000L
    private val from = 10 * hour
    private val to = 20 * hour

    private fun session(
        coveredMs: Long = hour,
        screenOnUah: Long? = 40_000,
        screenOffUah: Long? = 60_000,
    ) = ChargeSession(
        sessionId = "local",
        type = SessionType.DISCHARGE,
        startTime = from - hour,
        endTime = from,
        startLevel = 80,
        endLevel = 75,
        deltaUah = 100_000,
        avgCurrentUa = null,
        estCapacityMah = null,
        observedMs = hour,
        counterCoveredMs = coveredMs,
        screenOnMs = hour / 2,
        screenOffMs = hour / 2,
        screenOnUah = screenOnUah,
        screenOffUah = screenOffUah,
        source = "BatteryManager observed interval",
    )

    private fun seed(vararg sessions: ChargeSession) = TypicalDischargeSeed.rateUa(sessions.toList(), from, to)

    @Test fun uncoveredObservedTimeDoesNotDiluteTheSeed() {
        val covered = session()
        val uncovered = session(coveredMs = 0, screenOnUah = null, screenOffUah = null)
        assertEquals(100_000.0, seed(covered, uncovered)!!, 1e-6)
    }

    @Test fun eligibilityUsesCoveredTimeRatherThanObservedTime() {
        assertNull(seed(session(coveredMs = hour - 1).copy(observedMs = 2 * hour)))
        assertEquals(100_000.0, seed(session())!!, 1e-6)
    }

    @Test fun coveredTimeAndBothChargeBucketsAreSummedAcrossSessions() {
        val on = session(hour / 2, screenOnUah = 25_000, screenOffUah = null)
        val off = session(hour / 2, screenOnUah = null, screenOffUah = 75_000)
        assertEquals(100_000.0, seed(on, off)!!, 1e-6)
    }

    @Test fun positiveChargeIsRequired() {
        assertNull(seed(session(screenOnUah = 0, screenOffUah = 0)))
        assertNull(seed(session(coveredMs = 0, screenOnUah = null, screenOffUah = null)))
        assertNull(seed())
    }

    @Test fun importedSessionsAreExcluded() {
        val imported = session().copy(source = "import:BatteryManager observed interval")
        assertNull(seed(imported))
        assertEquals(100_000.0, seed(session(), imported.copy(screenOnUah = 900_000))!!, 1e-6)
    }

    @Test fun onlyDischargingSessionsAreIncluded() {
        for (type in listOf(SessionType.CHARGE, SessionType.PLUGGED, SessionType.UNKNOWN)) {
            assertNull(seed(session().copy(type = type)))
        }
    }

    @Test fun openSessionsAreExcluded() {
        assertNull(seed(session().copy(endTime = null, activeKey = 1)))
    }

    @Test fun windowUsesTheClosedEndpointAndIncludesBothBounds() {
        assertNull(seed(session().copy(endTime = from - 1)))
        assertNull(seed(session().copy(endTime = to + 1)))
        assertEquals(100_000.0, seed(session(), session().copy(endTime = to))!!, 1e-6)
    }

    @Test fun counterCoveredSeedStartsFreshEtaAtTwentyHours() {
        val typical = seed(session(), session(coveredMs = 0, screenOnUah = null, screenOffUah = null))
        val eta = DischargeEta().apply { seed(typical) }
        val point = Observation(
            to, 0, 0, 50, 2_000_000, -1, 4000, PowerState.DISCHARGING, true, false, "new", 30_000, Boundary.SAMPLE,
        )
        val estimate = eta.accept(point)!!
        assertEquals(EtaBasis.TYPICAL_7D, estimate.basis)
        assertEquals(20 * hour, estimate.remainingMs)
        assertEquals(100_000L, estimate.rateUa)
    }
}
