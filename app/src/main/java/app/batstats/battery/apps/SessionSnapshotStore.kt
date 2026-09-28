package app.batstats.battery.apps

import androidx.room.withTransaction
import app.batstats.battery.data.db.AppSnapshot
import app.batstats.battery.data.db.AppSnapshotKind
import app.batstats.battery.data.db.BatteryDatabase
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.data.db.toRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** The open session as far as per-app snapshots care. */
data class OpenSession(val sessionId: String, val type: SessionType)

/** What [SessionSnapshotCollector] reads and writes: [RoomSessionSnapshotStore] on device, a fake in unit tests. */
interface SessionSnapshotStore {
    /** The open (active) session, re-emitted only when it changes. Reads committed rows. */
    fun openSession(): Flow<OpenSession?>

    suspend fun hasBaseline(sessionId: String): Boolean

    /** The session's BASELINE snapshot, or null when none was captured (or it was pruned). */
    suspend fun baseline(sessionId: String): AppUsageSnapshot?

    /**
     * Stores [snapshot] as the session's one BASELINE, then prunes. Skipped (false) when the session already
     * has one or its row does not exist (orphan pruning would delete it).
     */
    suspend fun saveBaseline(sessionId: String, snapshot: AppUsageSnapshot): Boolean

    /**
     * In one transaction: stores [end] as the closed session's END snapshot, replaces its breakdown with
     * [result]'s rows and marks it READY with [result]'s basis, then prunes. False when the session row is gone.
     */
    suspend fun saveEnd(sessionId: String, end: AppUsageSnapshot, result: AppUsageDeltaResult): Boolean

    /** Status setter for CLOSED sessions only; the open session's status lives on the repository's in-memory row. */
    suspend fun setStatus(sessionId: String, status: AppUsageStatus)

    /** Closed DISCHARGE sessions whose breakdown is still PENDING, newest first (bounded look-back). */
    suspend fun pendingClosedDischarges(): List<String>
}

class RoomSessionSnapshotStore(private val db: BatteryDatabase) : SessionSnapshotStore {
    private val sessions = db.sessionDao()
    private val usage = db.appUsageDao()

    override fun openSession(): Flow<OpenSession?> = sessions.activeFlow()
        .map { active -> active?.let { OpenSession(it.sessionId, it.type) } }
        .distinctUntilChanged()

    override suspend fun hasBaseline(sessionId: String): Boolean =
        usage.latestSnapshot(sessionId, AppSnapshotKind.BASELINE) != null

    override suspend fun baseline(sessionId: String): AppUsageSnapshot? {
        val header = usage.latestSnapshot(sessionId, AppSnapshotKind.BASELINE) ?: return null
        return AppUsageSnapshot(header.windowStartedAt, header.windowStartCount, header.capturedAt,
            usage.snapshotUids(header.id).map { it.toRow() })
    }

    override suspend fun saveBaseline(sessionId: String, snapshot: AppUsageSnapshot): Boolean = db.withTransaction {
        if (sessions.byId(sessionId) == null || usage.latestSnapshot(sessionId, AppSnapshotKind.BASELINE) != null) {
            return@withTransaction false
        }
        insert(sessionId, AppSnapshotKind.BASELINE, snapshot)
        true
    }

    override suspend fun saveEnd(sessionId: String, end: AppUsageSnapshot, result: AppUsageDeltaResult): Boolean =
        db.withTransaction {
            if (sessions.byId(sessionId) == null) return@withTransaction false
            insert(sessionId, AppSnapshotKind.END, end)
            usage.replaceSessionUsage(sessionId, result.basis, result.rows)
            true
        }

    override suspend fun setStatus(sessionId: String, status: AppUsageStatus) {
        usage.setAppUsageStatus(sessionId, status, null)
    }

    override suspend fun pendingClosedDischarges(): List<String> =
        sessions.filteredSessions(SessionType.DISCHARGE, "", PENDING_LOOKBACK).first()
            .filter { it.endTime != null && it.activeKey == null && it.appUsageStatus == AppUsageStatus.PENDING }
            .map { it.sessionId }

    /** insertSnapshot prunes to the open session's baseline + the newest [AppUsageDao.SNAPSHOTS_KEPT]. */
    private suspend fun insert(sessionId: String, kind: AppSnapshotKind, snapshot: AppUsageSnapshot) {
        usage.insertSnapshot(AppSnapshot(sessionId = sessionId, kind = kind, capturedAt = snapshot.capturedAt,
            windowStartedAt = snapshot.windowStartedAt, windowStartCount = snapshot.windowStartCount),
            snapshot.rows.collapseByUid())
        usage.pruneOrphanSnapshots()
    }

    private companion object {
        /** Sessions are swept at every collector start and power transition, so few are ever pending. */
        const val PENDING_LOOKBACK = 50
    }
}

/**
 * One row per uid: `app_snapshot_uids` REPLACEs on (snapshotId, uid), so duplicates would silently keep only
 * the last. Totals add up; the first package name wins; a field stays null only when every row lacks it.
 */
internal fun List<AppUsageRow>.collapseByUid(): List<AppUsageRow> = groupBy { it.uid }.map { (_, rows) ->
    rows.reduce { a, b ->
        a.copy(
            powerMah = a.powerMah + b.powerMah,
            cpuTimeMs = addOrNull(a.cpuTimeMs, b.cpuTimeMs),
            foregroundTimeMs = addOrNull(a.foregroundTimeMs, b.foregroundTimeMs),
            backgroundTimeMs = addOrNull(a.backgroundTimeMs, b.backgroundTimeMs),
            wakelockTimeMs = addOrNull(a.wakelockTimeMs, b.wakelockTimeMs),
            mobileBytes = addOrNull(a.mobileBytes, b.mobileBytes),
            wifiBytes = addOrNull(a.wifiBytes, b.wifiBytes),
        )
    }
}

private fun addOrNull(a: Long?, b: Long?): Long? = if (a == null && b == null) null else (a ?: 0L) + (b ?: 0L)
