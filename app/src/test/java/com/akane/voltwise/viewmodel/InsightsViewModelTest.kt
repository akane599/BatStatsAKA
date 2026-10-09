package com.akane.voltwise.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.akane.voltwise.battery.data.db.InsightActionEntity
import com.akane.voltwise.battery.data.db.InsightActionStatus
import com.akane.voltwise.battery.data.db.*
import com.akane.voltwise.battery.data.sampling.FakeKeyValueStore
import com.akane.voltwise.battery.insights.*
import com.akane.voltwise.battery.util.ShellRunner
import com.akane.voltwise.battery.shizuku.ShizukuBridge
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import com.akane.voltwise.battery.insights.actions.*
import com.akane.voltwise.battery.insights.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val source = FakeInsightsRepository()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()

    private fun TestScope.start(saved: SavedStateHandle = SavedStateHandle()): InsightsViewModel {
        val vm = InsightsViewModel(source, backgroundScope, saved)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        return vm
    }

    @Test fun defaultAdapterUsesSharedEligibilityAccessAndExplicitRefresh() = runTest {
        val now = com.akane.voltwise.battery.insights.NOW
        val local = testSession()
        val sessions = MutableStateFlow(listOf(local, local.copy(sessionId = "open", endTime = null),
            local.copy(sessionId = "import:one"), local.copy(sessionId = "short", appCaptureEndMs = local.appCaptureStartMs?.plus(1000))))
        val sessionDao = object : UnusedSessionDao() {
            override fun filteredSessions(type: SessionType?, query: String, limit: Int) = sessions
            override suspend fun closedSessionsBetween(from: Long, to: Long) = sessions.value.filter { it.endTime != null }
            override fun capacityEstimates(limit: Int) = flowOf(emptyList<CapacityEstimateRow>())
        }
        val rows = MutableStateFlow<List<InsightFindingEntity>>(emptyList())
        val dao = object : UnusedInsightDao() {
            override fun findings() = rows
            override suspend fun findingsOnce() = rows.value
            override fun actions() = source.actions
            override suspend fun actionsOnce() = source.actions.value
            override suspend fun upsertFindings(list: List<InsightFindingEntity>) { rows.value = list }
            override suspend fun setStatus(key: String, status: InsightFindingStatus) {
                rows.value = rows.value.map { if (it.key == key) it.copy(status = status) else it }
            }
        }
        var dumps = 0
        val insights = InsightRepository(
            sessionDao,
            object : UnusedDailySummaryDao() { override suspend fun range(fromDay: Long, toDay: Long) = emptyList<DailySummary>() },
            object : UnusedAppUsageDao() {
                override suspend fun usageRowsForSessions(sessionIds: List<String>) = emptyList<SessionAppUsage>()
                override suspend fun sessionWakers(sessionIds: List<String>) = emptyList<SessionDeviceWaker>()
            },
            dao, backgroundScope, Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
            { null }, { true }, { dumps++ }, FakeKeyValueStore(),
            ioDispatcher = dispatcher, analyzeDispatcher = dispatcher,
            analyze = { InsightReport(it.nowMs, listOf(insightFinding()), insightFinding()) },
        )
        val journal = InsightActionRepository(dao, { error("observation must never execute") }, object : TargetInspector {
            override val sdkInt = 37
            override fun installedUid(pkg: String, userId: Int): Int? = error("unexpected inspection")
            override fun packagesForUid(uid: Int): List<String> = error("unexpected inspection")
            override fun roleHolders(): Set<String> = error("unexpected inspection")
        }, { now }, {})
        var mode = ShellRunner.Mode.NONE
        val shell = ShellRunner({ mode }, { _, _ -> error("unexpected shell call") }, { false }, { 0L })
        val adapter = DefaultInsightsRepository(insights, journal, shell, sessionDao, { now })
        assertSame(insights.report, adapter.report)
        assertEquals(1, adapter.eligibleSessionCount.first())
        assertEquals(0, dumps)
        for (next in ShellRunner.Mode.entries) {
            mode = next
            shell.detectMode(forceRefresh = true)
            assertEquals(next == ShellRunner.Mode.ROOT || next == ShellRunner.Mode.SHIZUKU, adapter.privileged.first())
        }
        adapter.analyzeNow()
        assertEquals(1, dumps)
        assertEquals(now, adapter.lastAnalyzedAt.first())
        assertEquals("finding", adapter.report.value?.findings?.single()?.key)
        val manual = insightFinding().recommendations.last()
        assertTrue(adapter.apply(insightFinding(), manual) is ActionResult.OpenSettings)
        adapter.dismiss("finding")
        assertTrue(adapter.report.value?.findings?.isEmpty() == true)
    }

    @Test fun mapsHeadlineKeyFindingsTrendsJournalEffectsAndLearningCount() = runTest {
        val key = insightFinding()
        val up = key.copy(key = "trend.up", type = FindingType.TREND, severity = Severity.INFO, direction = Direction.UP)
        val down = up.copy(key = "trend.down", direction = Direction.DOWN)
        val info = key.copy(key = "info", severity = Severity.INFO)
        val effect = info.copy(key = "ACTION_EFFECT:example.app:POWER_MAH_PER_H:7", type = FindingType.ACTION_EFFECT)
        source.report.value = InsightReport(100, listOf(key, up, down, info, effect), key)
        source.actions.value = InsightActionStatus.entries.mapIndexed { index, status -> insightAction((index + 7).toLong(), status) }
        source.lastAnalyzedAt.value = 100
        source.eligibleSessionCount.value = 3
        val vm = start()
        assertEquals(key.key, vm.state.value.headline?.key)
        assertEquals(listOf(key.key), vm.state.value.keyFindings.map { it.key })
        assertEquals(listOf(Direction.UP, Direction.DOWN), vm.state.value.changes.map { it.direction })
        assertEquals(listOf(InsightActionStatus.APPLIED, InsightActionStatus.UNKNOWN), vm.state.value.appliedActions.filter { it.undoable }.map { it.status })
        assertEquals(effect.key, vm.state.value.appliedActions.first().effect?.key)
        assertEquals(100L, vm.state.value.lastAnalyzedAt)
        assertFalse(vm.state.value.empty)
        assertTrue(vm.state.value.lowData)
        source.eligibleSessionCount.value = 4
        source.report.value = InsightReport(101, emptyList(), null)
        runCurrent()
        assertFalse(vm.state.value.lowData)
        assertTrue(vm.state.value.empty)
    }

    @Test fun analyzeShowsBusyAndClearsItAfterCompletionAndFailure() = runTest {
        val vm = start()
        source.analyzeGate = CompletableDeferred()
        vm.onEvent(InsightsEvent.AnalyzeNow)
        vm.onEvent(InsightsEvent.AnalyzeNow)
        runCurrent()
        assertTrue(vm.state.value.analyzing)
        assertEquals(1, source.analyzeCalls)
        source.analyzeGate?.complete(Unit)
        runCurrent()
        assertFalse(vm.state.value.analyzing)
        source.analyzeFailure = true
        vm.onEvent(InsightsEvent.AnalyzeNow)
        runCurrent()
        assertFalse(vm.state.value.analyzing)
        assertEquals(InsightMessageCode.ANALYSIS_FAILED, vm.state.value.error)
    }

    @Test fun requestApplyRequiresExplicitConfirmAndCannotReplay() = runTest {
        val vm = start()
        vm.onEvent(InsightsEvent.RequestApply("finding", ActionType.RESTRICT_BACKGROUND))
        runCurrent()
        assertEquals("RequestApply must never call apply", 0, source.applied.size)
        assertEquals(PendingInsightApply("finding", ActionType.RESTRICT_BACKGROUND), vm.state.value.apply.pending)
        vm.onEvent(InsightsEvent.ConfirmApply)
        vm.onEvent(InsightsEvent.ConfirmApply)
        runCurrent()
        assertEquals(1, source.applied.size)
        assertNull(vm.state.value.apply.pending)
        assertEquals(InsightMessageCode.APPLIED, vm.state.value.apply.lastResult?.code)
    }

    @Test fun processDeathRestoresDialogAndSelectionWithoutApplying() = runTest {
        val saved = SavedStateHandle()
        val original = start(saved)
        original.onEvent(InsightsEvent.RequestApply("finding", ActionType.RESTRICT_BACKGROUND))
        runCurrent()
        val restored = start(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertEquals(original.state.value.apply.pending, restored.state.value.apply.pending)
        assertEquals("finding", restored.state.value.apply.selectedKey)
        assertEquals(0, source.applied.size)
        restored.onEvent(InsightsEvent.CancelApply)
        restored.onEvent(InsightsEvent.ConfirmApply)
        runCurrent()
        assertEquals(0, source.applied.size)
        assertNull(restored.state.value.apply.pending)
    }

    @Test fun dismissalFeedbackAndNavigationUseTheirOwnPaths() = runTest {
        val vm = start()
        val effects = mutableListOf<InsightUiEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        vm.onEvent(InsightsEvent.Dismiss("one"))
        vm.onEvent(InsightsEvent.NotAProblem("two"))
        vm.onEvent(InsightsEvent.OpenFinding("three"))
        runCurrent()
        assertEquals(listOf("one"), source.dismissed)
        assertEquals(listOf("two"), source.feedback)
        assertEquals(listOf(InsightUiEffect.OpenFinding("three")), effects)
        assertEquals("three", vm.state.value.apply.selectedKey)
        assertTrue(source.applied.isEmpty())
    }

    @Test fun everyActionResultAndRefusalGetsItsFixedCodeOrSettingsEvent() = runTest {
        val vm = start()
        val effects = mutableListOf<InsightUiEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        val cases = listOf(
            ActionResult.Applied(1) to InsightMessageCode.APPLIED,
            ActionResult.AppliedWithFallback(2, FallbackCode.RESTRICTED_TO_RARE) to InsightMessageCode.RESTRICTED_TO_RARE,
            ActionResult.Unknown to InsightMessageCode.UNKNOWN,
            ActionResult.Reverted to InsightMessageCode.REVERTED,
            ActionResult.ChangedExternally("private shell text") to InsightMessageCode.CHANGED_EXTERNALLY,
            ActionResult.OneShot(3) to InsightMessageCode.ONE_SHOT,
        ) + FailureCode.entries.map { ActionResult.Failed(it) to InsightMessageCode.valueOf(it.name) } +
            RefusalCode.entries.map { ActionResult.Refused(it) to InsightMessageCode.valueOf(it.name) }
        for ((result, expected) in cases) {
            source.result = result
            vm.onEvent(InsightsEvent.Undo(42))
            runCurrent()
            assertEquals(result.toString(), expected, vm.state.value.apply.lastResult?.code)
            assertEquals(expected, (effects.last() as InsightUiEffect.Message).result.code)
        }
        val spec = IntentSpec("android.settings.APPLICATION_DETAILS_SETTINGS", "example.app")
        source.result = ActionResult.OpenSettings(spec)
        vm.onEvent(InsightsEvent.RequestApply("finding", ActionType.OPEN_APP_SETTINGS))
        vm.onEvent(InsightsEvent.ConfirmApply)
        runCurrent()
        assertEquals(InsightUiEffect.OpenSettings(spec), effects.last())
        assertNull(vm.state.value.apply.lastResult)
        assertEquals(cases.size, source.undone.size)
        assertTrue(source.undone.all { it == 42L })
    }

    @Test fun clearingScreenDoesNotCancelConfirmedApplyOrUndo() = runTest {
        for (undo in listOf(false, true)) {
            val vm = start()
            val store = ViewModelStore().apply { put("vm", vm) }
            source.actionGate = CompletableDeferred()
            if (undo) vm.onEvent(InsightsEvent.Undo(9)) else {
                vm.onEvent(InsightsEvent.RequestApply("finding", ActionType.RESTRICT_BACKGROUND))
                vm.onEvent(InsightsEvent.ConfirmApply)
            }
            runCurrent()
            assertTrue(vm.state.value.apply.working)
            store.clear()
            source.actionGate?.complete(Unit)
            runCurrent()
            assertEquals(if (undo) 2 else 1, source.completedActions)
        }
    }

    @Test fun disappearingFindingClearsSavedPendingAndDoesNotResurface() = runTest {
        val saved = SavedStateHandle()
        val vm = start(saved)
        vm.onEvent(InsightsEvent.RequestApply("finding", ActionType.RESTRICT_BACKGROUND))
        source.report.value = null
        runCurrent()
        assertNotNull("an unloaded report must not invalidate a restored dialog", vm.state.value.apply.pending)
        source.report.value = InsightReport(2, emptyList(), null)
        runCurrent()
        assertNull("missing finding must clear pending", vm.state.value.apply.pending)
        assertNull(saved.get<String>("insights.pending.key"))
        assertNull(saved.get<String>("insights.pending.action"))
        source.report.value = InsightReport(3, listOf(insightFinding()), insightFinding())
        runCurrent()
        assertNull("returning finding must not resurrect pending", vm.state.value.apply.pending)
        assertNull(start(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })).state.value.apply.pending)
        assertTrue(source.applied.isEmpty())
    }

    @Test fun pendingIsInvalidatedWithoutAScreenCollector() = runTest {
        val saved = SavedStateHandle()
        val vm = InsightsViewModel(source, backgroundScope, saved)
        vm.onEvent(InsightsEvent.RequestApply("finding", ActionType.RESTRICT_BACKGROUND))
        runCurrent()
        assertNotNull(saved.get<String>("insights.pending.key"))
        source.report.value = InsightReport(2, emptyList(), null)
        runCurrent()
        assertNull("saved pending must clear without a UI subscriber", saved.get<String>("insights.pending.key"))
        source.report.value = InsightReport(3, listOf(insightFinding()), null)
        assertNull(start(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })).state.value.apply.pending)
    }

    @Test fun unavailableRecommendationClearsPending() = runTest {
        val vm = start()
        for (reason in listOf("removed", "privilege", "applied")) {
            source.report.value = InsightReport(1, listOf(insightFinding()), insightFinding())
            source.privileged.value = true
            source.actions.value = emptyList()
            runCurrent()
            vm.onEvent(InsightsEvent.RequestApply("finding", ActionType.RESTRICT_BACKGROUND))
            runCurrent()
            assertNotNull(vm.state.value.apply.pending)
            when (reason) {
                "removed" -> source.report.value = InsightReport(2, listOf(insightFinding().copy(recommendations = emptyList())), null)
                "privilege" -> source.privileged.value = false
                "applied" -> source.actions.value = listOf(insightAction())
            }
            runCurrent()
            assertNull("$reason recommendation must clear pending", vm.state.value.apply.pending)
        }
        assertTrue(source.applied.isEmpty())
    }

    @Test fun latestUnconsumedResultRestoresAndConsumptionIsSaved() = runTest {
        val saved = SavedStateHandle()
        val vm = start(saved)
        vm.onEvent(InsightsEvent.Undo(7))
        runCurrent()
        source.result = ActionResult.OneShot(19)
        vm.onEvent(InsightsEvent.Undo(8))
        runCurrent()
        val expected = InsightActionMessage(InsightMessageCode.ONE_SHOT, 19)
        assertEquals(expected, vm.state.value.apply.lastResult)
        val restoredSaved = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        val restored = start(restoredSaved)
        assertEquals("latest unconsumed result must survive recreation", expected, restored.state.value.apply.lastResult)
        restored.onEvent(InsightsEvent.ResultShown)
        runCurrent()
        assertNull(restored.state.value.apply.lastResult)
        assertNull(start(SavedStateHandle(restoredSaved.keys().associateWith { restoredSaved.get<Any?>(it) })).state.value.apply.lastResult)
        assertEquals("restoring or consuming must not replay actions", listOf(7L, 8L), source.undone)
    }

    @Test fun loadedWaitsForFirstContentAndStatusEmissionEvenWithNullReport() = runTest {
        source.report.value = null
        val actions = kotlinx.coroutines.flow.MutableSharedFlow<List<InsightActionEntity>>(replay = 1)
        val count = kotlinx.coroutines.flow.MutableSharedFlow<Int>(replay = 1)
        val delayed = object : InsightsRepository by source {
            override val actions = actions
            override val eligibleSessionCount = count
        }
        val vm = InsightsViewModel(delayed, backgroundScope)
        assertFalse(vm.state.value.loaded)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertFalse(vm.state.value.loaded)
        actions.emit(emptyList())
        runCurrent()
        assertFalse("status has not emitted yet", vm.state.value.loaded)
        count.emit(0)
        runCurrent()
        assertTrue("first null report and status are loaded, not loading", vm.state.value.loaded)
        assertTrue(vm.state.value.lowData)
        assertTrue(vm.state.value.empty)
    }

    @Test fun confirmingStaleOrUnsupportedRecommendationNeverApplies() = runTest {
        val vm = start()
        vm.onEvent(InsightsEvent.RequestApply("finding", ActionType.RESTRICT_BACKGROUND))
        source.report.value = InsightReport(2, emptyList(), null)
        vm.onEvent(InsightsEvent.ConfirmApply)
        runCurrent()
        assertEquals(InsightMessageCode.FINDING_UNAVAILABLE, vm.state.value.apply.lastResult?.code)
        source.report.value = InsightReport(3, listOf(insightFinding()), null)
        vm.onEvent(InsightsEvent.RequestApply("finding", ActionType.FORCE_STOP))
        vm.onEvent(InsightsEvent.ConfirmApply)
        runCurrent()
        assertEquals(InsightMessageCode.RECOMMENDATION_UNAVAILABLE, vm.state.value.apply.lastResult?.code)
        assertTrue(source.applied.isEmpty())
    }
}

