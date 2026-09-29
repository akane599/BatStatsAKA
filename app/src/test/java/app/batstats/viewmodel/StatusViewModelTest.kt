package app.batstats.viewmodel

import app.batstats.battery.diagnostics.DiagnosticCode
import app.batstats.battery.diagnostics.DiagnosticEvent
import app.batstats.battery.measurement.CalibrationSource
import app.batstats.battery.measurement.CalibrationState
import app.batstats.battery.measurement.CurrentCalibration
import app.batstats.battery.measurement.CurrentSign
import app.batstats.battery.measurement.CurrentUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatusViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeStatusRepository()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.start(): Pair<StatusViewModel, () -> StatusUiState> {
        val vm = StatusViewModel(repo, clock = { NOW })
        backgroundScope.launch { vm.state.collect { } }
        runCurrent()
        return vm to { vm.state.value }
    }

    @Test fun accessIsProbedOnOpenAndAgainWhenShizukuChanges() = runTest {
        repo.detected = AccessMode.ROOT
        val (_, state) = start()
        assertEquals(listOf(false), repo.probes)
        with(state().access) {
            assertEquals(AccessMode.ROOT, mode)
            assertFalse(checking)
            assertEquals(COMMANDS, adbCommands)
            assertTrue(adbCoversAppStats)
            assertFalse(canAuthorizeShizuku)
        }

        // Shizuku starts but isn't authorized yet: probe again, offer authorization.
        repo.detected = AccessMode.NONE
        repo.shizuku.value = ShizukuState(running = true, granted = false)
        runCurrent()
        assertEquals(listOf(false, false), repo.probes)
        assertEquals(AccessMode.NONE, state().access.mode)
        assertTrue(state().access.canAuthorizeShizuku)

        // Authorized: probed once more, and Shizuku is in use.
        repo.detected = AccessMode.SHIZUKU
        repo.shizuku.value = ShizukuState(running = true, granted = true)
        runCurrent()
        assertEquals(3, repo.probes.size)
        assertEquals(AccessMode.SHIZUKU, state().access.mode)
        assertFalse(state().access.canAuthorizeShizuku)
    }

    @Test fun checkAgainProbesAfreshShowsCheckingAndRunsOneProbeAtATime() = runTest {
        val (vm, state) = start()
        repo.probes.clear()

        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        repo.detected = AccessMode.ADB
        vm.onEvent(StatusEvent.RecheckAccess)
        runCurrent()
        assertTrue(state().access.checking)
        assertEquals(listOf(true), repo.probes)

        // A second tap while it runs is covered by the running probe.
        vm.onEvent(StatusEvent.RecheckAccess)
        runCurrent()
        assertEquals(listOf(true), repo.probes)

        gate.complete(Unit)
        runCurrent()
        assertFalse(state().access.checking)
        assertEquals(AccessMode.ADB, state().access.mode)

        // A failed probe ends the checking state and keeps the last mode.
        repo.gate = null
        repo.probeFailure = IllegalStateException("su crashed")
        vm.onEvent(StatusEvent.RecheckAccess)
        runCurrent()
        assertFalse(state().access.checking)
        assertEquals(AccessMode.ADB, state().access.mode)
    }

    @Test fun adbOnAndroid16IsFlaggedAndAuthorizeGoesToShizuku() = runTest {
        repo.adbCoversAppStats = false
        val (vm, state) = start()
        assertFalse(state().access.adbCoversAppStats)
        vm.onEvent(StatusEvent.AuthorizeShizuku)
        assertEquals(1, repo.authorizations)
        // Clipboard and sharing are the screen's.
        vm.onEvent(StatusEvent.CopyCommands)
        vm.onEvent(StatusEvent.ShareReport)
        assertEquals(1, repo.authorizations)
    }

    @Test fun calibrationShowsTheCalibrationInUseAndTheCorrectionNoticeWithUndoAndKeep() = runTest {
        val (vm, state) = start()
        assertEquals(CalibrationStatus(), state().calibration)

        val detected = CurrentCalibration(CurrentUnit.MILLIAMPS, CurrentSign.INVERTED)
        repo.calibration.value = CalibrationState(detected, detected, CalibrationSource.DETECTED, agreeingWindows = 3, noticePending = true)
        runCurrent()
        assertEquals(
            CalibrationStatus(CurrentUnit.MILLIAMPS, CurrentSign.INVERTED, CalibrationSource.DETECTED, notice = detected),
            state().calibration,
        )

        vm.onEvent(StatusEvent.UndoCalibration)
        vm.onEvent(StatusEvent.KeepCalibration)
        assertEquals(1, repo.undos)
        assertEquals(1, repo.keeps)

        // An override wins over detection; the notice goes once dismissed.
        repo.calibration.value = CalibrationState(
            CurrentCalibration(CurrentUnit.MICROAMPS, CurrentSign.INVERTED), detected, CalibrationSource.OVERRIDE,
        )
        runCurrent()
        with(state().calibration) {
            assertEquals(CurrentUnit.MICROAMPS, unit)
            assertEquals(CurrentSign.INVERTED, sign)
            assertEquals(CalibrationSource.OVERRIDE, source)
            assertNull(notice)
        }
    }

    @Test fun issuesAreProblemsOnlyNewestFirst() = runTest {
        val (_, state) = start()
        assertEquals(emptyList<StatusIssue>(), state().issues)

        repo.events.value = listOf(
            DiagnosticEvent(DiagnosticCode.MONITORING_STARTED, 100, 100),
            DiagnosticEvent(DiagnosticCode.OBSERVATION_GAP, 200, 900, count = 4),
            DiagnosticEvent(DiagnosticCode.ACCESS_SHIZUKU, 950, 950),
            DiagnosticEvent(DiagnosticCode.HISTORY_WRITE_FAILED, 1_000, 1_000),
            DiagnosticEvent(DiagnosticCode.ADVANCED_RECOVERED, 1_100, 1_100),
            DiagnosticEvent(DiagnosticCode.SYSTEM_STATS_RESET, 1_200, 1_200),
            DiagnosticEvent(DiagnosticCode.MONITORING_STOPPED, 1_300, 1_300),
        )
        repo.logUnavailable.value = true
        runCurrent()
        assertEquals(
            listOf(
                StatusIssue(StatusIssueKind.HISTORY_WRITE_FAILED, 1_000, 1_000, 1),
                StatusIssue(StatusIssueKind.OBSERVATION_GAP, 200, 900, 4),
            ),
            state().issues,
        )
        assertTrue(state().issueLogUnavailable)
        assertEquals(NOW, state().nowMs)

        // Every problem code has plain words; every other code is context and never listed.
        val every = DiagnosticCode.entries.mapIndexed { i, code -> DiagnosticEvent(code, i.toLong(), i.toLong()) }
        assertEquals(StatusIssueKind.entries.size, StatusViewModel.issues(every).size)
    }

    @Test fun reportComesFromTheRepositoryAndAFailedShareIsShown() = runTest {
        val (vm, state) = start()
        assertEquals("report", vm.report())
        assertFalse(state().shareUnavailable)
        vm.onShareResult(shared = false)
        runCurrent()
        assertTrue(state().shareUnavailable)
        vm.onShareResult(shared = true)
        runCurrent()
        assertFalse(state().shareUnavailable)
    }

    private class FakeStatusRepository : StatusRepository {
        val accessMode = MutableStateFlow(AccessMode.NONE)
        override val access = accessMode
        override val shizuku = MutableStateFlow(ShizukuState())
        override val calibration = MutableStateFlow(CalibrationState())
        override val events = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
        override val logUnavailable = MutableStateFlow(false)
        override val adbCommands = COMMANDS
        override var adbCoversAppStats = true
        var detected = AccessMode.NONE
        val probes = mutableListOf<Boolean>()
        var gate: CompletableDeferred<Unit>? = null
        var probeFailure: Exception? = null
        var authorizations = 0
        var undos = 0
        var keeps = 0

        override suspend fun detectAccess(recheck: Boolean): AccessMode {
            probes += recheck
            gate?.await()
            probeFailure?.let { throw it }
            accessMode.value = detected
            return detected
        }

        override fun requestShizukuPermission() {
            authorizations++
        }

        override fun undoCalibration() {
            undos++
        }

        override fun keepCalibration() {
            keeps++
        }

        override fun report() = "report"
    }

    private companion object {
        const val NOW = 1_760_001_600_000L
        val COMMANDS = listOf(
            "adb shell pm grant org.example DUMP",
            "adb shell pm grant org.example PACKAGE_USAGE_STATS",
            "adb shell appops set org.example GET_USAGE_STATS allow",
        )
    }
}
