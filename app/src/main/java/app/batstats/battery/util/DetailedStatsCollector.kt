package app.batstats.battery.util

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
 * Collects comprehensive battery stats using Root/Shizuku/ADB-granted DUMP.
 * Provides parsed data for the detailed stats screen.
 */
class DetailedStatsCollector(
    private val shellRunner: ShellRunner,
    private val diagnostics: DiagnosticStore
) {
    companion object {
        private const val TAG = "DetailedStatsCollector"

        const val NO_ACCESS_MESSAGE =
            "Need Shizuku, root, or ADB-granted DUMP and PACKAGE_USAGE_STATS with usage app-op access. See Advanced statistics > Access setup."
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

            Log.d(TAG, "Fetching batterystats...")
            when (val stats = shellRunner.exec("dumpsys batterystats -c --charged")) {
                is ShellRunner.Outcome.Success -> {
                    if (stats.mode != selectedMode || generation != accessGeneration.get()) { accessChanged(shellRunner.access.value); return false }
                    Log.d(TAG, "Parsing batterystats (${stats.output.length} chars, via ${stats.mode})...")
                    val parsed = BatteryStatsParser.parseCheckin(stats.output)
                    if (!parsed.hasValidWindow) {
                        diagnostics.record(DiagnosticCode.ADVANCED_FORMAT_INVALID)
                        failures += "Battery statistics format unavailable or incomplete"
                    } else {
                        newSnapshot = parsed.copy(source = "Android batterystats · ${stats.mode.name}")
                        hasData = true
                    }
                    Log.d(TAG, "Parsed ${parsed.apps.size} apps, ${parsed.wakelocks.size} wakelocks")
                }

                is ShellRunner.Outcome.Failure -> {
                    _snapshot.value = null; _lastRefresh.value = 0
                    failures += describe(stats)
                    Log.e(TAG, "batterystats failed: ${stats.mode} / ${stats.message}")
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

            if (failures.isNotEmpty()) diagnostics.record(DiagnosticCode.ADVANCED_READ_FAILED)
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

    private fun describe(failure: ShellRunner.Outcome.Failure): String = when (failure.mode) {
        ShellRunner.Mode.NONE -> NO_ACCESS_MESSAGE
        ShellRunner.Mode.SHIZUKU ->
            "Shizuku is connected but the dump failed: ${failure.message}. Try again, or restart Shizuku."
        ShellRunner.Mode.ROOT ->
            "Root is available but the dump failed: ${failure.message}."
        ShellRunner.Mode.ADB ->
            "DUMP and usage-stat access were detected but the dump failed: ${failure.message}."
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
