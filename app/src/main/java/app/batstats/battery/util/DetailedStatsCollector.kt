package app.batstats.battery.util

import app.batstats.battery.apps.AppStatsRepository
import app.batstats.battery.apps.AppStatsResult
import app.batstats.battery.diagnostics.DiagnosticCode
import app.batstats.battery.diagnostics.DiagnosticStore
import android.util.Log
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The old detailed-stats screen's state holder. The batterystats dump now comes from [AppStatsRepository]
 * (shared, cached, joined by concurrent callers); this shim only keeps the screen's flows and its extra
 * deviceidle/power dumps until P4c replaces the screen.
 */
@Deprecated("Removed in P4c")
class DetailedStatsCollector(
    private val shellRunner: ShellRunner,
    private val diagnostics: DiagnosticStore,
    private val appStats: AppStatsRepository,
) {
    companion object {
        private const val TAG = "DetailedStatsCollector"

        const val NO_ACCESS_MESSAGE = AppStatsRepository.NO_ACCESS_MESSAGE
    }

    private val refreshing = AtomicBoolean(false)
    private val accessGeneration = AtomicLong()

    private val _snapshot = MutableStateFlow<BatteryStatsParser.FullSnapshot?>(null)
    val snapshot: StateFlow<BatteryStatsParser.FullSnapshot?> = _snapshot.asStateFlow()

    private val _deviceIdle = MutableStateFlow<BatteryStatsParser.DeviceIdleInfo?>(null)
    val deviceIdle: StateFlow<BatteryStatsParser.DeviceIdleInfo?> = _deviceIdle.asStateFlow()

    private val _powerManager = MutableStateFlow<BatteryStatsParser.PowerManagerInfo?>(null)
    val powerManager: StateFlow<BatteryStatsParser.PowerManagerInfo?> = _powerManager.asStateFlow()

    private val _lastRefresh = MutableStateFlow(0L)
    val lastRefresh: StateFlow<Long> = _lastRefresh.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _mode = MutableStateFlow(ShellRunner.Mode.NONE)
    val mode: StateFlow<ShellRunner.Mode> = _mode.asStateFlow()

    @Volatile private var lastAttemptElapsed = Long.MIN_VALUE

    fun accessChanged(mode: ShellRunner.Mode) {
        if (mode != _mode.value) diagnostics.record(when (mode) {
            ShellRunner.Mode.NONE -> DiagnosticCode.ACCESS_NONE
            ShellRunner.Mode.SHIZUKU -> DiagnosticCode.ACCESS_SHIZUKU
            ShellRunner.Mode.ROOT -> DiagnosticCode.ACCESS_ROOT
            ShellRunner.Mode.ADB -> DiagnosticCode.ACCESS_ADB
        })
        if (mode != _mode.value || mode == ShellRunner.Mode.NONE) {
            accessGeneration.incrementAndGet()
            _snapshot.value = null; _deviceIdle.value = null; _powerManager.value = null
            _lastRefresh.value = 0; lastAttemptElapsed = Long.MIN_VALUE
            _mode.value = mode
            _error.value = if (mode == ShellRunner.Mode.NONE) NO_ACCESS_MESSAGE else null
        }
    }

    fun clearError() {
        _error.value = null
    }

    suspend fun refresh(force: Boolean = false): Boolean {
        if (!force && lastAttemptElapsed != Long.MIN_VALUE && SystemClock.elapsedRealtime() - lastAttemptElapsed < 30_000) return _snapshot.value != null
        if (!refreshing.compareAndSet(false, true)) {
            Log.d(TAG, "Refresh already in progress")
            return false
        }

        lastAttemptElapsed = SystemClock.elapsedRealtime()
        _isRefreshing.value = true
        Log.d(TAG, "Starting refresh...")

        val previousError = _error.value
        return try {
            val selectedMode = shellRunner.detectMode(forceRefresh = true)
            if (selectedMode == ShellRunner.Mode.NONE) {
                accessChanged(ShellRunner.Mode.NONE)
                lastAttemptElapsed = SystemClock.elapsedRealtime()
                return false
            }
            accessChanged(selectedMode)
            lastAttemptElapsed = SystemClock.elapsedRealtime()
            val generation = accessGeneration.get()
            var newSnapshot: BatteryStatsParser.FullSnapshot? = null
            var newIdle: BatteryStatsParser.DeviceIdleInfo? = null
            var newPower: BatteryStatsParser.PowerManagerInfo? = null
            var hasData = false
            val failures = mutableListOf<String>()

            // The repository records its own diagnostics for a failed or unreadable dump.
            val stats = appStats.snapshot(force)
            if (generation != accessGeneration.get() || shellRunner.access.value != selectedMode) { accessChanged(shellRunner.access.value); return false }
            when (stats) {
                is AppStatsResult.Ready -> {
                    newSnapshot = stats.snapshot
                    hasData = true
                }
                AppStatsResult.NoAccess -> {
                    _snapshot.value = null; _lastRefresh.value = 0
                    failures += NO_ACCESS_MESSAGE
                }
                is AppStatsResult.Failed -> {
                    _snapshot.value = null; _lastRefresh.value = 0
                    failures += stats.message
                    Log.e(TAG, "batterystats failed: ${stats.message}")
                }
            }

            when (val idle = shellRunner.exec("dumpsys deviceidle")) {
                is ShellRunner.Outcome.Success -> {
                    if (idle.mode != selectedMode || generation != accessGeneration.get()) { accessChanged(shellRunner.access.value); return false }
                    newIdle = BatteryStatsParser.parseDeviceIdle(idle.output)
                }

                is ShellRunner.Outcome.Failure -> {
                    _deviceIdle.value = null
                    failures += "Doze state: ${idle.message}"
                }
            }

            when (val power = shellRunner.exec("dumpsys power")) {
                is ShellRunner.Outcome.Success -> {
                    if (power.mode != selectedMode || generation != accessGeneration.get()) { accessChanged(shellRunner.access.value); return false }
                    newPower = BatteryStatsParser.parsePowerManager(power.output)
                }

                is ShellRunner.Outcome.Failure -> {
                    _powerManager.value = null
                    failures += "Power state: ${power.message}"
                }
            }

            currentCoroutineContext().ensureActive()
            if (generation != accessGeneration.get() || shellRunner.access.value != selectedMode) { accessChanged(shellRunner.access.value); return false }
            _snapshot.value = newSnapshot
            _deviceIdle.value = newIdle
            _powerManager.value = newPower
            _lastRefresh.value = newSnapshot?.capturedAt ?: 0
            _error.value = failures.takeIf { it.isNotEmpty() }?.joinToString("\n")

            if (failures.isNotEmpty() && stats !is AppStatsResult.Failed) diagnostics.record(DiagnosticCode.ADVANCED_READ_FAILED)
            else if (previousError != null && hasData) diagnostics.record(DiagnosticCode.ADVANCED_RECOVERED)
            hasData
        } catch (ce: CancellationException) {
            diagnostics.record(DiagnosticCode.ADVANCED_INTERRUPTED)
            throw ce
        } catch (e: Exception) {
            diagnostics.record(DiagnosticCode.ADVANCED_READ_FAILED)
            Log.e(TAG, "Refresh failed with exception", e)
            _snapshot.value = null; _deviceIdle.value = null; _powerManager.value = null; _lastRefresh.value = 0
            _error.value = "Collection failed: ${e.javaClass.simpleName}"
            false
        } finally {
            _isRefreshing.value = false
            refreshing.set(false)
        }
    }

    suspend fun resetStats(): Boolean {
        if (!refreshing.compareAndSet(false, true)) {
            _error.value = "Collection is in progress. Try the reset again after it finishes."
            return false
        }
        return try {
            val outcome = shellRunner.exec("dumpsys batterystats --reset", allowEmpty = true)
            if (outcome is ShellRunner.Outcome.Success) {
                diagnostics.record(DiagnosticCode.SYSTEM_STATS_RESET)
                appStats.invalidate() // A new stats window: the cached dump is from the old one.
                accessGeneration.incrementAndGet()
                _snapshot.value = null; _deviceIdle.value = null; _powerManager.value = null
                _lastRefresh.value = 0; lastAttemptElapsed = Long.MIN_VALUE
                _error.value = null
                true
            } else {
                _error.value = (outcome as ShellRunner.Outcome.Failure).message
                false
            }
        } finally { refreshing.set(false) }
    }
}
