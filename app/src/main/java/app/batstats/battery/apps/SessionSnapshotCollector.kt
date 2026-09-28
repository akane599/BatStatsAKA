package app.batstats.battery.apps

import android.util.Log
import app.batstats.battery.data.PowerTransition
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.measurement.PowerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-discharge-session app breakdowns from two privileged dumps, run for the monitoring service's lifetime
 * ([run]). There is no periodic collection: a dump happens only here (plug-in, a new discharge session) and
 * when a screen asks [AppStatsSource].
 *
 * - **Baseline:** whenever the open session becomes a DISCHARGE session with no BASELINE (an unplug, monitoring
 *   start, a gap or reset that reopened it), wait [BASELINE_DEBOUNCE_MS] and store one. A change of open session
 *   before then cancels it. Keyed to the committed open-session row, so the row always exists first.
 * - **End:** at a plug-in that ended a DISCHARGE session ([PowerTransition]), wait [END_DEBOUNCE_MS] (the next
 *   transition cancels it), take a forced dump, store it as the END snapshot and the [AppUsageDelta] against the
 *   BASELINE as the session's breakdown (READY + basis); [AppStatsResult.NoAccess] → NO_ACCESS,
 *   [AppStatsResult.Failed] → FAILED. Once the debounce has passed, a later transition no longer cancels it.
 * - **Abandoned:** closed DISCHARGE sessions still PENDING that no end is being taken for (closed by Stop,
 *   process death, a gap, or a plug-in cancelled by an unplug) are marked FAILED: at every transition, and
 *   [STARTUP_SWEEP_DELAY_MS] after start (after the repository has closed a session left open by a dead process).
 */
class SessionSnapshotCollector(
    private val stats: AppStatsSource,
    private val store: SessionSnapshotStore,
    private val transitions: Flow<PowerTransition>,
    private val log: (String) -> Unit = { Log.d(LOG_TAG, it) },
) {
    private val writes = Mutex()
    // Sessions whose END is being taken; the sweep leaves them alone. Added before the handler returns.
    private val endsInProgress: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Runs until cancelled; the service cancels it when monitoring stops. */
    suspend fun run(): Unit = coroutineScope {
        launch { baselines() }
        // null = start: the sweep waits for the repository to close any session a dead process left open.
        merge(flowOf(null), transitions).collectLatest { transition ->
            guarded("transition") { onTransition(transition, this@coroutineScope) }
        }
    }

    // A query error in the flow itself ends baselines for this run instead of the service; the next start resumes.
    private suspend fun baselines() = guarded("open session") {
        store.openSession().collectLatest { open ->
            if (open?.type != SessionType.DISCHARGE) return@collectLatest
            guarded("baseline") {
                if (store.hasBaseline(open.sessionId)) return@guarded
                delay(BASELINE_DEBOUNCE_MS)
                when (val result = stats.snapshot(force = true)) {
                    is AppStatsResult.Ready -> {
                        val saved = writes.withLock { store.saveBaseline(open.sessionId, result.snapshot.toAppUsageSnapshot()) }
                        if (saved) log("baseline stored session=${open.sessionId} apps=${result.snapshot.apps.size}")
                    }
                    // Nothing stored: the session ends ABSOLUTE if a dump works at plug-in, else NO_ACCESS/FAILED.
                    AppStatsResult.NoAccess -> log("baseline skipped: no privileged access")
                    is AppStatsResult.Failed -> log("baseline failed: ${result.message}")
                }
            }
        }
    }

    private suspend fun onTransition(transition: PowerTransition?, scope: CoroutineScope) {
        if (transition == null) {
            delay(STARTUP_SWEEP_DELAY_MS)
            failAbandoned(except = null)
            return
        }
        val ended = transition.endedSessionId?.takeIf { transition.isPlugIn }
        failAbandoned(except = ended)
        if (ended == null) return
        delay(END_DEBOUNCE_MS)
        // Past the debounce: a later transition must not cancel the dump, but stopping the service does.
        endsInProgress += ended
        scope.launch {
            try { guarded("end") { finishEnded(ended) } }
            finally { endsInProgress -= ended }
        }
    }

    private suspend fun finishEnded(sessionId: String) {
        val result = stats.snapshot(force = true)
        writes.withLock {
            when (result) {
                is AppStatsResult.Ready -> {
                    val end = result.snapshot.toAppUsageSnapshot()
                    val delta = AppUsageDelta.compute(store.baseline(sessionId), end)
                    if (store.saveEnd(sessionId, end, delta)) {
                        log("end stored session=$sessionId basis=${delta.basis} rows=${delta.rows.size}")
                    }
                }
                AppStatsResult.NoAccess -> store.setStatus(sessionId, AppUsageStatus.NO_ACCESS)
                is AppStatsResult.Failed -> store.setStatus(sessionId, AppUsageStatus.FAILED)
            }
        }
        if (result !is AppStatsResult.Ready) log("end not captured session=$sessionId result=$result")
    }

    private suspend fun failAbandoned(except: String?) = writes.withLock {
        store.pendingClosedDischarges()
            .filter { it != except && it !in endsInProgress }
            .forEach { sessionId ->
                store.setStatus(sessionId, AppUsageStatus.FAILED)
                log("breakdown not captured session=$sessionId")
            }
    }

    /** A storage or dump error must never take the monitoring service down; it leaves the session PENDING. */
    private suspend fun guarded(step: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("$step failed (${e.javaClass.simpleName}: ${e.message})")
        }
    }

    private val PowerTransition.isPlugIn: Boolean
        get() = from == PowerState.DISCHARGING && to in setOf(PowerState.CHARGING, PowerState.PLUGGED)

    companion object {
        const val LOG_TAG = "BatStatsApps"
        const val END_DEBOUNCE_MS = 10_000L
        const val BASELINE_DEBOUNCE_MS = 30_000L
        const val STARTUP_SWEEP_DELAY_MS = 30_000L

        /** Set on the in-memory open session at creation: a breakdown is only ever taken for discharge sessions. */
        fun initialStatus(type: SessionType): AppUsageStatus =
            if (type == SessionType.DISCHARGE) AppUsageStatus.PENDING else AppUsageStatus.NOT_APPLICABLE
    }
}
