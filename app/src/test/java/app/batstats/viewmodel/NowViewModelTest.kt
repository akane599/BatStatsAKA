package app.batstats.viewmodel

import android.graphics.Bitmap
import app.batstats.battery.apps.AppInfo
import app.batstats.battery.apps.AppInfoSource
import app.batstats.battery.apps.AppUsageBasis
import app.batstats.battery.apps.AppUsageRow
import app.batstats.battery.apps.AppUsageSnapshot
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.DailySummary
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.data.sampling.ChargerType
import app.batstats.battery.measurement.CalibrationState
import app.batstats.battery.measurement.CapacityConfidence
import app.batstats.battery.measurement.CurrentCalibration
import app.batstats.battery.measurement.CurrentUnit
import app.batstats.battery.measurement.DailySummaryAggregator
import app.batstats.battery.measurement.EtaBasis
import app.batstats.battery.measurement.Observation
import app.batstats.battery.measurement.ObservationSummary
import app.batstats.battery.measurement.ObservedBucket
import app.batstats.battery.measurement.PowerState
import app.batstats.battery.service.MonitoringControl
import app.batstats.settings.AppSettings
import app.batstats.ui.components.chart.TimeWindow
import app.batstats.ui.screens.now.Eta
import app.batstats.ui.screens.now.NowEvent
import app.batstats.ui.screens.now.NowUiState
import app.batstats.ui.screens.now.TodayState
import app.batstats.ui.screens.now.TopAppsState
import app.batstats.ui.screens.now.TraceRange
import java.time.ZoneOffset
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NowViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeNowRepository()
    private val monitoring = FakeMonitoring()
    private val appInfo = FakeAppInfo(mapOf(CHROME to "Chrome", YOUTUBE to "YouTube", MAPS to "Maps"))
    private var now = T0

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.start(): Pair<NowViewModel, () -> NowUiState> {
        val vm = NowViewModel(repo, monitoring, appInfo, clock = { now }, zone = { ZoneOffset.UTC }, computeDispatcher = dispatcher)
        backgroundScope.launch { vm.state.collect { } }
        runCurrent()
        return vm to { vm.state.value }
    }

    @Test fun heroAndReadoutsUseTheCalibratedReadingAndHoldTheEtaAcrossACaptureWithoutIt() = runTest {
        monitoring.isMonitoring.value = true
        val mA = CurrentCalibration(CurrentUnit.MILLIAMPS)
        repo.realtime.value = BatteryRepository.Realtime(sample(T0, raw = -412, eta = 5 * HOUR, basis = "LIVE_RATE"), mA)
        val (_, state) = start()

        with(state()) {
            assertTrue(hero.hasReading)
            assertEquals(67, hero.level)
            assertEquals(PowerState.DISCHARGING, hero.power)
            assertNull(hero.charger)
            assertEquals(Eta(5 * HOUR, EtaBasis.LIVE_RATE), hero.eta)
            // Raw -412 in mA units → calibrated -412 mA; power = I × V.
            assertEquals(-412.0, readouts.currentMa!!, 1e-9)
            assertEquals(-412.0 * 3.87 / 1_000, readouts.powerW!!, 1e-9)
            assertEquals(31.5, readouts.temperatureC!!, 1e-6)
            assertEquals(3.87, readouts.voltageV!!, 1e-9)
        }

        // The next raw capture has no ETA yet (the writer adds it just after): keep the last one, counted down.
        repo.realtime.value = BatteryRepository.Realtime(sample(T0 + 2 * SECOND, raw = -420), mA)
        runCurrent()
        assertEquals(Eta(5 * HOUR - 2 * SECOND, EtaBasis.LIVE_RATE), state().hero.eta)

        // Held for 60 s at most.
        repo.realtime.value = BatteryRepository.Realtime(sample(T0 + 61 * SECOND, raw = -420), mA)
        runCurrent()
        assertNull(state().hero.eta)

        // A basis this build doesn't know still shows the time, without a basis.
        repo.realtime.value = BatteryRepository.Realtime(sample(T0 + 62 * SECOND, eta = HOUR, basis = "FUTURE_MODEL"), mA)
        runCurrent()
        assertEquals(Eta(HOUR, null), state().hero.eta)

        // A power change drops a held estimate; the charger shows while plugged.
        repo.realtime.value = BatteryRepository.Realtime(sample(T0 + 64 * SECOND, status = 2, plugged = 1), mA)
        runCurrent()
        assertNull(state().hero.eta)
        assertEquals(PowerState.CHARGING, state().hero.power)
        assertEquals(ChargerType.AC, state().hero.charger)

        // No time left without monitoring, even when a sample carries one.
        monitoring.isMonitoring.value = false
        repo.realtime.value = BatteryRepository.Realtime(sample(T0 + 66 * SECOND, status = 2, plugged = 1, eta = HOUR, basis = "ANDROID"), mA)
        runCurrent()
        assertNull(state().hero.eta)
    }

    @Test fun startBlockedShowsUntilTheNextAttemptAndStopStops() = runTest {
        repo.realtime.value = BatteryRepository.Realtime(sample(T0))
        val (vm, state) = start()
        monitoring.result = MonitoringControl.StartResult.BLOCKED

        vm.onEvent(NowEvent.ToggleMonitoring)
        runCurrent()
        assertEquals(1, monitoring.starts)
        assertTrue(state().hero.startBlocked)
        assertFalse(state().hero.monitoring)

        monitoring.result = MonitoringControl.StartResult.STARTED
        vm.onEvent(NowEvent.ToggleMonitoring)
        runCurrent()
        assertFalse(state().hero.startBlocked)
        assertTrue(state().hero.monitoring)

        vm.onEvent(NowEvent.ToggleMonitoring)
        runCurrent()
        assertEquals(1, monitoring.stops)
        assertFalse(state().hero.monitoring)
        // Navigation events are the screen's: the ViewModel ignores them.
        vm.onEvent(NowEvent.OpenApps)
        assertEquals(2, monitoring.starts)
    }

    @Test fun liveTraceIsSeededFromStoredRowsThenAppendedFromRealtimeTrimmedAndRecalibrated() = runTest {
        repo.calibration.value = CalibrationState(effective = CurrentCalibration(CurrentUnit.MILLIAMPS))
        repo.samples.value = listOf(
            sample(T0 - 12 * MINUTE, raw = -300),
            sample(T0 - 9 * MINUTE, raw = -400),
            sample(T0 - 8 * MINUTE, raw = -410),
        )
        repo.realtime.value = BatteryRepository.Realtime(sample(T0 - 8 * MINUTE, raw = -410))
        val (_, state) = start()

        with(state().trace) {
            assertEquals(TraceRange.LIVE, range)
            assertEquals(T0 - 10 * MINUTE, repo.queries.single())
            assertEquals(listOf(T0 - 9 * MINUTE, T0 - 8 * MINUTE), points.map { it.timeMs })
            assertEquals(listOf(-400.0, -410.0), points.map { it.value })
            assertEquals(TimeWindow(T0 - 18 * MINUTE, T0 - 8 * MINUTE), window)
            assertEquals(NowMath.LIVE_MAX_GAP_MS, maxGapMs)
        }

        repo.realtime.value = BatteryRepository.Realtime(sample(T0, raw = -500))
        runCurrent()
        assertEquals(-500.0, state().trace.points.last().value!!, 1e-9)
        assertEquals(TimeWindow(T0 - 10 * MINUTE, T0), state().trace.window)

        // Older or foreign captures are not appended; a new monitoring generation breaks the line.
        repo.realtime.value = BatteryRepository.Realtime(sample(T0 - SECOND, raw = -1))
        runCurrent()
        repo.realtime.value = BatteryRepository.Realtime(sample(T0 + SECOND, raw = -1, source = "import"))
        runCurrent()
        repo.realtime.value = BatteryRepository.Realtime(sample(T0 + 2 * SECOND, raw = -520, observation = "gen-2"))
        runCurrent()
        val points = state().trace.points
        assertEquals(listOf(T0 - 9 * MINUTE, T0 - 8 * MINUTE, T0, T0 + SECOND, T0 + 2 * SECOND), points.map { it.timeMs })
        assertNull("gap marker between generations", points[3].value)

        // Ten minutes on, the seeded rows fell out of the window.
        repo.realtime.value = BatteryRepository.Realtime(sample(T0 + 9 * MINUTE + 30 * SECOND, raw = -450, observation = "gen-2"))
        runCurrent()
        assertTrue(state().trace.points.all { it.timeMs >= T0 - 30 * SECOND })

        // A calibration change recalibrates what is already on screen (rows stay raw).
        repo.calibration.value = CalibrationState()
        runCurrent()
        assertEquals(-0.45, state().trace.points.last().value!!, 1e-9)
    }

    @Test fun historyRangesQueryStoredRowsForTheirSpanWithGapMarkers() = runTest {
        repo.samples.value = listOf(
            sample(T0 - 50 * MINUTE, raw = -100_000),
            sample(T0 - 20 * MINUTE, raw = -200_000, observation = "gen-2"),
            sample(T0 - 10 * MINUTE, raw = -300_000, observation = "gen-2", boundary = "Collection interrupted"),
            sample(T0 - 5 * MINUTE, raw = -400_000, observation = "gen-2", source = "import"),
        )
        val (vm, state) = start()
        vm.onEvent(NowEvent.SelectRange(TraceRange.HOUR))
        runCurrent()

        with(state().trace) {
            assertEquals(TraceRange.HOUR, range)
            assertEquals(T0 - HOUR, repo.queries.last())
            assertEquals(listOf(-100.0, null, -200.0, null, -300.0), points.map { it.value })
            assertEquals(TimeWindow(T0 - HOUR, T0), window)
            assertNull(maxGapMs)
        }
    }

    @Test fun aFullDayOfRowsIsDownsampledForTheChart() = runTest {
        repo.samples.value = List(6_000) { i -> sample(T0 - 24 * HOUR + i * 14 * SECOND, raw = -100_000 - (i % 97) * 1_000L) }
        val (vm, state) = start()
        vm.onEvent(NowEvent.SelectRange(TraceRange.DAY))
        runCurrent()

        val points = state().trace.points
        assertTrue("downsampled to ${points.size}", points.size <= NowMath.DOWNSAMPLE_BUCKETS * 4)
        assertEquals(T0 - 24 * HOUR, points.first().timeMs)
        assertEquals(repo.samples.value.last().timestamp, points.last().timeMs)
        assertEquals(-196.0, points.minOf { it.value!! }, 1e-9)
    }

    @Test fun sinceUnplugTurnsDrainIntoPercentPerHourAndDeepSleep() = runTest {
        repo.observation.value = summary(chargeUah = 2_800_000, level = 70)
        val (_, state) = start()

        with(state().sinceUnplug!!) {
            assertEquals(T0 - 3 * HOUR, startedAtMs)
            assertEquals(T0, throughMs)
            // Counter full charge = 2.8 Ah × 100 / 70 = 4 Ah; 400 mA → 10 %/h, 100 mA → 2.5 %/h.
            assertEquals(400.0, screenOn.currentMa!!, 1e-9)
            assertEquals(10.0, screenOn.percentPerHour!!, 1e-9)
            assertEquals(HOUR, screenOn.durationMs)
            assertEquals(2.5, screenOff.percentPerHour!!, 1e-9)
            assertEquals(50.0, deepSleepPercent!!, 1e-9)
            assertFalse(paused)
        }

        // No counter: the combined capacity estimate stands in (5,000 mAh → 8 %/h).
        repo.sessions.value = listOf(session("s1", capacityMah = 5_000, confidence = "HIGH"))
        repo.observation.value = summary(chargeUah = null, level = 70, stopped = true)
        runCurrent()
        assertEquals(8.0, state().sinceUnplug!!.screenOn.percentPerHour!!, 1e-9)
        assertTrue(state().sinceUnplug!!.paused)

        // Neither: mA only.
        repo.sessions.value = emptyList()
        runCurrent()
        assertNull(state().sinceUnplug!!.screenOn.percentPerHour)
        assertEquals(400.0, state().sinceUnplug!!.screenOn.currentMa!!, 1e-9)

        repo.observation.value = ObservationSummary()
        runCurrent()
        assertNull(state().sinceUnplug)
    }

    @Test fun todayFollowsTheLocalDayAcrossMidnight() = runTest {
        val today = DailySummaryAggregator.epochDay(T0, ZoneOffset.UTC)
        repo.days.value = mapOf(
            today to DailySummary(today, screenOnMs = 7_800_000, screenOnDischargeUah = 800_000, screenOffDischargeUah = 440_000, chargedUah = 800_000),
        )
        repo.realtime.value = BatteryRepository.Realtime(sample(T0))
        val (_, state) = start()
        assertEquals(TodayState(usedMah = 1_240.0, chargedMah = 800.0, screenOnMs = 7_800_000), state().today)

        now = (today + 1) * 24 * HOUR + SECOND
        repo.realtime.value = BatteryRepository.Realtime(sample(now))
        runCurrent()
        assertNull(state().today)
    }

    @Test fun healthCombinesStoredEstimatesAndUsesTheDesignOverride() = runTest {
        repo.sessions.value = listOf(
            session("a", capacityMah = 4_200, confidence = "MEDIUM"),
            session("b", capacityMah = 4_100, confidence = "HIGH"),
            session("c", capacityMah = 4_300, confidence = "LOW"),
            session("d", capacityMah = 9_999, confidence = "GARBAGE"),
            session("e", capacityMah = null, confidence = null),
        )
        val (_, state) = start()
        with(state().health!!) {
            assertEquals(4_100, capacityMah)
            assertEquals(CapacityConfidence.HIGH, confidence)
            assertNull(healthPercent)
        }

        repo.settings.value = AppSettings(designCapacityMah = 5_000)
        runCurrent()
        assertEquals(82.0, state().health!!.healthPercent!!, 1e-9)

        repo.sessions.value = emptyList()
        runCurrent()
        assertNull(state().health)
    }

    @Test fun topAppsComeOnlyFromTheCachedDumpSinceUnplugWhenNewerThanTheBaseline() = runTest {
        // The seam has no way to start a dump: Now only ever reads the cache.
        repo.cached.value = AppUsageSnapshot(100, 1, T0 - 5 * MINUTE, listOf(row(CHROME, 1, 130.0), row(YOUTUBE, 2, 60.0), row(MAPS, 3, 8.0), row(GMS, 4, 2.0)))
        repo.baselines["s1"] = AppUsageSnapshot(100, 1, T0 - 2 * HOUR, listOf(row(CHROME, 1, 6.0), row(YOUTUBE, 2, 0.0)))
        repo.active.value = session("s1", type = SessionType.DISCHARGE)
        val (_, state) = start()

        with(state().topApps as TopAppsState.Ready) {
            assertEquals(AppUsageBasis.DELTA, basis)
            assertEquals(T0 - 5 * MINUTE, capturedAtMs)
            assertEquals(listOf("Chrome", "YouTube", "Maps"), rows.map { it.label })
            assertEquals(listOf(124.0, 60.0, 8.0), rows.map { it.powerMah })
            // Shares are of all apps, including the ones not shown (194 mAh).
            assertEquals((124.0 / 194.0).toFloat(), rows.first().share, 1e-6f)
            assertEquals(1, rows.first().uid)
            assertEquals(CHROME, rows.first().packageName)
        }

        // A baseline newer than the dump means the dump predates this session: absolute values.
        repo.baselines["s2"] = AppUsageSnapshot(100, 1, T0 - MINUTE, emptyList())
        repo.active.value = session("s2", type = SessionType.DISCHARGE)
        runCurrent()
        assertEquals(AppUsageBasis.ABSOLUTE, (state().topApps as TopAppsState.Ready).basis)
        assertEquals(130.0, (state().topApps as TopAppsState.Ready).rows.first().powerMah, 1e-9)

        // Charging: no discharge session, absolute too.
        repo.active.value = session("c1", type = SessionType.CHARGE)
        runCurrent()
        assertEquals(AppUsageBasis.ABSOLUTE, (state().topApps as TopAppsState.Ready).basis)

        repo.cached.value = null
        runCurrent()
        assertEquals(TopAppsState.Empty, state().topApps)
    }

    @Test fun calibrationNoticeUndoKeepAndResetGoToTheRepository() = runTest {
        val detected = CurrentCalibration(CurrentUnit.MILLIAMPS)
        repo.calibration.value = CalibrationState(effective = detected, detected = detected, noticePending = true)
        val (vm, state) = start()
        assertEquals(detected, state().calibrationNotice)

        vm.onEvent(NowEvent.UndoCalibration)
        vm.onEvent(NowEvent.KeepCalibration)
        vm.onEvent(NowEvent.ResetObservation)
        assertEquals(1, repo.undos)
        assertEquals(1, repo.dismissals)
        assertEquals(1, repo.resets)

        repo.calibration.value = CalibrationState(effective = detected, detected = detected)
        runCurrent()
        assertNull(state().calibrationNotice)
        assertNotNull(state().hero)
    }

    private class FakeNowRepository : NowRepository {
        override val realtime = MutableStateFlow(BatteryRepository.Realtime())
        override val observation = MutableStateFlow(ObservationSummary())
        override val calibration = MutableStateFlow(CalibrationState())
        override val settings = MutableStateFlow(AppSettings())
        val cached = MutableStateFlow<AppUsageSnapshot?>(null)
        override val cachedAppUsage: Flow<AppUsageSnapshot?> = cached
        val active = MutableStateFlow<ChargeSession?>(null)
        override val activeSession: Flow<ChargeSession?> = active
        val samples = MutableStateFlow<List<BatterySample>>(emptyList())
        val queries = mutableListOf<Long>()
        val days = MutableStateFlow<Map<Long, DailySummary>>(emptyMap())
        val sessions = MutableStateFlow<List<ChargeSession>>(emptyList())
        val baselines = mutableMapOf<String, AppUsageSnapshot>()
        var resets = 0
        var undos = 0
        var dismissals = 0

        override fun samplesSince(fromMs: Long): Flow<List<BatterySample>> {
            queries += fromMs
            return samples.map { rows -> rows.filter { it.timestamp >= fromMs } }
        }

        override fun day(epochDay: Long): Flow<DailySummary?> = days.map { it[epochDay] }
        override fun recentSessions(limit: Int): Flow<List<ChargeSession>> = sessions.map { it.take(limit) }
        override suspend fun baseline(sessionId: String) = baselines[sessionId]
        override fun resetObservation() { resets++ }
        override fun undoCalibration() { undos++ }
        override fun dismissCalibrationNotice() { dismissals++ }
    }

    private class FakeMonitoring : MonitoringControl {
        override val isMonitoring = MutableStateFlow(false)
        var result = MonitoringControl.StartResult.STARTED
        var starts = 0
        var stops = 0

        override fun start(): MonitoringControl.StartResult {
            starts++
            if (result == MonitoringControl.StartResult.STARTED) isMonitoring.value = true
            return result
        }

        override fun stop() {
            stops++
            isMonitoring.value = false
        }
    }

    private class FakeAppInfo(private val labels: Map<String, String>) : AppInfoSource {
        override suspend fun info(packageName: String) = AppInfo(packageName, labels[packageName] ?: packageName, isSystem = false, installed = true)
        override suspend fun icon(packageName: String): Bitmap? = null
    }

    private companion object {
        const val T0 = 1_760_001_600_000L
        const val SECOND = 1_000L
        const val MINUTE = 60 * SECOND
        const val HOUR = 60 * MINUTE
        const val CHROME = "com.android.chrome"
        const val YOUTUBE = "com.google.android.youtube"
        const val MAPS = "com.google.android.apps.maps"
        const val GMS = "com.google.android.gms"

        // Status 3 = discharging, 2 = charging; plugged 0 = battery, 1 = AC.
        fun sample(
            timestamp: Long,
            raw: Long? = -412_000,
            status: Int = 3,
            plugged: Int = if (status == 2) 1 else 0,
            eta: Long? = null,
            basis: String? = null,
            observation: String = "gen-1",
            boundary: String? = null,
            source: String = "BatteryManager",
        ) = BatterySample(
            timestamp = timestamp,
            levelPercent = 67,
            status = status,
            plugged = plugged,
            currentNowUa = raw,
            chargeCounterUah = 2_800_000,
            voltageMv = 3_870,
            temperatureDeciC = 315,
            health = 2,
            screenOn = true,
            observationId = observation,
            etaMs = eta,
            etaBasis = basis,
            source = source,
            boundaryReason = boundary,
        )

        fun summary(chargeUah: Long?, level: Int, stopped: Boolean = false) = ObservationSummary(
            startedAt = T0 - 3 * HOUR,
            latest = Observation(T0, 20_000_000, 12_000_000, level, chargeUah, -400_000, 3_870, PowerState.DISCHARGING, true, false, "gen-1"),
            screenOn = ObservedBucket(durationMs = HOUR, chargeCoveredMs = HOUR, chargeChangeUah = 400_000),
            screenOff = ObservedBucket(durationMs = 2 * HOUR, chargeCoveredMs = 2 * HOUR, chargeChangeUah = 200_000),
            cpuSuspendMs = 90 * MINUTE,
            cpuObservedMs = 3 * HOUR,
            stopped = stopped,
        )

        fun session(
            id: String,
            type: SessionType = SessionType.DISCHARGE,
            capacityMah: Int? = null,
            confidence: String? = null,
        ) = ChargeSession(
            sessionId = id,
            type = type,
            startTime = T0 - 2 * HOUR,
            endTime = null,
            startLevel = 90,
            endLevel = 67,
            deltaUah = null,
            avgCurrentUa = null,
            estCapacityMah = null,
            capacityEstimateMah = capacityMah,
            capacityConfidence = confidence,
            capacityBasis = confidence?.let { "COUNTER_SPAN" },
        )

        fun row(packageName: String, uid: Int, mah: Double) = AppUsageRow(uid = uid, packageName = packageName, powerMah = mah)
    }
}
