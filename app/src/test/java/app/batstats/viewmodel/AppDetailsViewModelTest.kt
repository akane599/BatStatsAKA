package app.batstats.viewmodel

import app.batstats.battery.apps.AppLabel
import app.batstats.battery.apps.AppStatsResult
import app.batstats.battery.util.BatteryStatsParser
import app.batstats.battery.util.BatteryStatsParser.WakelockType
import app.batstats.battery.util.ShellRunner
import app.batstats.viewmodel.AppsViewModelTest.Companion.CHROME
import app.batstats.viewmodel.AppsViewModelTest.Companion.CHROME_UID
import app.batstats.viewmodel.AppsViewModelTest.Companion.GONE_UID
import app.batstats.viewmodel.AppsViewModelTest.Companion.NOW
import app.batstats.viewmodel.AppsViewModelTest.Companion.YOUTUBE
import app.batstats.viewmodel.AppsViewModelTest.Companion.YOUTUBE_UID
import app.batstats.viewmodel.AppsViewModelTest.Companion.dump
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class AppDetailsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val source = FakeAppStats()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.start(uid: Int = CHROME_UID, packageName: String = CHROME): Pair<AppDetailsViewModel, () -> AppDetailsUiState> {
        val vm = AppDetailsViewModel(source, uid, packageName, clock = { NOW }, computeDispatcher = dispatcher)
        backgroundScope.launch { vm.state.collect { } }
        runCurrent()
        return vm to { vm.state.value }
    }

    @Test fun detailsCoverOnlyThisUidLargestFirst() = runTest {
        source.next = { AppStatsResult.Ready(detailedDump()) }
        val (vm, state) = start()
        vm.onStart()
        runCurrent()
        assertEquals(listOf(false), source.calls)

        with(state()) {
            assertEquals(AppLabel.Named("Chrome"), label)
            assertTrue(canOpenAppInfo)
            assertFalse(loading)
            assertNull(problem)
            assertEquals(NOW - 60_000, capturedAtMs)
            assertEquals(NOW - 14 * HOUR, startedAtMs)
            val usage = checkNotNull(usage)
            assertEquals(124.0, usage.powerMah, 1e-9)
            assertEquals(124f / 240f, usage.share, 1e-6f)
            assertEquals(2 * HOUR, usage.foregroundMs)
            assertEquals(10 * MINUTE, usage.foregroundServiceMs)
            assertEquals(5_000L, usage.backgroundMs)
            assertEquals(HOUR, usage.cachedMs)
            assertEquals(30_000L, usage.cpuTimeMs)
            assertEquals(4 * MINUTE, usage.wakelockTimeMs)
            // Chrome's wakelocks only, longest first; a partial one keeps the CPU on, the rest the screen.
            assertEquals(
                listOf(
                    WakelockItem("*job*/chrome/.Sync", WakelockKind.CPU, 3 * MINUTE, 12),
                    WakelockItem("Chrome video", WakelockKind.SCREEN, MINUTE, 2),
                ),
                usage.wakelocks,
            )
            assertEquals(listOf(AlarmItem("chrome.refresh", 9), AlarmItem("chrome.ping", 3)), usage.alarms)
            assertEquals(listOf(TaskItem("chrome/.Prefetch", 4, 2 * MINUTE)), usage.jobs)
            assertEquals(listOf(TaskItem("com.android.chrome.bookmarks", 3, 20_000L)), usage.syncs)
            assertEquals(NetworkUsage(null, null, 1_000L, 500L, radioActiveMs = 45_000L), usage.network)
            // Hardware Android counted; zero times are left out.
            assertEquals(HardwareUsage(gpsMs = 5 * MINUTE, cameraMs = MINUTE), usage.hardware)
        }
    }

    @Test fun anAppMissingFromTheDumpAndAnUninstalledAppSayWhatTheyAre() = runTest {
        source.next = { AppStatsResult.Ready(detailedDump()) }
        // Not in the dump, not installed, an app uid: an unknown app with no App info.
        val (vm, state) = start(uid = 10_777, packageName = "com.example.gone")
        vm.onStart()
        runCurrent()
        with(state()) {
            assertEquals(AppLabel.Unknown, label)
            assertFalse(canOpenAppInfo)
            assertNotNull("a dump was read", capturedAtMs)
            assertNull("Android counted nothing for it", usage)
        }

        // A uid-only system row: a system process.
        val (_, system) = start(uid = 1_041, packageName = "System UID 1041")
        assertEquals(AppLabel.SystemProcess, system().label)
    }

    @Test fun anAppWithLittleActivityHasNoListsNetworkOrHardware() = runTest {
        source.cached.value = dump()
        val (_, state) = start(uid = GONE_UID, packageName = "UID $GONE_UID")
        val usage = checkNotNull(state().usage)
        assertEquals(8.0, usage.powerMah, 1e-9)
        assertTrue(usage.wakelocks.isEmpty() && usage.alarms.isEmpty() && usage.jobs.isEmpty() && usage.syncs.isEmpty())
        assertNull(usage.network)
        assertEquals(HardwareUsage(), usage.hardware)
    }

    @Test fun historyIsTheNewest14SessionsOldestFirstOverThe30DayWindow() = runTest {
        source.sessions = List(16) { i ->
            AppSessionUsage("s$i", NOW - (16 - i) * DAY, powerMah = if (i % 3 == 0) null else i.toDouble())
        }.shuffled(kotlin.random.Random(7))
        val (_, state) = start(uid = YOUTUBE_UID, packageName = YOUTUBE)

        assertEquals(listOf(Triple(YOUTUBE_UID, NOW - AppDetailsViewModel.HISTORY_WINDOW_MS, NOW)), source.historyCalls)
        val history = checkNotNull(state().history)
        assertEquals((2 until 16).map { "s$it" }, history.sessions.map { it.sessionId })
        // Sessions 3, 6, 9, 12 and 15 didn't list the app.
        assertEquals(9, history.listedIn)
    }

    @Test fun refreshForcesAReadAndReloadsHistory() = runTest {
        source.next = { AppStatsResult.Ready(detailedDump()) }
        val (vm, _) = start()
        vm.onStart()
        runCurrent()
        vm.onEvent(AppDetailsEvent.Refresh)
        runCurrent()
        assertEquals(listOf(false, true), source.calls)
        assertEquals(2, source.historyCalls.size)
    }

    @Test fun noAccessShowsTheProblemWithoutUsage() = runTest {
        source.next = { AppStatsResult.NoAccess }
        source.accessNow = AccessSnapshot(ShellRunner.Mode.NONE, null, ShizukuState(running = false, granted = false))
        val (vm, state) = start()
        vm.onStart()
        runCurrent()
        with(state()) {
            assertEquals(StatsProblem.NoAccess(AccessProblem.NOT_SET_UP), problem)
            assertNull(capturedAtMs)
            assertNull(usage)
            assertEquals(AppLabel.Named("Chrome"), label)
        }
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR

        fun detailedDump(): BatteryStatsParser.FullSnapshot {
            val base = dump()
            val chrome = base.apps.first { it.uid == CHROME_UID }.copy(
                topTimeMs = 2 * HOUR,
                foregroundServiceTimeMs = 10 * MINUTE,
                cachedTimeMs = HOUR,
                wakeLockTimeMs = 4 * MINUTE,
                gpsTimeMs = 5 * MINUTE,
                sensorTimeMs = 0,
                cameraTimeMs = MINUTE,
            )
            val other = YOUTUBE_UID
            return base.copy(
                apps = base.apps.map { if (it.uid == CHROME_UID) chrome else it },
                wakelocks = listOf(
                    BatteryStatsParser.WakelockStats(CHROME_UID, CHROME, tag = "Chrome video", type = WakelockType.FULL, count = 2, totalTimeMs = MINUTE),
                    BatteryStatsParser.WakelockStats(other, YOUTUBE, tag = "yt", type = WakelockType.PARTIAL, count = 1, totalTimeMs = HOUR),
                    BatteryStatsParser.WakelockStats(CHROME_UID, CHROME, tag = "*job*/chrome/.Sync", type = WakelockType.PARTIAL, count = 12, totalTimeMs = 3 * MINUTE),
                ),
                alarms = listOf(
                    BatteryStatsParser.AlarmStats(CHROME_UID, CHROME, tag = "chrome.ping", count = 3, wakeups = 3, totalTimeMs = null),
                    BatteryStatsParser.AlarmStats(other, YOUTUBE, tag = "yt.alarm", count = 50, wakeups = 50, totalTimeMs = null),
                    BatteryStatsParser.AlarmStats(CHROME_UID, CHROME, tag = "chrome.refresh", count = 9, wakeups = 9, totalTimeMs = null),
                ),
                jobs = listOf(
                    BatteryStatsParser.JobStats(other, YOUTUBE, jobName = "yt/.Job", count = 1, totalTimeMs = HOUR),
                    BatteryStatsParser.JobStats(CHROME_UID, CHROME, jobName = "chrome/.Prefetch", count = 4, totalTimeMs = 2 * MINUTE),
                ),
                syncs = listOf(
                    BatteryStatsParser.SyncStats(CHROME_UID, CHROME, authority = "com.android.chrome.bookmarks", count = 3, totalTimeMs = 20_000),
                ),
                network = listOf(
                    BatteryStatsParser.NetworkStats(CHROME_UID, CHROME, mobileRxBytes = 0, mobileTxBytes = 0, wifiRxBytes = 1_000, wifiTxBytes = 500, mobileActiveTimeMs = 45_000),
                    BatteryStatsParser.NetworkStats(other, YOUTUBE, mobileRxBytes = 1, mobileTxBytes = 1, wifiRxBytes = 1, wifiTxBytes = 1, mobileActiveTimeMs = 1),
                ),
            )
        }
    }
}