internal class FakeInsightsRepository : InsightsRepository {
    override val report = MutableStateFlow<InsightReport?>(InsightReport(1, listOf(insightFinding()), insightFinding()))
    override val actions = MutableStateFlow<List<InsightActionEntity>>(emptyList())
    override val privileged = MutableStateFlow(true)
    override val lastAnalyzedAt = MutableStateFlow<Long?>(null)
    override val eligibleSessionCount = MutableStateFlow(0)
    val applied = mutableListOf<Pair<Finding, Recommendation>>()
    val undone = mutableListOf<Long>()
    val dismissed = mutableListOf<String>()
    val feedback = mutableListOf<String>()
    var result: ActionResult = ActionResult.Applied(7)
    var analyzeCalls = 0
    var analyzeGate: CompletableDeferred<Unit>? = null
    var analyzeFailure = false
    var actionGate: CompletableDeferred<Unit>? = null
    var completedActions = 0
    override suspend fun analyzeNow() {
        analyzeCalls++
        analyzeGate?.await()
        if (analyzeFailure) error("analysis test failure")
    }
    override suspend fun dismiss(key: String) { dismissed += key }
    override suspend fun notAProblem(key: String) { feedback += key }
    override suspend fun apply(finding: Finding, recommendation: Recommendation): ActionResult {
        applied += finding to recommendation
        actionGate?.await()
        completedActions++
        return result
    }
    override suspend fun undo(actionId: Long): ActionResult {
        undone += actionId
        actionGate?.await()
        completedActions++
        return result
    }
}

internal fun insightFinding() = Finding(
    "finding", FindingType.APP_DRAIN_ANOMALY, Severity.HIGH, Confidence.HIGH, 1.0,
    Subject.App(10042, "example.app"), Direction.UP,
    listOf(Evidence(Metric.POWER_MAH_PER_H, 10.0, 2.0, MetricUnit.MAH_PER_H, 4)),
    listOf(SeriesPoint(10, 10.0, 1.0, 3.0)),
    listOf(Recommendation(ActionType.RESTRICT_BACKGROUND, true, true), Recommendation(ActionType.OPEN_APP_SETTINGS, false, false)),
)

internal fun insightAction(id: Long = 7, status: InsightActionStatus = InsightActionStatus.APPLIED) = InsightActionEntity(
    id = id, findingKey = "finding", type = ActionType.RESTRICT_BACKGROUND.name,
    packageName = "example.app", uid = 10042, userId = 0, status = status,
    priorStateVersion = 1, createdAt = 1, appliedAt = 2,
)
