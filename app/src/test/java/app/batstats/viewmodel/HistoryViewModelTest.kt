package app.batstats.viewmodel

import androidx.lifecycle.SavedStateHandle
import app.batstats.battery.apps.AppUsageStatus
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.DailySummary
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.measurement.DailySummaryAggregator
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeHistoryRepository()
    private var now = T0

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.start(saved: SavedStateHandle = SavedStateHandle()): Pair<HistoryViewModel, () -> HistoryUiState> {
        val vm = HistoryViewModel(repo, saved, clock = { now }, zone = { ZoneOffset.UTC }, computeDispatcher = dispatcher)
        backgroundScope.launch { vm.state.collect { } }
        runCurrent()
        return vm to { vm.state.value }
    }

    @Test fun daysFillTheRangeOldestFirstAndAverageTheRecordedDaysWithoutToday() = runTest {
        repo.summaries.value = listOf(
            day(TODAY, onUah = 900_000, offUah = 100_000, onMs = 2 * HOUR, offMs = 2 * HOUR, chargedUah = 0, suspendMs = null),
            day(TODAY - 1, onUah = 600_000, offUah = 200_000, onMs = 2 * HOUR, offMs = 14 * HOUR, chargedUah = 1_450_000, suspendMs = 12 * HOUR),
            day(TODAY - 3, onUah = 400_000, offUah = 0, onMs = HOUR, offMs = 3 * HOUR, chargedUah = 550_000, suspendMs = 0),
            day(TODAY - 9, onUah = 999_000, offUah = 0, onMs = HOUR, offMs = 0, chargedUah = 0, suspendMs = null), // outside a week
        )
        val (_, state) = start()

        with(state()) {
            assertEquals(HistoryMode.DAYS, mode)
            assertEquals(DayRange.WEEK, range)
            assertEquals(TODAY, todayEpochDay)
            assertEquals((TODAY - 6..TODAY).toList(), days.days.map { it.epochDay })
            assertEquals(listOf(TODAY - 6..TODAY), repo.dayQueries)
            assertFalse(days.loading)
            assertTrue(days.recorded)
            assertEquals(listOf(TODAY - 3, TODAY - 1, TODAY), days.days.filter { it.figures != null }.map { it.epochDay })

            // No capacity known: totals stay in mAh.
            assertEquals(DrainUnit.MAH, days.unit)
            val yesterday = days.days.single { it.epochDay == TODAY - 1 }.figures!!
            assertEquals(600.0, yesterday.screenOnUsed!!, 1e-9)
            assertEquals(200.0, yesterday.screenOffUsed!!, 1e-9)
            assertEquals(800.0, yesterday.used!!, 1e-9)
            assertEquals(1_450.0, yesterday.charged, 1e-9)
            assertEquals(2 * HOUR, yesterday.screenOn.durationMs)
            // 12 h asleep of 16 h on battery.
            assertEquals(75.0, yesterday.deepSleepPercent!!, 1e-9)
            assertNull(days.days.single { it.epochDay == TODAY }.figures!!.deepSleepPercent)

            // Today isn't over, so the average is over yesterday and three days ago; deep sleep weighted by time.
            // Three days ago had screen-off time but no charge (a legacy row): unmeasured, so screen off averages
            // yesterday alone instead of counting that day as zero.
            val average = days.average!!
            assertEquals(500.0, average.screenOnUsed!!, 1e-9)
            assertEquals(200.0, average.screenOffUsed!!, 1e-9)
            assertEquals(1_000.0, average.charged, 1e-9)
            assertEquals(3 * HOUR / 2, average.screenOn.durationMs)
            assertEquals(12.0 / 20 * 100, average.deepSleepPercent!!, 1e-9)
            // 1,000 mAh over 3 h of screen on; 200 mAh over yesterday's 14 h of screen off. No capacity: no %/h.
            assertEquals(1_000.0 / 3, average.screenOn.currentMa!!, 1e-9)
            assertEquals(200.0 / 14, average.screenOff.currentMa!!, 1e-9)
            assertNull(average.screenOn.percentPerHour)
        }

        // Only today recorded: it is all there is, so it is the average.
        repo.summaries.value = listOf(day(TODAY, onUah = 900_000, offUah = 100_000, onMs = HOUR, offMs = HOUR, chargedUah = 0, suspendMs = null))
        runCurrent()
        assertEquals(900.0, state().days.average!!.screenOnUsed!!, 1e-9)

        repo.summaries.value = emptyList()
        runCurrent()
        assertFalse(state().days.recorded)
        assertNull(state().days.average)
    }

    @Test fun capacityComesFromTheLiveCounterElseTheStoredEstimateElseTotalsStayInMah() = runTest {
        repo.summaries.value = listOf(
            day(TODAY - 1, onUah = 500_000, offUah = 250_000, onMs = 2 * HOUR, offMs = 10 * HOUR, chargedUah = 1_000_000, suspendMs = null)
                .copy(screenOnCoveredMs = HOUR, screenOffCoveredMs = 5 * HOUR),
        )
        val (_, state) = start()
        fun yesterday() = state().days.days.single { it.epochDay == TODAY - 1 }.figures!!

        assertEquals(DrainUnit.MAH, state().days.unit)
        assertEquals(DrainState(2 * HOUR, 500.0, null), yesterday().screenOn)
        assertEquals(1_000.0, yesterday().charged, 1e-9)

        // Stored Health estimate: 5,000 mAh.
        repo.storedEstimateUah.value = 5_000_000
        runCurrent()
        assertEquals(DrainUnit.PERCENT, state().days.unit)
        assertEquals(DrainState(2 * HOUR, 500.0, 10.0), yesterday().screenOn)
        assertEquals(DrainState(10 * HOUR, 50.0, 1.0), yesterday().screenOff)
        assertEquals(15.0, yesterday().used!!, 1e-9)
        assertEquals(20.0, yesterday().charged, 1e-9)

        // The live counter's own full charge wins: 2,000 mAh at 50 % is a 4,000 mAh battery.
        repo.realtime.value = BatteryRepository.Realtime(sample = sample(T0).copy(chargeCounterUah = 2_000_000, levelPercent = 50))
        runCurrent()
        assertEquals(12.5, yesterday().screenOn.percentPerHour!!, 1e-9)
        assertEquals(25.0, yesterday().charged, 1e-9)
    }

    @Test fun rangeSelectionAndTheLocalDayDriveTheQuery() = runTest {
        val (vm, state) = start()
        vm.onEvent(HistoryEvent.SelectRange(DayRange.MONTH))
        runCurrent()
        assertEquals(DayRange.MONTH, state().range)
        assertEquals(30, state().days.days.size)
        assertEquals(TODAY - 29..TODAY, repo.dayQueries.last())

        // A reading still refreshes the day immediately when the clock changes.
        now = T0 + DAY
        repo.realtime.value = BatteryRepository.Realtime(sample(now))
        runCurrent()
        assertEquals(TODAY + 1, state().todayEpochDay)
        assertEquals(TODAY - 28..TODAY + 1, repo.dayQueries.last())
    }

    @Test fun todayAdvancesPastMidnightWithoutRealtimeEmissions() = runTest {
        val silentRepo = object : HistoryRepository by repo {
            override val realtime: Flow<BatteryRepository.Realtime> = emptyFlow()
        }
        val startMs = (TODAY + 1) * DAY - SECOND
        val vm = HistoryViewModel(
            silentRepo,
            SavedStateHandle(),
            clock = { startMs + testScheduler.currentTime },
            zone = { ZoneOffset.UTC },
            computeDispatcher = dispatcher,
        )
        backgroundScope.launch { vm.state.collect { } }
        runCurrent()

        advanceTimeBy(SECOND + 1)
        runCurrent()
        assertFalse(vm.state.value.monitoring)
        assertEquals("Today advances without any realtime capture", TODAY + 1, vm.state.value.todayEpochDay)
        assertEquals((TODAY - 5..TODAY + 1).toList(), vm.state.value.days.days.map { it.epochDay })
        assertEquals(TODAY - 5..TODAY + 1, repo.dayQueries.last())

        advanceTimeBy(DAY)
        runCurrent()
        assertEquals("The ticker repeats on the next midnight", TODAY + 2, vm.state.value.todayEpochDay)
        assertEquals(TODAY - 4..TODAY + 2, repo.dayQueries.last())
    }

    @Test fun midnightTickerUsesTheLocalZoneAcrossADaylightSavingDay() = runTest {
        val localZone = ZoneId.of("America/New_York")
        val startMs = Instant.parse("2025-11-02T03:59:59Z").toEpochMilli() // 23:59:59 before the 25-hour day.
        val firstDay = DailySummaryAggregator.epochDay(startMs, localZone)
        val vm = HistoryViewModel(
            repo,
            SavedStateHandle(),
            clock = { startMs + testScheduler.currentTime },
            zone = { localZone },
            computeDispatcher = dispatcher,
        )
        backgroundScope.launch { vm.state.collect { } }
        runCurrent()
        assertEquals(firstDay, vm.state.value.todayEpochDay)
        assertEquals(1, repo.dayQueries.size)

        advanceTimeBy(SECOND)
        runCurrent()
        assertEquals("The first local midnight, not UTC midnight", firstDay + 1, vm.state.value.todayEpochDay)
        assertEquals(2, repo.dayQueries.size)

        advanceTimeBy(DAY)
        runCurrent()
        assertEquals("A 25-hour local day has not ended after 24 hours", firstDay + 1, vm.state.value.todayEpochDay)
        assertEquals(2, repo.dayQueries.size)

        advanceTimeBy(HOUR)
        runCurrent()
        assertEquals("The next local midnight accounts for daylight saving", firstDay + 2, vm.state.value.todayEpochDay)
        assertEquals(firstDay - 4..firstDay + 2, repo.dayQueries.last())
    }

    @Test fun modeRangeChipAndSelectedDayAreSavedAndRestoredAfterProcessDeath() = runTest {
        repo.summaries.value = listOf(day(TODAY - 2, onUah = 100_000, offUah = 0, onMs = HOUR, offMs = 0, chargedUah = 0, suspendMs = null))
        val saved = SavedStateHandle()
        val (vm, state) = start(saved)
        vm.onEvent(HistoryEvent.SelectRange(DayRange.TWO_WEEKS))
        vm.onEvent(HistoryEvent.SelectDay(TODAY - 2))
        vm.onEvent(HistoryEvent.SelectFilter(SessionFilter.CHARGE))
        vm.onEvent(HistoryEvent.SelectMode(HistoryMode.SESSIONS))
        runCurrent()
        assertEquals(TODAY - 2, state().selectedDay)

        // A new ViewModel from the saved values (as after process death) shows the same view.
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        val (_, again) = start(restored)
        with(again()) {
            assertEquals(HistoryMode.SESSIONS, mode)
            assertEquals(DayRange.TWO_WEEKS, range)
            assertEquals(SessionFilter.CHARGE, filter)
            assertEquals(TODAY - 2, selectedDay)
        }
        assertEquals(SessionType.CHARGE to HistoryViewModel.PAGE_SIZE + 1, repo.sessionQueries.last())
    }

    @Test fun unknownSavedNamesFallBackAndASelectedDayOutsideTheRangeIsDropped() = runTest {
        val saved = SavedStateHandle(
            mapOf(
                HistoryViewModel.KEY_MODE to "TIMELINE",
                HistoryViewModel.KEY_RANGE to "YEAR",
                HistoryViewModel.KEY_FILTER to "PLUGGED",
                HistoryViewModel.KEY_DAY to TODAY - 20,
            ),
        )
        val (vm, state) = start(saved)
        with(state()) {
            assertEquals(HistoryMode.DAYS, mode)
            assertEquals(DayRange.WEEK, range)
            assertEquals(SessionFilter.ALL, filter)
            assertNull(selectedDay)
        }
        vm.onEvent(HistoryEvent.SelectRange(DayRange.MONTH))
        runCurrent()
        assertEquals(TODAY - 20, state().selectedDay)
        vm.onEvent(HistoryEvent.SelectDay(null))
        runCurrent()
        assertNull(state().selectedDay)
    }

    @Test fun sessionsMapSpansLevelsChargeAndTheAppUsageHint() = runTest {
        repo.recordingObservation.value = "obs-live"
        repo.sessionRows.value = listOf(
            // Open and being recorded: ends now.
            session("live", SessionType.DISCHARGE, start = T0 - 2 * HOUR, end = null, last = T0 - MINUTE, observation = "obs-live",
                delta = 420_000, status = AppUsageStatus.PENDING),
            // Left open by an earlier observation (process died): ends at its last saved reading.
            session("stale", SessionType.DISCHARGE, start = T0 - 30 * HOUR, end = null, last = T0 - 26 * HOUR, observation = "obs-old",
                delta = 900_000, status = AppUsageStatus.NO_ACCESS),
            session("charge", SessionType.CHARGE, start = T0 - 40 * HOUR, end = T0 - 39 * HOUR, last = T0 - 39 * HOUR, observation = "obs-old",
                delta = 1_200_000, status = AppUsageStatus.FAILED),
            session("failed", SessionType.DISCHARGE, start = T0 - 50 * HOUR, end = T0 - 45 * HOUR, last = T0 - 45 * HOUR, observation = "obs-old",
                delta = null, status = AppUsageStatus.FAILED),
        )
        val (_, state) = start()

        val rows = state().sessions.rows
        assertEquals(listOf("live", "stale", "charge", "failed"), rows.map { it.sessionId })
        with(rows[0]) {
            assertTrue(recording)
            assertEquals(T0, endMs)
            assertEquals(TODAY, startDay)
            assertEquals(420.0, chargeMah!!, 1e-9)
            assertNull(appUsage)
            assertEquals(80, startLevel)
            assertEquals(60, endLevel)
        }
        with(rows[1]) {
            assertFalse(recording)
            assertEquals(T0 - 26 * HOUR, endMs)
            assertEquals(TODAY - 1, startDay) // 30 h before 09:20 UTC is 03:20 yesterday
            assertEquals(AppUsageHint.NO_ACCESS, appUsage)
        }
        // A charge never gets the app-usage hint; a discharge with FAILED does.
        assertNull(rows[2].appUsage)
        assertEquals(T0 - 39 * HOUR, rows[2].endMs)
        assertEquals(AppUsageHint.FAILED, rows[3].appUsage)
        assertNull(rows[3].chargeMah)

        // Monitoring stops: the open row is no longer recording and ends at its last reading.
        repo.recordingObservation.value = null
        runCurrent()
        assertFalse(state().sessions.rows[0].recording)
        assertEquals(T0 - MINUTE, state().sessions.rows[0].endMs)
    }

    @Test fun sessionRowsHideChargeSessionDetailsWouldHide() = runTest {
        val measured = session("measured", SessionType.DISCHARGE, start = T0 - 3 * HOUR, end = T0 - 2 * HOUR, last = T0 - 2 * HOUR,
            observation = "obs-old", delta = 300_000, status = null)
        repo.sessionRows.value = listOf(
            measured,
            measured.copy(sessionId = "legacy", source = "legacy"),
            measured.copy(sessionId = "uncovered", counterCoveredMs = 0),
        )
        val (_, state) = start()

        val charge = state().sessions.rows.associate { it.sessionId to it.chargeMah }
        assertEquals(300.0, charge.getValue("measured")!!, 1e-9)
        assertNull("Legacy rows: SessionDetails hides it too", charge.getValue("legacy"))
        assertNull("The counter never covered it", charge.getValue("uncovered"))
    }

    @Test fun chipsFilterTheQueryAndPagesGrowFiftyAtATime() = runTest {
        repo.sessionRows.value = List(120) { i ->
            session("s$i", if (i % 2 == 0) SessionType.DISCHARGE else SessionType.CHARGE, start = T0 - i * HOUR, end = T0 - i * HOUR + MINUTE,
                last = T0 - i * HOUR + MINUTE, observation = "obs", delta = 1_000, status = null)
        }
        val saved = SavedStateHandle()
        val (vm, state) = start(saved)
        assertEquals(null to 51, repo.sessionQueries.last())
        assertEquals(50, state().sessions.rows.size)
        assertTrue(state().sessions.hasMore)

        vm.onEvent(HistoryEvent.LoadMore)
        runCurrent()
        assertEquals(null to 101, repo.sessionQueries.last())
        assertEquals(100, state().sessions.rows.size)

        // A chip starts again at the first page.
        vm.onEvent(HistoryEvent.SelectFilter(SessionFilter.DISCHARGE))
        runCurrent()
        assertEquals(SessionType.DISCHARGE to 51, repo.sessionQueries.last())
        assertEquals(SessionFilter.DISCHARGE, state().filter)
        assertEquals(50, state().sessions.rows.size)
        assertTrue(state().sessions.rows.all { it.type == SessionType.DISCHARGE })
        assertTrue(state().sessions.hasMore)
        assertEquals(50, saved.get<Int>(HistoryViewModel.KEY_PAGE_LIMIT))

        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        val (_, again) = start(restored)
        assertEquals("The filter's first-page reset is saved", SessionType.DISCHARGE to 51, repo.sessionQueries.last())
        assertEquals(50, again().sessions.rows.size)

        vm.onEvent(HistoryEvent.LoadMore)
        runCurrent()
        assertEquals(60, state().sessions.rows.size)
        assertFalse(state().sessions.hasMore)

        // Navigation is the screen's; the VM ignores it.
        val queries = repo.sessionQueries.size
        vm.onEvent(HistoryEvent.OpenSession("s0"))
        runCurrent()
        assertEquals(queries, repo.sessionQueries.size)
    }

    @Test fun loadedPageLimitAndRowsAreRestoredAfterProcessDeath() = runTest {
        repo.sessionRows.value = List(150) { i ->
            session("s$i", SessionType.DISCHARGE, start = T0 - i * HOUR, end = T0 - i * HOUR + MINUTE,
                last = T0 - i * HOUR + MINUTE, observation = "obs", delta = 1_000, status = null)
        }
        val saved = SavedStateHandle()
        val (vm, state) = start(saved)
        vm.onEvent(HistoryEvent.SelectMode(HistoryMode.SESSIONS))
        vm.onEvent(HistoryEvent.LoadMore)
        runCurrent()
        vm.onEvent(HistoryEvent.LoadMore)
        runCurrent()
        assertEquals(150, state().sessions.rows.size)
        assertEquals(null to 151, repo.sessionQueries.last())
        assertEquals(150, saved.get<Int>(HistoryViewModel.KEY_PAGE_LIMIT))

        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        val (restoredVm, again) = start(restored)
        assertEquals("The restored query retains the 150-row limit plus its lookahead", null to 151, repo.sessionQueries.last())
        assertEquals("All loaded rows survive ViewModel recreation", 150, again().sessions.rows.size)
        assertEquals(state().sessions.rows, again().sessions.rows)
        assertFalse(again().sessions.hasMore)

        val queries = repo.sessionQueries.size
        restoredVm.onEvent(HistoryEvent.Retry)
        runCurrent()
        assertEquals("Retry re-queries without resetting the restored page limit", queries + 1, repo.sessionQueries.size)
        assertEquals(null to 151, repo.sessionQueries.last())
        assertEquals(150, again().sessions.rows.size)
    }

    @Test fun aFailedReadShowsAndRetryReadsAgain() = runTest {
        repo.failSessions = true
        repo.failDays = true
        val (vm, state) = start()
        assertTrue(state().sessions.failed)
        assertFalse(state().sessions.loading)
        assertTrue(state().days.failed)

        repo.failSessions = false
        repo.failDays = false
        repo.sessionRows.value = listOf(session("s", SessionType.CHARGE, T0 - HOUR, T0, T0, "obs", 1_000, null))
        vm.onEvent(HistoryEvent.Retry)
        runCurrent()
        assertFalse(state().sessions.failed)
        assertEquals(listOf("s"), state().sessions.rows.map { it.sessionId })
        assertFalse(state().days.failed)
    }

    @Test fun showTodaySwitchesToDaysWithTodaySelected() = runTest {
        repo.summaries.value = listOf(day(TODAY, onUah = 900_000, offUah = 100_000, onMs = HOUR, offMs = HOUR, chargedUah = 0, suspendMs = null))
        val saved = SavedStateHandle()
        val (vm, state) = start(saved)
        vm.onEvent(HistoryEvent.SelectMode(HistoryMode.SESSIONS))
        vm.onEvent(HistoryEvent.SelectDay(TODAY - 2))
        runCurrent()

        vm.onEvent(HistoryEvent.ShowToday)
        runCurrent()

        assertEquals(HistoryMode.DAYS, state().mode)
        assertEquals(TODAY, state().selectedDay)
        assertEquals("Saved, so it survives process death", TODAY, saved.get<Long>(HistoryViewModel.KEY_DAY))
    }

    @Test fun monitoringIsPassedThrough() = runTest {
        val (_, state) = start()
        assertFalse(state().monitoring)
        repo.isMonitoring.value = true
        runCurrent()
        assertTrue(state().monitoring)
    }

    private class FakeHistoryRepository : HistoryRepository {
        override val realtime = MutableStateFlow(BatteryRepository.Realtime())
        override val isMonitoring = MutableStateFlow(false)
        override val recordingObservation = MutableStateFlow<String?>(null)
        override val storedEstimateUah = MutableStateFlow<Long?>(null)
        val summaries = MutableStateFlow<List<DailySummary>>(emptyList())
        val sessionRows = MutableStateFlow<List<ChargeSession>>(emptyList())
        val dayQueries = mutableListOf<LongRange>()
        val sessionQueries = mutableListOf<Pair<SessionType?, Int>>()
        var failDays = false
        var failSessions = false

        override fun days(fromDay: Long, toDay: Long): Flow<List<DailySummary>> = flow {
            dayQueries += fromDay..toDay
            if (failDays) throw IOException("disk")
            emitAll(summaries.map { rows -> rows.filter { it.epochDay in fromDay..toDay }.sortedBy { it.epochDay } })
        }

        override fun sessions(type: SessionType?, limit: Int): Flow<List<ChargeSession>> = flow {
            sessionQueries += type to limit
            if (failSessions) throw IOException("disk")
            emitAll(sessionRows.map { rows -> rows.filter { type == null || it.type == type }.take(limit) })
        }
    }

    private companion object {
        const val SECOND = 1_000L
        const val MINUTE = 60 * SECOND
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        const val T0 = 1_760_001_600_000L // 2025-10-09 09:20 UTC
        val TODAY = DailySummaryAggregator.epochDay(T0, ZoneOffset.UTC)

        fun day(epochDay: Long, onUah: Long, offUah: Long, onMs: Long, offMs: Long, chargedUah: Long, suspendMs: Long?) = DailySummary(
            epochDay = epochDay,
            screenOnMs = onMs,
            screenOffMs = offMs,
            screenOnDischargeUah = onUah,
            screenOffDischargeUah = offUah,
            chargedUah = chargedUah,
            cpuSuspendMs = suspendMs,
        )

        fun session(
            id: String,
            type: SessionType,
            start: Long,
            end: Long?,
            last: Long,
            observation: String,
            delta: Long?,
            status: AppUsageStatus?,
        ) = ChargeSession(
            sessionId = id,
            type = type,
            startTime = start,
            endTime = end,
            startLevel = 80,
            endLevel = 60,
            deltaUah = delta,
            avgCurrentUa = null,
            estCapacityMah = null,
            observationId = observation,
            lastSampleTime = last,
            counterCoveredMs = HOUR,
            source = "BatteryManager",
            appUsageStatus = status,
        )

        fun sample(timestamp: Long) = BatterySample(
            timestamp = timestamp,
            levelPercent = 60,
            status = 3,
            plugged = 0,
            currentNowUa = -400_000,
            chargeCounterUah = null,
            voltageMv = 3_900,
            temperatureDeciC = 300,
            health = null,
            screenOn = true,
        )
    }
}
