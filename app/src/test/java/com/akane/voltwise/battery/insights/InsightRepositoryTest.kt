package com.akane.voltwise.battery.insights

import com.akane.voltwise.battery.data.db.*
import com.akane.voltwise.battery.data.sampling.FakeKeyValueStore
import com.akane.voltwise.battery.insights.model.*
import kotlinx.coroutines.CompletableDeferred
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
        override fun findings() = rows
        override suspend fun findingsOnce() = rows.value
        override suspend fun actionsOnce() = actionRows
        override suspend fun upsertFindings(list: List<InsightFindingEntity>) {
            val merged = rows.value.associateBy { it.key }.toMutableMap()
            list.forEach { merged[it.key] = it }
            rows.value = merged.values.toList()
        }
        override suspend fun setStatus(key: String, status: InsightFindingStatus) {
            rows.value = rows.value.map { if (it.key == key) it.copy(status = status) else it }
        }
        fun row(key: String = testFinding().key) = rows.value.single { it.key == key }
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
        var whitelist = setOf("old.whitelist")
        val store = FakeKeyValueStore()
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
            capacityReading = { 2_000_000L to 50 },
            ioDispatcher = StandardTestDispatcher(scope.testScheduler),
            analyzeDispatcher = StandardTestDispatcher(scope.testScheduler),
            analyze = { seen += it; events += "analyze"; InsightReport(it.nowMs, output, output.firstOrNull()) })
    }

    @Test fun startupLoadsOnlyActiveSupportedFindingsAndPersistedAnalysisTime() = runTest {
        val fixture = Fixture()
        fixture.store.edit(mapOf(InsightRepository.LAST_ANALYZED_AT to "123"))
        fixture.insights.rows.value = listOf(FindingCodec.encode(testFinding(), 1),
            FindingCodec.encode(testFinding("dismissed"), 1, status = InsightFindingStatus.DISMISSED),
            FindingCodec.encode(testFinding("resolved"), 1, status = InsightFindingStatus.RESOLVED),
            FindingCodec.encode(testFinding("bad"), 1).copy(evidenceJson = "bad"))
        val repo = fixture.repository(this)
        assertEquals(123L, repo.lastAnalyzedAt.value)
        assertNull(repo.report.value)
        runCurrent()
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
        assertEquals(NOW, fixture.repository(this).lastAnalyzedAt.value)
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
