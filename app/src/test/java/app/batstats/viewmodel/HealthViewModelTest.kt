package app.batstats.viewmodel

import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.measurement.CapacityConfidence
import app.batstats.battery.measurement.HealthSummary
import app.batstats.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HealthViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeHealthRepository()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.start(): () -> HealthUiState {
        val vm = HealthViewModel(repo, computeDispatcher = dispatcher)
        backgroundScope.launch { vm.state.collect { } }
        runCurrent()
        return { vm.state.value }
    }

    @Test fun capacityAndHealthAreNowsFiguresForTheSameSessions() = runTest {
        // 60 sessions, newest first: the combined figure reads only the newest 50, the trend all 60.
        val sessions = List(60) { i ->
            val confidence = when (i % 3) { 0 -> "HIGH"; 1 -> "MEDIUM"; else -> "LOW" }
            session("s$i", endTime = T0 - i * DAY, capacityMah = if (i < 50) 4_000 + (i % 7) * 40 else 3_000, confidence = confidence)
        }
        repo.sessions.value = sessions
        repo.settings.value = AppSettings(designCapacityMah = 5_000)
        val state = start()

        val now = NowMapping.healthSummary(sessions.take(HealthSummary.SESSIONS), 5_000)?.let(NowMapping::health)
        with(state()) {
            assertTrue(loaded)
            assertEquals(listOf(HealthViewModel.TREND_SESSIONS), repo.limits)
            assertEquals(now?.capacityMah, summary?.capacityMah)
            assertEquals(now?.confidence, summary?.confidence)
            assertEquals(now?.healthPercent, summary?.healthPercent)
            // The ten older 3,000 mAh sessions are in the trend but can't pull the figure down.
            assertEquals(60, estimates.size)
            assertEquals(3_000, estimates.first().capacityMah)
            assertTrue(summary!!.capacityMah >= 4_000)
            assertEquals(DesignCapacityState.Known(5_000, DesignSource.SETTINGS), design)
        }
        // With a design override, sysfs is never read (no root prompt).
        assertEquals(0, repo.sysfsReads)
    }

    @Test fun autoDesignReadsSysfsOnceShowingCheckingUntilItAnswers() = runTest {
        repo.sessions.value = listOf(session("a", endTime = T0, capacityMah = 4_500, confidence = "HIGH"))
        val pending = CompletableDeferred<Long?>()
        repo.sysfs = pending
        val state = start()

        with(state()) {
            assertEquals(DesignCapacityState.Checking, design)
            assertEquals(4_500, summary?.capacityMah)
            assertNull(summary?.healthPercent)
        }

        pending.complete(5_000_000L)
        runCurrent()
        with(state()) {
            assertEquals(DesignCapacityState.Known(5_000, DesignSource.BATTERY), design)
            assertEquals(90.0, summary!!.healthPercent!!, 1e-9)
        }
        assertEquals(1, repo.sysfsReads)

        // A new session doesn't read sysfs again; switching the override on and back to auto does.
        repo.sessions.value = repo.sessions.value + session("b", endTime = T0 - DAY, capacityMah = 4_400, confidence = "LOW")
        runCurrent()
        assertEquals(1, repo.sysfsReads)
        repo.sysfs = answered(null)
        repo.settings.value = AppSettings(designCapacityMah = 4_800)
        runCurrent()
        assertEquals(DesignCapacityState.Known(4_800, DesignSource.SETTINGS), state().design)
        repo.settings.value = AppSettings(designCapacityMah = 0)
        runCurrent()
        assertEquals(2, repo.sysfsReads)
        // No root (or nothing plausible reported): unknown, and no health %.
        with(state()) {
            assertEquals(DesignCapacityState.Unknown, design)
            assertNull(summary?.healthPercent)
        }
    }

    @Test fun implausibleSysfsOrInvalidOverrideLeavesTheDesignUnknown() = runTest {
        repo.sessions.value = listOf(session("a", endTime = T0, capacityMah = 4_500, confidence = "MEDIUM"))
        // 5,000 "mAh" in a µAh field is a unit error (CapacityEstimator.PLAUSIBLE_FULL_UAH), and 500 mAh is outside the
        // setting's range (read as auto).
        repo.sysfs = answered(5_000L)
        repo.settings.value = AppSettings(designCapacityMah = 500)
        val state = start()

        assertEquals(DesignCapacityState.Unknown, state().design)
        assertNull(state().summary?.healthPercent)
        assertEquals(1, repo.sysfsReads)
    }

    @Test fun noEstimatesYetStillShowsDesignAndCycles() = runTest {
        repo.sessions.value = listOf(
            session("plugged", endTime = T0, type = SessionType.PLUGGED),
            session("short", endTime = T0 - DAY),
            session("junk", endTime = T0 - 2 * DAY, capacityMah = 4_000, confidence = "SOMEDAY"),
        )
        repo.settings.value = AppSettings(designCapacityMah = 5_000)
        repo.cycles = 312
        val state = start()

        with(state()) {
            assertTrue(loaded)
            assertNull(summary)
            assertTrue(estimates.isEmpty())
            assertEquals(DesignCapacityState.Known(5_000, DesignSource.SETTINGS), design)
            assertEquals(CycleCountState.Count(312), cycles)
        }
    }

    @Test fun cyclesAreHiddenBelowApi34() = runTest {
        repo.cyclesSupported = false
        repo.cycles = 99
        assertEquals(CycleCountState.Unsupported, start()().cycles)
    }

    @Test fun cyclesNotReportedOnApi34WithoutTheExtra() = runTest {
        repo.cycles = null
        assertEquals(CycleCountState.NotReported, start()().cycles)
    }

    @Test fun trendPointsAreOrderedByWhenEachSessionEnded() = runTest {
        repo.sessions.value = listOf(
            // Newest first by start time, as the query returns them; the open one is placed at its latest save.
            session("open", endTime = null, lastSample = T0, startTime = T0 - HOUR, capacityMah = 4_300, confidence = "LOW"),
            session("long", endTime = T0 - 2 * HOUR, startTime = T0 - 30 * HOUR, capacityMah = 4_250, confidence = "HIGH", type = SessionType.CHARGE),
            session("mid", endTime = T0 - 3 * HOUR, startTime = T0 - 10 * HOUR, capacityMah = 4_200, confidence = "MEDIUM"),
        )
        val state = start()

        with(state().estimates) {
            // "long" started first but ended after "mid": points sit where each estimate was measured.
            assertEquals(listOf("mid", "long", "open"), map { it.sessionId })
            assertEquals(listOf(T0 - 3 * HOUR, T0 - 2 * HOUR, T0), map { it.timeMs })
            assertEquals(CapacityPoint("long", T0 - 2 * HOUR, SessionType.CHARGE, 20, 80, 4_250, CapacityConfidence.HIGH), this[1])
            assertEquals(CapacityConfidence.LOW, last().confidence)
        }
    }

    private class FakeHealthRepository : HealthRepository {
        override val settings = MutableStateFlow(AppSettings())
        val sessions = MutableStateFlow<List<ChargeSession>>(emptyList())
        val limits = mutableListOf<Int>()
        /** No root by default: the read answers null at once. */
        var sysfs: CompletableDeferred<Long?> = answered(null)
        var sysfsReads = 0
        override var cyclesSupported = true
        var cycles: Int? = null

        override fun recentSessions(limit: Int): Flow<List<ChargeSession>> {
            limits += limit
            return sessions.map { it.take(limit) }
        }

        override suspend fun chargeFullDesignUah(): Long? {
            sysfsReads++
            return sysfs.await()
        }

        override suspend fun cycleCount(): Int? = cycles
    }

    private companion object {
        const val T0 = 1_760_001_600_000L
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR

        // Not CompletableDeferred(null): that overload takes a parent Job and never completes.
        fun answered(uah: Long?) = CompletableDeferred<Long?>().also { it.complete(uah) }

        fun session(
            id: String,
            endTime: Long?,
            type: SessionType = SessionType.DISCHARGE,
            startTime: Long = (endTime ?: T0) - 5 * HOUR,
            lastSample: Long? = endTime,
            capacityMah: Int? = null,
            confidence: String? = null,
        ) = ChargeSession(
            sessionId = id,
            type = type,
            startTime = startTime,
            endTime = endTime,
            startLevel = if (type == SessionType.CHARGE) 20 else 90,
            endLevel = if (type == SessionType.CHARGE) 80 else 40,
            deltaUah = null,
            avgCurrentUa = null,
            estCapacityMah = null,
            lastSampleTime = lastSample,
            capacityEstimateMah = capacityMah,
            capacityConfidence = confidence,
            capacityBasis = confidence?.let { "COUNTER_SPAN" },
        )
    }
}
