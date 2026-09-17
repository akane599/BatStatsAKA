package app.batstats.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.batstats.battery.shizuku.ShizukuBridge
import app.batstats.battery.util.DetailedStatsCollector
import app.batstats.battery.util.RootStatsCollector
import app.batstats.battery.util.KernelStats
import app.batstats.battery.util.ShellRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class DetailedStatsViewModel(
    private val collector: DetailedStatsCollector,
    private val shizuku: ShizukuBridge,
    private val shell: ShellRunner,
    context: Context
) : ViewModel() {
    val snapshot = collector.snapshot
    val deviceIdle = collector.deviceIdle
    val powerManager = collector.powerManager
    val lastRefresh = collector.lastRefresh
    val isRefreshing = collector.isRefreshing
    val error = collector.error
    val hasShizuku = shizuku.granted
    val shizukuRunning = shizuku.running
    val advMode = collector.mode
    private val _hasRoot = MutableStateFlow(false)
    val hasRoot = _hasRoot.asStateFlow()
    private val _kernelBattery = MutableStateFlow<KernelStats.Battery?>(null)
    val kernelBattery = _kernelBattery.asStateFlow()
    private var refreshJob: Job? = null
    val adbCommands = listOf(
        "adb shell pm grant ${context.packageName} android.permission.DUMP",
        "adb shell pm grant ${context.packageName} android.permission.PACKAGE_USAGE_STATS",
        "adb shell appops set ${context.packageName} GET_USAGE_STATS allow"
    ).joinToString("\n")

    init {
        viewModelScope.launch {
            combine(shizuku.running, shizuku.granted) { running, granted -> running to granted }.collect {
                // Drop stale values immediately, before potentially slow mode probing.
                collector.accessChanged(ShellRunner.Mode.NONE)
                refreshJob?.cancel()
                refreshJob = null
                shell.invalidateMode()
                refresh()
            }
        }
    }
    fun recheck() = refresh()
    fun requestShizukuPermission() = shizuku.requestPermission()
    fun refresh(forceRefresh: Boolean = false) {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            val mode = shell.detectMode(forceRefresh = true)
            collector.accessChanged(mode)
            if (mode == ShellRunner.Mode.ROOT) _hasRoot.value = true
            collector.refresh(force = forceRefresh)
        }
    }
    fun refreshRootStats() {
        viewModelScope.launch {
            RootStatsCollector.invalidateRootCache()
            _hasRoot.value = RootStatsCollector.isRootAvailable()
            _kernelBattery.value = if (_hasRoot.value) RootStatsCollector.getKernelBatteryInfo() else null
        }
    }
    suspend fun resetStats(): Boolean {
        return try { collector.resetStats() }
        catch (e: CancellationException) { throw e }
    }
}
