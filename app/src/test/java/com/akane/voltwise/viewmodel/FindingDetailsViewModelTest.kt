package com.akane.voltwise.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.akane.voltwise.battery.data.db.InsightActionStatus
import com.akane.voltwise.battery.insights.actions.ActionResult
import com.akane.voltwise.battery.insights.actions.RefusalCode
import com.akane.voltwise.battery.insights.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FindingDetailsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val source = FakeInsightsRepository()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()

    private fun TestScope.start(saved: SavedStateHandle = SavedStateHandle(mapOf("key" to "finding"))): FindingDetailsViewModel {
        val vm = FindingDetailsViewModel(source, backgroundScope, saved)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        return vm
    }

    @Test fun mapsEvidenceSeriesBaselineAndPackageRelatedActions() = runTest {
        val finding = insightFinding()
        source.actions.value = listOf(
            insightAction(), insightAction(8, InsightActionStatus.UNKNOWN).copy(findingKey = "another.finding"),
            insightAction(9).copy(packageName = "other.app"),
            insightAction(10, InsightActionStatus.ONE_SHOT).copy(type = ActionType.FORCE_STOP.name),
        )
        val vm = start()
        val state = vm.state.value
        assertEquals(finding.evidence, state.finding?.evidence)
        assertEquals(finding.series, state.finding?.series)
        assertEquals(1.0, state.finding?.series?.single()?.baselineLow)
        assertEquals(3.0, state.finding?.series?.single()?.baselineHigh)
        assertEquals(listOf(7L, 8L, 10L), state.relatedActions.map { it.id })
        assertEquals(listOf(true, true, false), state.relatedActions.map { it.undoable })
        val restriction = state.finding?.recommendations?.first()
        assertEquals(true, restriction?.alreadyApplied)
        assertEquals(true, restriction?.reversible)
        assertEquals(false, restriction?.available)
    }

    @Test fun notPrivilegedDisablesPrivilegedApplyAndKeepsManualPath() = runTest {
        source.privileged.value = false
        val vm = start()
        assertFalse(vm.state.value.privileged)
        assertEquals(listOf(false, true), vm.state.value.finding?.recommendations?.map { it.available })
        source.privileged.value = true
        runCurrent()
        assertEquals(listOf(true, true), vm.state.value.finding?.recommendations?.map { it.available })
source.actions.value = listOf(insightAction().copy(findingKey = "another.finding"))
        runCurrent()
        assertEquals(listOf(false, true), vm.state.value.finding?.recommendations?.map { it.available })
        assertEquals(true, vm.state.value.finding?.recommendations?.first()?.alreadyApplied)
    }

    @Test fun requestAndRestoredDialogNeverApplyWithoutConfirm() = runTest {
        val saved = SavedStateHandle(mapOf("key" to "finding"))
        val original = start(saved)
        original.onEvent(InsightsEvent.RequestApply("finding", ActionType.RESTRICT_BACKGROUND))
        runCurrent()
        assertEquals("details RequestApply must never call apply", 0, source.applied.size)
        val restored = start(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertEquals(original.state.value.apply.pending, restored.state.value.apply.pending)
        assertNotNull(restored.state.value.finding)
        assertTrue(source.applied.isEmpty())
        restored.onEvent(InsightsEvent.ConfirmApply)
        runCurrent()
        assertEquals(1, source.applied.size)
        assertEquals(ActionType.RESTRICT_BACKGROUND, source.applied.single().second.action)
        assertNull(restored.state.value.apply.pending)
    }

    @Test fun undoUsesSharedRefusalMappingAndChangedOutsideResult() = runTest {
        val vm = start()
        val effects = mutableListOf<InsightUiEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        source.result = ActionResult.Refused(RefusalCode.ALREADY_AT_TARGET)
        vm.onEvent(InsightsEvent.Undo(7))
        runCurrent()
        assertEquals(InsightMessageCode.ALREADY_AT_TARGET, vm.state.value.apply.lastResult?.code)
        source.result = ActionResult.ChangedExternally("not user-facing")
        vm.onEvent(InsightsEvent.Undo(8))
        runCurrent()
        assertEquals(listOf(7L, 8L), source.undone)
        assertEquals(InsightMessageCode.CHANGED_EXTERNALLY, (effects.last() as InsightUiEffect.Message).result.code)
    }

    @Test fun missingFindingHasNoInventedEvidenceAndNoApply() = runTest {
        val vm = start(SavedStateHandle(mapOf("key" to "missing")))
        assertNull(vm.state.value.finding)
        vm.onEvent(InsightsEvent.RequestApply("missing", ActionType.RESTRICT_BACKGROUND))
        vm.onEvent(InsightsEvent.ConfirmApply)
        runCurrent()
        assertTrue(source.applied.isEmpty())
        assertEquals(InsightMessageCode.FINDING_UNAVAILABLE, vm.state.value.apply.lastResult?.code)
    }

    @Test fun findingSnapshotsCannotBeMutatedThroughSourceOrUiLists() = runTest {
        val evidence = insightFinding().evidence.toMutableList()
        val series = insightFinding().series.toMutableList()
        source.report.value = InsightReport(1, listOf(insightFinding().copy(evidence = evidence, series = series)), null)
        val vm = start()
        val snapshot = vm.state.value.finding ?: error("missing finding")
        evidence.clear()
        series.clear()
        assertEquals(1, snapshot.evidence.size)
        assertEquals(1, snapshot.series.size)
        try {
            (snapshot.evidence as MutableList<Evidence>).clear()
            fail("evidence must reject mutation")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test fun deviceActionsAreRelatedByFindingNotNullPackage() = runTest {
        val device = insightFinding().copy(subject = Subject.Device)
        source.report.value = InsightReport(1, listOf(device), device)
        source.actions.value = listOf(insightAction().copy(packageName = null), insightAction(8).copy(packageName = null, findingKey = "other"))
        val vm = start()
        assertEquals(listOf(7L), vm.state.value.relatedActions.map { it.id })
    }
}
