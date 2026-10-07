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
import kotlinx.coroutines.CancellationException
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
    private val warnings = mutableListOf<String>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.start(
        uid: Int = CHROME_UID,
        packageName: String = CHROME,
        repository: AppDetailsRepository = source,
    ): Pair<AppDetailsViewModel, () -> AppDetailsUiState> {
        val vm = AppDetailsViewModel(repository, uid, packageName, clock = { NOW }, computeDispatcher = dispatcher, warn = { warnings += it })
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

    @Test fun reusedApplicationUidDoesNotAttributeReplacementPackagesLiveUsageToTheRoute() = runTest {
        val base = detailedDump()
        source.next = {
            AppStatsResult.Ready(base.copy(apps = base.apps.map { app ->
                if (app.uid == CHROME_UID) app.copy(packageName = YOUTUBE, packages = listOf(YOUTUBE)) else app
            }))
        }
        val (vm, state) = start()
        vm.onStart()
        runCurrent()

        assertEquals(AppLabel.Named("Chrome"), state().label)
        assertEquals(base.capturedAt, state().capturedAtMs)
        assertNull("the replacement app's drain and activity must not appear under Chrome", state().usage)
    }

    @Test fun matchingPackageInSharedApplicationUidShowsLiveUsageRegardlessOfPackageOrder() = runTest {
        val base = detailedDump()
        source.cached.value = base.copy(apps = base.apps.map { app ->
            if (app.uid == CHROME_UID) app.copy(packageName = "Shared UID $CHROME_UID", packages = listOf(YOUTUBE, CHROME)) else app
        })
        val (_, state) = start()

        assertEquals(124.0, checkNotNull(state().usage).powerMah, 1e-9)
        source.cached.value = base.copy(apps = base.apps.map { app ->
            if (app.uid == CHROME_UID) app.copy(packages = listOf(CHROME, YOUTUBE)) else app
        })
        runCurrent()
        assertEquals(124.0, checkNotNull(state().usage).powerMah, 1e-9)
    }

    @Test fun unknownPackageMembershipDoesNotFallBackToTheDisplayPackage() = runTest {
        val base = detailedDump()
        source.cached.value = base.copy(apps = base.apps.map { app ->
            if (app.uid == CHROME_UID) app.copy(packages = emptyList()) else app
        })
        val (_, state) = start()

        assertNotNull(state().capturedAtMs)
        assertNull("unknown packages are not evidence that the UID still belongs to Chrome", state().usage)
    }

    @Test fun losingPackageMembershipOnRefreshClearsPreviousLiveUsage() = runTest {
        source.cached.value = detailedDump()
        val (vm, state) = start()
        assertNotNull(state().usage)
        val base = detailedDump()
        source.next = {
            AppStatsResult.Ready(base.copy(apps = base.apps.map { app ->
                if (app.uid == CHROME_UID) app.copy(packages = listOf(YOUTUBE)) else app
            }))
        }
        vm.onEvent(AppDetailsEvent.Refresh)
        runCurrent()

        assertNull("refresh must not retain usage after the route package leaves the UID", state().usage)
    }

    @Test fun secondaryUserSystemUidShowsLiveUsageWithoutPackageMembership() = runTest {
        val uid = 1_001_000
        source.cached.value = dump().copy(apps = listOf(
            BatteryStatsParser.AppPowerStats(uid, "android", 20.0, packages = listOf("android")),
        ))
        val (_, state) = start(uid = uid, packageName = "com.android.settings")
        assertEquals(20.0, checkNotNull(state().usage).powerMah, 1e-9)

        source.cached.value = dump().copy(apps = listOf(
            BatteryStatsParser.AppPowerStats(uid, "System UID $uid", 20.0),
        ))
        runCurrent()
        assertEquals(20.0, checkNotNull(state().usage).powerMah, 1e-9)
    }

    @Test fun anAppWithLittleActivityHasNoListsNetworkOrHardware() = runTest {
        val packageName = "com.example.quiet"
        val base = dump()
        source.cached.value = base.copy(apps = base.apps.map { app ->
            if (app.uid == GONE_UID) app.copy(packageName = packageName, packages = listOf(packageName)) else app
        })
        val (_, state) = start(uid = GONE_UID, packageName = packageName)
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
        val history = (state().history as AppHistoryState.Loaded).history
        assertEquals((2 until 16).map { "s$it" }, history.sessions.map { it.sessionId })
        // Sessions 3, 6, 9, 12 and 15 didn't list the app.
        assertEquals(9, history.listedIn)
    }

    @Test fun aFailedHistoryReadIsAFailureNotAnEmptyHistoryAndRetryReadsAgain() = runTest {
        val repository = HistorySource(source)
        repository.answer = { throw IllegalStateException("SELECT * FROM session_app_usage WHERE packageName = 'com.android.chrome'") }
        val (vm, state) = start(repository = repository)

        assertEquals("a throwing read must not look like no sessions", AppHistoryState.Failed, state().history)
        assertEquals("logged by type only, without the message", listOf("History read failed (IllegalStateException)"), warnings)

        val sessions = listOf(AppSessionUsage("s1", NOW - DAY, 12.0))
        repository.answer = { sessions }
        vm.onEvent(AppDetailsEvent.RetryHistory)
        runCurrent()

        assertEquals(2, repository.reads)
        assertEquals(AppHistoryState.Loaded(AppHistory(sessions)), state().history)
    }

    @Test fun anEmptyHistoryReadIsLoadedWithNoSessions() = runTest {
        val (_, state) = start()
        assertEquals(AppHistoryState.Loaded(AppHistory(emptyList())), state().history)
        assertTrue(warnings.isEmpty())
    }

    @Test fun aCancelledHistoryReadIsNotReportedAsFailed() = runTest {
        val repository = HistorySource(source)
        repository.answer = { throw CancellationException("screen left") }
        val (_, state) = start(repository = repository)

        assertEquals(1, repository.reads)
        assertEquals("cancellation propagates instead of becoming a failure", AppHistoryState.Loading, state().history)
        assertTrue(warnings.isEmpty())
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

    /** [base] with a history read that answers through [answer] (which may throw). */
    private class HistorySource(base: FakeAppStats) : AppDetailsRepository by base {
        var answer: suspend () -> List<AppSessionUsage> = { emptyList() }
        var reads = 0

        override suspend fun history(uid: Int, packageName: String, fromMs: Long, toMs: Long): List<AppSessionUsage> {
            reads++
            return answer()
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
