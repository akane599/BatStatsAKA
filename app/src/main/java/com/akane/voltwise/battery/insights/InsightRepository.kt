package com.akane.voltwise.battery.insights

import com.akane.voltwise.battery.data.HistoryMaintenance
import com.akane.voltwise.battery.data.db.*
import com.akane.voltwise.battery.data.resolveFullUah
import com.akane.voltwise.battery.data.storedFullUah
import com.akane.voltwise.battery.data.sampling.KeyValueStore
import com.akane.voltwise.battery.insights.engine.InsightEngine
import com.akane.voltwise.battery.insights.engine.findingOrder
import com.akane.voltwise.battery.insights.model.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant

/** One writer for analysis and feedback; never writes app snapshots or an open session's baseline. */
class InsightRepository(
    private val sessionDao: SessionDao,
    private val dailyDao: DailySummaryDao,
    private val appUsageDao: AppUsageDao,
    private val insightDao: InsightDao,
    scope: CoroutineScope,
    private val clock: Clock,
    private val dozeWhitelist: suspend () -> Set<String>?,
    private val privileged: () -> Boolean,
    private val liveDump: suspend () -> Unit,
    private val store: KeyValueStore,
    private val maintenance: HistoryMaintenance,
    /** Latest charge counter and level, when available; FullCapacity also uses stored estimates. */
    private val capacityReading: () -> Pair<Long?, Int?> = { null to null },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val analyzeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val analyze: (InsightInputs) -> InsightReport = InsightEngine::analyze,
) {
    private val mutex = Mutex()
    private val mutableReport = MutableStateFlow<InsightReport?>(null)
    val report: StateFlow<InsightReport?> = mutableReport.asStateFlow()
    private val mutableLastAnalyzedAt = MutableStateFlow(store.getString(LAST_ANALYZED_AT)?.toLongOrNull())
    val lastAnalyzedAt: StateFlow<Long?> = mutableLastAnalyzedAt.asStateFlow()

    init {
        scope.launch(ioDispatcher) {
            insightDao.findings().collect {
                // Re-read under the same lock: a buffered emission must not overwrite a newer refresh/dismiss.
                mutex.withLock { publish(insightDao.findingsOnce()) }
            }
        }
    }

    suspend fun refresh(liveDump: Boolean = false) = mutex.withLock {
        val generation = maintenance.generation
        if (maintenance.isClearing) return@withLock
        val inputs = withContext(ioDispatcher) {
            if (liveDump) this@InsightRepository.liveDump()
            val now = clock.millis()
            val today = Instant.ofEpochMilli(now).atZone(clock.zone).toLocalDate().toEpochDay()
            val sessions = sessionDao.closedSessionsBetween(now - InsightInputsBuilder.HISTORY_MS, now)
                .filter { it.endTime != null }
            val ids = sessions.map { it.sessionId }
            val rows = ids.chunked(QUERY_CHUNK).flatMap { appUsageDao.usageRowsForSessions(it) }
            val wakers = ids.chunked(QUERY_CHUNK).flatMap { appUsageDao.sessionWakers(it) }
            val (counter, level) = capacityReading()
            InsightInputsBuilder.build(
                now, today, resolveFullUah(counter, level, storedFullUah(sessions)), privileged(), sessions,
                dailyDao.range(today - InsightInputsBuilder.HISTORY_DAYS, today), rows, wakers,
                sessionDao.capacityEstimates(Int.MAX_VALUE).first(), dozeWhitelist(),
                insightDao.actionsOnce(), insightDao.findingsOnce(),
            )
        }
        val analyzed = withContext(analyzeDispatcher) { analyze(inputs) }
        withContext(ioDispatcher) {
            maintenance.mutations.withLock write@ {
                if (maintenance.isClearing || maintenance.generation != generation) return@write
                val existing = insightDao.findingsOnce().associateBy { it.key }
                val produced = analyzed.findings.map { it.key }.toSet()
                val updates = analyzed.findings.map { finding ->
                    val old = existing[finding.key]
                    val oldSeverity = enumName<Severity>(old?.severity)
                    val status = if (old?.status == InsightFindingStatus.DISMISSED &&
                        (oldSeverity == null || finding.severity.ordinal <= oldSeverity.ordinal)) {
                        InsightFindingStatus.DISMISSED
                    } else InsightFindingStatus.ACTIVE
                    val persisted = if (status == InsightFindingStatus.DISMISSED && oldSeverity != null) {
                        finding.copy(severity = maxOf(oldSeverity, finding.severity))
                    } else finding
                    FindingCodec.encode(persisted, old?.firstSeenAt ?: inputs.nowMs, inputs.nowMs, status,
                        old?.feedbackMultiplier ?: 1.0)
                } + existing.values.filter { it.status == InsightFindingStatus.ACTIVE && it.key !in produced }
                    .map { it.copy(status = InsightFindingStatus.RESOLVED) }
                insightDao.upsertFindings(updates)
                store.edit(mapOf(LAST_ANALYZED_AT to inputs.nowMs.toString()))
                mutableLastAnalyzedAt.value = inputs.nowMs
                publish(insightDao.findingsOnce())
            }
        }
    }

    suspend fun dismiss(key: String) = mutex.withLock {
        withContext(ioDispatcher) {
            insightDao.setStatus(key, InsightFindingStatus.DISMISSED)
            publish(insightDao.findingsOnce())
        }
    }

    suspend fun notAProblem(key: String) = mutex.withLock {
        withContext(ioDispatcher) {
            val old = insightDao.findingsOnce().firstOrNull { it.key == key } ?: return@withContext
            insightDao.upsertFindings(listOf(old.copy(status = InsightFindingStatus.DISMISSED,
                feedbackMultiplier = (old.feedbackMultiplier * 1.5).coerceAtMost(4.0))))
            publish(insightDao.findingsOnce())
        }
    }

    private fun publish(rows: List<InsightFindingEntity>) {
        val active = rows.filter { it.status == InsightFindingStatus.ACTIVE }.mapNotNull(FindingCodec::decode)
            .sortedWith(findingOrder)
        mutableReport.value = InsightReport(mutableLastAnalyzedAt.value ?: rows.maxOfOrNull { it.lastSeenAt } ?: 0,
            active, active.firstOrNull { it.severity != Severity.INFO })
    }

    companion object {
        const val LAST_ANALYZED_AT = "insights.lastAnalyzedAt"
        private const val QUERY_CHUNK = 900
    }
}
