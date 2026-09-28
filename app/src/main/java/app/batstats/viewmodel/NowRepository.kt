package app.batstats.viewmodel

import app.batstats.battery.apps.AppStatsRepository
import app.batstats.battery.apps.AppUsageSnapshot
import app.batstats.battery.apps.SessionSnapshotStore
import app.batstats.battery.apps.toAppUsageSnapshot
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.CalibrationStore
import app.batstats.battery.data.DesignCapacityReading
import app.batstats.battery.data.DesignCapacitySource
import app.batstats.battery.data.db.BatteryDatabase
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.DailySummary
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.measurement.CalibrationState
import app.batstats.settings.AppSettings
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * What the Now screen reads and does, as one seam: [DefaultNowRepository] on device, a fake in unit tests. Nothing
 * here starts privileged work: per-app data is only the last cached dump (the Apps screen fetches on demand).
 */
interface NowRepository {
    /** The latest capture, calibrated; every 2 s while the screen holds SamplingDemand. */
    val realtime: StateFlow<BatteryRepository.Realtime>
    val calibration: StateFlow<CalibrationState>
    val settings: Flow<AppSettings>

    /** The app-wide design capacity (Settings override, else sysfs read once through root), shared with Health. */
    val design: Flow<DesignCapacityReading>

    /** The last privileged dump's per-app usage, or null; reading it never starts a dump. */
    val cachedAppUsage: Flow<AppUsageSnapshot?>
    val activeSession: Flow<ChargeSession?>

    /** Stored samples from [fromMs] on, oldest first; re-emits when rows are saved. */
    fun samplesSince(fromMs: Long): Flow<List<BatterySample>>
    fun day(epochDay: Long): Flow<DailySummary?>

    /** The newest [limit] sessions of any type, newest first. */
    fun recentSessions(limit: Int): Flow<List<ChargeSession>>

    /** The newest [limit] DISCHARGE sessions (the open one first while on battery), newest first. */
    fun dischargeSessions(limit: Int): Flow<List<ChargeSession>>

    /** The session's BASELINE snapshot, when one was captured. */
    suspend fun baseline(sessionId: String): AppUsageSnapshot?

    /** Closes the open session and starts a new one (and a new observation window) at the next capture. */
    fun resetObservation()
    fun undoCalibration()
    fun dismissCalibrationNotice()
}

/** [NowRepository] over the app's repositories; every read is a Flow or a main-safe suspend call. */
class DefaultNowRepository(
    private val repository: BatteryRepository,
    private val database: BatteryDatabase,
    private val calibrationStore: CalibrationStore,
    appStats: AppStatsRepository,
    private val snapshots: SessionSnapshotStore,
    settingsRepository: SettingsRepository<AppSettings>,
    designCapacity: DesignCapacitySource,
) : NowRepository {
    override val realtime = repository.realtimeFlow
    override val calibration = calibrationStore.state
    override val settings = settingsRepository.flow
    override val design = designCapacity.design
    override val cachedAppUsage = appStats.cached.map { it?.toAppUsageSnapshot() }
    override val activeSession = repository.activeSessionFlow
    override fun samplesSince(fromMs: Long) = repository.samplesBetween(fromMs, Long.MAX_VALUE)
    override fun day(epochDay: Long) = database.dailySummaryDao().day(epochDay)
    override fun recentSessions(limit: Int) = repository.sessionDao.filteredSessions(null, "", limit)
    override fun dischargeSessions(limit: Int) = repository.sessionDao.filteredSessions(SessionType.DISCHARGE, "", limit)
    override suspend fun baseline(sessionId: String) = snapshots.baseline(sessionId)
    override fun resetObservation() = repository.resetObservation()
    override fun undoCalibration() = calibrationStore.undoLastCorrection()
    override fun dismissCalibrationNotice() = calibrationStore.dismissNotice()
}
