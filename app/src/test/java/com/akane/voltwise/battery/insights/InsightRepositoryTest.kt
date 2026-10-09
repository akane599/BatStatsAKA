package com.akane.voltwise.battery.insights

import com.akane.voltwise.battery.reconcileAndCatchUpInsights
import com.akane.voltwise.battery.data.HistoryMaintenance
import com.akane.voltwise.battery.data.db.*
import com.akane.voltwise.battery.data.sampling.FakeKeyValueStore
import com.akane.voltwise.battery.data.sampling.KeyValueStore
import com.akane.voltwise.battery.insights.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class InsightRepositoryTest {
    private class MemoryInsights(initial: List<InsightFindingEntity> = emptyList()) : UnusedInsightDao() {
        val rows = MutableStateFlow(initial)
        var actionRows = emptyList<InsightActionEntity>()
        var afterFindingsRead: suspend () -> Unit = {}
        var feedbackWrites = 0
        override fun findings() = rows
        override suspend fun findingsOnce(): List<InsightFindingEntity> {
            val snapshot = rows.value
            val callback = afterFindingsRead
            afterFindingsRead = {}
            callback()
            return snapshot
        }
        override suspend fun actionsOnce() = actionRows
        override suspend fun upsertFindings(list: List<InsightFindingEntity>) {
            feedbackWrites++
            val merged = rows.value.associateBy { it.key }.toMutableMap()
            list.forEach { merged[it.key] = it }
            rows.value = merged.values.toList()
        }
        override suspend fun setStatus(key: String, status: InsightFindingStatus) {
            feedbackWrites++
            rows.value = rows.value.map { if (it.key == key) it.copy(status = status) else it }
        }
        override suspend fun clearFindings() { rows.value = emptyList() }
        fun row(key: String = testFinding().key) = rows.value.single { it.key == key }
    }

    private class CountingStore : KeyValueStore {
        private val delegate = FakeKeyValueStore()
        var reads = 0
        override fun getString(key: String): String? {
            reads++
            return delegate.getString(key)
        }
        override fun edit(values: Map<String, String?>) = delegate.edit(values)
    }

    private class Fixture {
        var now = NOW
        var sessions = listOf(testSession())
        var output = listOf(testFinding())
        val seen = mutableListOf<InsightInputs>()
        val events = mutableListOf<String>()
        val rowChunks = mutableListOf<List<String>>()
        val wakerChunks = mutableListOf<List<String>>()
        var window: Pair<Long, Long>? = null
        var dayWindow: Pair<Long, Long>? = null
        var dump: suspend () -> Unit = {}
        var duringAnalysis: () -> Unit = {}
        val maintenance = HistoryMaintenance()
        var whitelist = setOf("old.whitelist")
        val store = CountingStore()
        val insights = MemoryInsights()
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = Instant.ofEpochMilli(now)
        }
        val sessionDao = object : UnusedSessionDao() {
            override suspend fun closedSessionsBetween(from: Long, to: Long): List<ChargeSession> {
                events += "sessions"
                window = from to to
                return sessions
            }
            override fun capacityEstimates(limit: Int) = flowOf(emptyList<CapacityEstimateRow>())
        }
        val daily = object : UnusedDailySummaryDao() {
            override suspend fun range(fromDay: Long, toDay: Long): List<DailySummary> {
                dayWindow = fromDay to toDay
                return emptyList()
            }
        }
        val apps = object : UnusedAppUsageDao() {
            override suspend fun usageRowsForSessions(sessionIds: List<String>): List<SessionAppUsage> {
                rowChunks += sessionIds
                return sessionIds.map { testAppRow(it) }
            }
            override suspend fun sessionWakers(sessionIds: List<String>): List<SessionDeviceWaker> {
                wakerChunks += sessionIds
                return emptyList()
            }
        }
        fun repository(scope: TestScope) = InsightRepository(sessionDao, daily, apps, insights, scope.backgroundScope,
            clock, { events += "whitelist"; whitelist }, { true }, { events += "dump"; dump() }, store,
            maintenance = maintenance,
            capacityReading = { 2_000_000L to 50 },
            ioDispatcher = StandardTestDispatcher(scope.testScheduler),
            analyzeDispatcher = StandardTestDispatcher(scope.testScheduler),
            analyze = {
                seen += it
                events += "analyze"
                duringAnalysis()
                InsightReport(it.nowMs, output, output.firstOrNull())
            })
    }

    @Test fun constructorDefersPreferenceReadUntilIoInitialization() = runTest {
        val fixture = Fixture()
        fixture.store.edit(mapOf(InsightRepository.LAST_ANALYZED_AT to "123"))
        val repo = fixture.repository(this)
        assertEquals("Constructing the repository must not read preferences", 0, fixture.store.reads)
        assertNull(repo.lastAnalyzedAt.value)
        runCurrent()
        assertEquals(1, fixture.store.reads)
        assertEquals(123L, repo.lastAnalyzedAt.value)
        assertEquals(123L, repo.report.value!!.generatedAtMs)
    }

    @Test fun notAProblemCannotResurrectFindingClearedAfterRead() = runTest {
        val fixture = Fixture()
        fixture.insights.rows.value = listOf(FindingCodec.encode(testFinding(), 1))
        val repo = fixture.repository(this)
        runCurrent()
        var clear: Deferred<Unit>? = null
        fixture.insights.afterFindingsRead = {
            clear = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.maintenance.clear({}, { fixture.insights.clearFindings() })
            }
        }
        repo.notAProblem(testFinding().key)
        clear!!.await()
        assertTrue("Feedback must not reinsert a cleared finding", fixture.insights.findingsOnce().isEmpty())
        assertEquals("Feedback must skip a clear started during its read", 0, fixture.insights.feedbackWrites)
        runCurrent()
        assertTrue(repo.report.value!!.findings.isEmpty())
    }

    @Test fun feedbackSkipsWhileClearHoldsMutationLock() = runTest {
        val fixture = Fixture()
        fixture.insights.rows.value = listOf(FindingCodec.encode(testFinding(), 1))
        val repo = fixture.repository(this)
        runCurrent()
        val deleteGate = CompletableDeferred<Unit>()
        val clear = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.maintenance.clear({}, {
                deleteGate.await()
                fixture.insights.clearFindings()
            })
        }
        val feedback = async { repo.notAProblem(testFinding().key) }
        val dismiss = async { repo.dismiss(testFinding().key) }
        try {
            runCurrent()
            assertEquals("Neither feedback path may write during a clear", 0, fixture.insights.feedbackWrites)
        } finally {
            deleteGate.complete(Unit)
        }
        clear.await()
        feedback.await()
        dismiss.await()
        assertTrue(fixture.insights.findingsOnce().isEmpty())
        assertEquals(0, fixture.insights.feedbackWrites)
    }

    @Test fun bothFeedbackPathsWaitForHistoryMutations() = runTest {
        for (notAProblem in listOf(false, true)) {
            val fixture = Fixture()
            fixture.insights.rows.value = listOf(FindingCodec.encode(testFinding(), 1))
            val repo = fixture.repository(this)
            runCurrent()
            fixture.maintenance.mutations.lock()
            val feedback = async {
                if (notAProblem) repo.notAProblem(testFinding().key) else repo.dismiss(testFinding().key)
            }
            try {
                runCurrent()
                assertFalse("Feedback must acquire the history mutation lock", feedback.isCompleted)
                assertEquals(0, fixture.insights.feedbackWrites)
            } finally {
                fixture.maintenance.mutations.unlock()
            }
            feedback.await()
            assertEquals(InsightFindingStatus.DISMISSED, fixture.insights.row().status)
            assertEquals(if (notAProblem) 1.5 else 1.0, fixture.insights.row().feedbackMultiplier, 0.0)
        }
    }

    @Test fun catchUpWaitsForStoredTimestampRatherThanInitialNull() = runTest {
        val fixture = Fixture()
        fixture.store.edit(mapOf(InsightRepository.LAST_ANALYZED_AT to NOW.toString()))
        val repo = fixture.repository(this)
        var refreshes = 0
        var reconciled = false
        val catchUp = async(start = CoroutineStart.UNDISPATCHED) {
            reconcileAndCatchUpInsights(
                reconcile = { reconciled = true },
                lastAnalyzedAt = { repo.awaitLastAnalyzedAt() },
                refresh = { refreshes++ },
                clock = { NOW + 1_000 },
            )
        }
        assertTrue(reconciled)
        assertFalse("Catch-up must wait for IO initialization", catchUp.isCompleted)
        assertEquals(0, fixture.store.reads)
        runCurrent()
        catchUp.await()
        assertEquals(NOW, repo.lastAnalyzedAt.value)
        assertEquals("A recent stored analysis must not trigger catch-up", 0, refreshes)
        repo.refresh()
        assertEquals("Awaited accessor must return the latest value", repo.lastAnalyzedAt.value, repo.awaitLastAnalyzedAt())
    }

    @Test fun catchUpWithNoStoredTimestampCompletesLoadingAndRefreshes() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository(this)
        var refreshes = 0
        reconcileAndCatchUpInsights(
            reconcile = {},
            lastAnalyzedAt = { repo.awaitLastAnalyzedAt() },
            refresh = { refreshes++ },
            clock = { NOW },
        )
        assertEquals(1, fixture.store.reads)
        assertNull(repo.lastAnalyzedAt.value)
        assertEquals(1, refreshes)
    }

    @Test fun startupLoadsOnlyActiveSupportedFindingsAndPersistedAnalysisTime() = runTest {
        val fixture = Fixture()
        fixture.store.edit(mapOf(InsightRepository.LAST_ANALYZED_AT to "123"))
        fixture.insights.rows.value = listOf(FindingCodec.encode(testFinding(), 1),
            FindingCodec.encode(testFinding("dismissed"), 1, status = InsightFindingStatus.DISMISSED),
            FindingCodec.encode(testFinding("resolved"), 1, status = InsightFindingStatus.RESOLVED),
            FindingCodec.encode(testFinding("bad"), 1).copy(evidenceJson = "bad"))
        val repo = fixture.repository(this)
        assertNull(repo.lastAnalyzedAt.value)
        assertNull(repo.report.value)
        runCurrent()
        assertEquals(123L, repo.lastAnalyzedAt.value)
        assertEquals(listOf(testFinding()), repo.report.value!!.findings)
        assertEquals(123L, repo.report.value!!.generatedAtMs)
        fixture.insights.rows.value = emptyList() // Clear history propagates without running analysis.
        runCurrent()
        assertTrue(repo.report.value!!.findings.isEmpty())
    }

    @Test fun mergeUpdatesActiveKeepsDismissedUnlessSeverityRisesAndResolvesMissing() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository(this)
        repo.refresh()
        assertEquals(InsightFindingStatus.ACTIVE, fixture.insights.row().status)
        assertEquals(NOW, fixture.insights.row().firstSeenAt)
        fixture.now += 100
        fixture.output = listOf(testFinding().copy(score = 80.0))
        repo.refresh()
        assertEquals(80.0, fixture.insights.row().score, 0.0)
        assertEquals(NOW, fixture.insights.row().firstSeenAt)
        assertEquals(NOW + 100, fixture.insights.row().lastSeenAt)
        repo.dismiss(testFinding().key)
        assertTrue(repo.report.value!!.findings.isEmpty())
        fixture.now += 100
        repo.refresh()
        assertEquals(InsightFindingStatus.DISMISSED, fixture.insights.row().status)
        assertEquals(NOW + 200, fixture.insights.row().lastSeenAt)
        fixture.output = listOf(testFinding(severity = Severity.HIGH))
        repo.refresh()
        assertEquals(InsightFindingStatus.ACTIVE, fixture.insights.row().status)
        fixture.output = emptyList()
        repo.refresh()
        assertEquals(InsightFindingStatus.RESOLVED, fixture.insights.row().status)
        assertTrue(repo.report.value!!.findings.isEmpty())
        fixture.output = listOf(testFinding())
        repo.refresh()
        assertEquals(InsightFindingStatus.ACTIVE, fixture.insights.row().status)
        assertEquals(NOW, fixture.insights.row().firstSeenAt)
    }

    @Test fun dismissedHighFindingStaysDismissedAfterMediumThenHigh() = runTest {
        assertDismissedSeveritySurvivesImprovement(notAProblem = false)
    }

    @Test fun notAProblemHighFindingStaysDismissedAfterMediumThenHigh() = runTest {
        assertDismissedSeveritySurvivesImprovement(notAProblem = true)
    }

    private suspend fun TestScope.assertDismissedSeveritySurvivesImprovement(notAProblem: Boolean) {
        val fixture = Fixture()
        fixture.output = listOf(testFinding(severity = Severity.HIGH))
        val repo = fixture.repository(this)
        repo.refresh()
        if (notAProblem) repo.notAProblem(testFinding().key) else repo.dismiss(testFinding().key)
        fixture.output = listOf(testFinding(severity = Severity.MEDIUM))
        repo.refresh()
        fixture.output = listOf(testFinding(severity = Severity.HIGH))
        repo.refresh()
        assertEquals("HIGH must not reactivate a finding dismissed at HIGH",
            InsightFindingStatus.DISMISSED, fixture.insights.row().status)
        assertEquals(Severity.HIGH.name, fixture.insights.row().severity)
        assertEquals(if (notAProblem) 1.5 else 1.0, fixture.insights.row().feedbackMultiplier, 0.0)
        assertTrue(repo.report.value!!.findings.isEmpty())
    }

    @Test fun completedClearDuringAnalysisDiscardsStaleFindingsAndTimestamp() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository(this)
        var clear: Deferred<Unit>? = null
        fixture.duringAnalysis = {
            clear = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.maintenance.clear({}, {
                    fixture.sessions = emptyList()
                    fixture.insights.rows.value = emptyList()
                })
            }
            assertTrue("Slow analysis must not hold the history mutation lock", clear!!.isCompleted)
            assertFalse(fixture.maintenance.isClearing)
        }
        repo.refresh()
        clear!!.await()
        assertEquals(1, fixture.seen.single().sessions.size)
        assertTrue("Completed clear must not be repopulated by stale analysis", fixture.insights.rows.value.isEmpty())
        assertNull(fixture.store.getString(InsightRepository.LAST_ANALYZED_AT))
        assertNull(repo.lastAnalyzedAt.value)
        runCurrent()
        assertTrue(repo.report.value!!.findings.isEmpty())
        fixture.duringAnalysis = {}
        fixture.output = emptyList()
        repo.refresh()
        assertTrue(fixture.seen.last().sessions.isEmpty())
        assertEquals(NOW, repo.lastAnalyzedAt.value)
    }

    @Test fun clearHoldingMutationLockPreventsRefreshFromWriting() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository(this)
        val deleteGate = CompletableDeferred<Unit>()
        var clear: Deferred<Unit>? = null
        fixture.duringAnalysis = {
            clear = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.maintenance.clear({}, {
                    deleteGate.await()
                    fixture.sessions = emptyList()
                    fixture.insights.rows.value = emptyList()
                })
            }
        }
        val refresh = async { repo.refresh() }
        try {
            runCurrent()
            assertTrue(fixture.maintenance.isClearing)
            assertTrue("No write may race a pending clear", fixture.insights.rows.value.isEmpty())
            assertNull(repo.lastAnalyzedAt.value)
        } finally {
            // Clear is non-cancellable, so release its fake deletion even when a regression fails.
            deleteGate.complete(Unit)
        }
        clear!!.await()
        refresh.await()
        runCurrent()
        assertTrue(fixture.insights.rows.value.isEmpty())
        assertTrue(repo.report.value!!.findings.isEmpty())
        assertNull(repo.lastAnalyzedAt.value)
    }

    @Test fun missingDismissedFindingRemainsDismissedAndFeedbackFlowsIntoNextAnalysis() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository(this)
        repo.refresh()
        repo.notAProblem(testFinding().key)
        assertEquals(1.5, fixture.insights.row().feedbackMultiplier, 0.0)
        fixture.output = emptyList()
        repo.refresh()
        assertEquals(1.5, fixture.seen.last().feedback[testFinding().key]!!, 0.0)
        assertEquals(InsightFindingStatus.DISMISSED, fixture.insights.row().status)
        repeat(5) { repo.notAProblem(testFinding().key) }
        repo.refresh()
        assertEquals(4.0, fixture.seen.last().feedback[testFinding().key]!!, 0.0)
        repo.notAProblem("missing")
        repo.dismiss("missing")
        assertEquals(1, fixture.insights.rows.value.size)
    }

    @Test fun liveDumpRunsBeforeInputReadsAndOnlyRefreshesLiveState() = runTest {
        val fixture = Fixture()
        fixture.dump = { fixture.whitelist = setOf("new.whitelist") }
        val repo = fixture.repository(this)
        repo.refresh(liveDump = true)
        assertEquals("dump", fixture.events.first())
        assertEquals(setOf("new.whitelist"), fixture.seen.single().dozeUserWhitelist)
        assertEquals(4_000_000L, fixture.seen.single().fullUah)
        assertTrue(fixture.seen.single().privileged)
        assertEquals(NOW - InsightInputsBuilder.HISTORY_MS to NOW, fixture.window)
        assertEquals(10L to 100L, fixture.dayWindow)
        // Every snapshot/baseline write on the fake throws; Analyze now called none.
        repo.refresh()
        assertEquals(1, fixture.events.count { it == "dump" })
    }

    @Test fun refreshesAndFeedbackAreSerializedAcrossSuspendingLiveDump() = runTest {
        val fixture = Fixture()
        val gate = CompletableDeferred<Unit>()
        fixture.dump = { gate.await() }
        val repo = fixture.repository(this)
        val first = async { repo.refresh(true) }
        runCurrent()
        val second = async { repo.refresh() }
        val feedback = async { repo.notAProblem(testFinding().key) }
        runCurrent()
        assertEquals(listOf("dump"), fixture.events)
        assertFalse(second.isCompleted)
        assertFalse(feedback.isCompleted)
        gate.complete(Unit)
        first.await()
        second.await()
        feedback.await()
        assertEquals(2, fixture.seen.size)
        assertEquals(1.5, fixture.insights.row().feedbackMultiplier, 0.0)
    }

    @Test fun successfulRefreshPersistsLastAnalyzedAtAndFailureDoesNotAdvanceIt() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository(this)
        repo.refresh()
        assertEquals(NOW, repo.lastAnalyzedAt.value)
        assertEquals(NOW.toString(), fixture.store.getString(InsightRepository.LAST_ANALYZED_AT))
        assertEquals(NOW, fixture.repository(this).awaitLastAnalyzedAt())
        fixture.now += 500
        fixture.dump = { error("dump failed") }
        try { repo.refresh(true); fail("Expected dump failure") } catch (_: IllegalStateException) { }
        assertEquals(NOW, repo.lastAnalyzedAt.value)
        assertEquals(1, fixture.seen.size)
    }

    @Test fun chunksDaoIdsBelowSqliteLimitAndOmitsOpenSession() = runTest {
        val fixture = Fixture()
        fixture.sessions = (0..1_800).map { testSession("s$it") } + testSession("open").copy(endTime = null)
        fixture.repository(this).refresh()
        assertEquals(listOf(900, 900, 1), fixture.rowChunks.map { it.size })
        assertEquals(fixture.rowChunks, fixture.wakerChunks)
        assertEquals(1_801, fixture.seen.single().sessions.size)
        assertFalse(fixture.rowChunks.flatten().contains("open"))
    }

    @Test fun realEngineDetectsHotChargingAndPersistsItsEvidence() = runTest {
        val fixture = Fixture()
        fixture.sessions = (1..3).map { index -> testSession("charge$index").copy(
            type = SessionType.CHARGE, startTime = NOW - (index + 1) * HOUR,
            endTime = NOW - index * HOUR, peakTemperatureDeciC = 430,
        ) }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = InsightRepository(fixture.sessionDao, fixture.daily, fixture.apps, fixture.insights,
            backgroundScope, fixture.clock, { emptySet() }, { false }, {}, fixture.store,
            maintenance = fixture.maintenance,
            ioDispatcher = dispatcher, analyzeDispatcher = dispatcher)
        repo.refresh()
        assertNotNull(repo.report.value)
        val hot = repo.report.value!!.findings.single { it.type == FindingType.HOT_CHARGING }
        assertEquals(43.0, hot.evidence.single().observed, 0.0)
        assertEquals(3, hot.evidence.single().sessions)
        assertEquals(hot, FindingCodec.decode(fixture.insights.row(hot.key)))
        assertEquals(hot, repo.report.value!!.headline)
        assertEquals(NOW, repo.report.value!!.generatedAtMs)
        assertEquals(NOW, repo.lastAnalyzedAt.value)
    }
}
