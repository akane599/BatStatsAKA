package com.akane.voltwise.viewmodel

import com.akane.voltwise.battery.data.HistoryMaintenance
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.InsightActionEntity
import com.akane.voltwise.battery.data.db.InsightActionStatus
import com.akane.voltwise.battery.data.db.InsightFindingEntity
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.data.sampling.FakeKeyValueStore
import com.akane.voltwise.battery.insights.InsightRepository
import com.akane.voltwise.battery.insights.UnusedAppUsageDao
import com.akane.voltwise.battery.insights.UnusedDailySummaryDao
import com.akane.voltwise.battery.insights.UnusedInsightDao
import com.akane.voltwise.battery.insights.UnusedSessionDao
import com.akane.voltwise.battery.insights.actions.InsightActionRepository
import com.akane.voltwise.battery.insights.actions.TargetInspector
import com.akane.voltwise.battery.insights.model.ActionType
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.Recommendation
import com.akane.voltwise.battery.util.ShellRunner
import java.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultInsightsRepositoryTest {
    @Test fun liveDozeWhitelistFindingAllowsRemovalAgainDespiteUndoableRow() {
        val finding = insightFinding().copy(
            type = FindingType.DOZE_WHITELISTED_DRAINER,
            recommendations = listOf(Recommendation(ActionType.REMOVE_DOZE_WHITELIST, true, true)),
        )
        for (status in listOf(InsightActionStatus.APPLIED, InsightActionStatus.UNKNOWN)) {
            val row = insightAction(status = status).copy(
                type = ActionType.REMOVE_DOZE_WHITELIST.name,
                priorState = "PRESENT",
                targetState = "ABSENT",
            )

            val recommendation = finding.toInsightState(privileged = true, listOf(row)).recommendations.single()
            assertTrue("live whitelist must keep removal available despite $status", recommendation.available)
            assertFalse("live whitelist proves $status removal no longer holds", recommendation.alreadyApplied)

            val withoutPrivilege = finding.toInsightState(privileged = false, listOf(row)).recommendations.single()
            assertFalse("removal still requires privileged access", withoutPrivilege.available)
            assertFalse("lack of privilege must not claim removal is applied", withoutPrivilege.alreadyApplied)
        }
    }

    @Test fun appliedBackgroundRestrictionRemainsAppliedAndUnavailable() {
        val finding = insightFinding().copy(
            recommendations = listOf(Recommendation(ActionType.RESTRICT_BACKGROUND, true, true)),
        )

        val recommendation = finding.toInsightState(privileged = true, listOf(insightAction())).recommendations.single()
        assertFalse("applied background restriction must remain unavailable", recommendation.available)
        assertTrue("background restriction must retain applied state", recommendation.alreadyApplied)
    }

    @Test fun establishedPrivilegeIsImmediateOnEveryCollectionWithoutWaitingForStaleProbe() = runTest {
        for (mode in listOf(ShellRunner.Mode.SHIZUKU, ShellRunner.Mode.ROOT)) {
            val probe = FakeProbe(mode)
            probe.shell.detectMode()
            probe.elapsedMs = 10_001L
            probe.pending = CompletableDeferred()
            val repository = repository(probe.shell)

            repeat(2) {
                assertEquals("established $mode must emit true first, never unknown", true, repository.privileged.first())
            }
            assertEquals("resubscription must not re-probe established access", 1, probe.calls)
            assertFalse("the suspended replacement probe was not needed", probe.started.isCompleted)
        }
    }

    @Test fun coldAccessStaysUnknownUntilProbeCompletes() = runTest {
        val probe = FakeProbe(ShellRunner.Mode.SHIZUKU).apply { pending = CompletableDeferred() }
        val repository = repository(probe.shell)
        val emissions = mutableListOf<Boolean?>()
        val collection = async(start = CoroutineStart.UNDISPATCHED) {
            repository.privileged.take(2).toList(emissions)
        }
        probe.started.await()
        assertEquals("pending detection must remain unknown, not false", listOf<Boolean?>(null), emissions)
        probe.pending?.complete(ShellRunner.Mode.SHIZUKU)
        assertEquals(listOf(null, true), collection.await())
        assertEquals(1, probe.calls)
    }

    @Test fun establishedAdbAccessIsImmediatelyUnprivileged() = runTest {
        val probe = FakeProbe(ShellRunner.Mode.ADB)
        probe.shell.detectMode()
        probe.elapsedMs = 10_001L
        probe.pending = CompletableDeferred()

        assertEquals("ADB is known but cannot execute privileged fixes", false, repository(probe.shell).privileged.first())
        assertEquals(1, probe.calls)
    }

    @Test fun establishedAccessKeepsFollowingRevocationWithoutReturningToUnknown() = runTest {
        val probe = FakeProbe(ShellRunner.Mode.SHIZUKU)
        probe.shell.detectMode()
        val repository = repository(probe.shell)
        val collection = async(start = CoroutineStart.UNDISPATCHED) {
            repository.privileged.take(2).toList()
        }

        probe.mode = ShellRunner.Mode.NONE
        probe.shell.detectMode(forceRefresh = true)
        assertEquals("revocation must publish false, not another loading state", listOf(true, false), collection.await())
    }

    private fun TestScope.repository(shell: ShellRunner): DefaultInsightsRepository {
        val sessions = object : UnusedSessionDao() {
            override fun filteredSessions(type: SessionType?, query: String, limit: Int) = flowOf(emptyList<ChargeSession>())
        }
        val dao = object : UnusedInsightDao() {
            override fun findings() = flowOf(emptyList<InsightFindingEntity>())
            override suspend fun findingsOnce() = emptyList<InsightFindingEntity>()
            override fun actions() = flowOf(emptyList<InsightActionEntity>())
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val insights = InsightRepository(
            sessions, UnusedDailySummaryDao(), UnusedAppUsageDao(), dao,
            backgroundScope, Clock.systemUTC(), { null }, { false }, {}, FakeKeyValueStore(),
            maintenance = HistoryMaintenance(), ioDispatcher = dispatcher, analyzeDispatcher = dispatcher,
        )
        val actions = InsightActionRepository(dao, { error("observation must not execute") }, object : TargetInspector {
            override val sdkInt = 37
            override fun installedUid(pkg: String, userId: Int): Int? = error("unexpected inspection")
            override fun packagesForUid(uid: Int): List<String> = error("unexpected inspection")
            override fun roleHolders(): Set<String> = error("unexpected inspection")
        }, { 0L }, {})
        return DefaultInsightsRepository(insights, actions, shell, sessions)
    }

    private class FakeProbe(var mode: ShellRunner.Mode) {
        var elapsedMs = 0L
        var calls = 0
        var pending: CompletableDeferred<ShellRunner.Mode>? = null
        val started = CompletableDeferred<Unit>()
        val shell = ShellRunner(
            probeMode = {
                calls++
                pending?.let {
                    started.complete(Unit)
                    it.await()
                } ?: mode
            },
            runShizuku = { _, _ -> error("unexpected shell call") },
            shizukuRunning = { false },
            elapsedMs = { elapsedMs },
        )
    }
}
