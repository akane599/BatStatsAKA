package app.batstats.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.service.MonitoringControl
import app.batstats.settings.AppSettings
import app.batstats.settings.AppSettingsSchema
import app.batstats.settings.chartTimeRangeMs
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DashboardViewModel(
    private val app: Application,
    private val repo: BatteryRepository,
    settingsRepository: SettingsRepository<AppSettings>,
    private val monitoring: MonitoringControl,
) : AndroidViewModel(app) {

    val settings: StateFlow<AppSettings> = settingsRepository.flow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettingsSchema.default
        )

    val observation = repo.observation
    val collectionError = repo.error
    fun refresh() = repo.refreshNow()

    val realtime: StateFlow<BatteryRepository.Realtime> = repo.realtimeFlow
    val isMonitoring: StateFlow<Boolean> = repo.isMonitoringFlow

    val activeSession: StateFlow<ChargeSession?> = repo.activeSessionFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    @Suppress("DEPRECATION") // Shim until P4c replaces this screen.
    @OptIn(ExperimentalCoroutinesApi::class)
    val recentSamples: Flow<List<BatterySample>> = settings
        .flatMapLatest { s ->
            repo.recentSamplesFlow(s.chartTimeRangeMs)
        }

    fun toggleMonitoring() {
        // Repo update happens in Service.onStartCommand / onDestroy.
        if (isMonitoring.value) monitoring.stop() else monitoring.start()
    }

    fun startManualSession(type: SessionType) {
        viewModelScope.launch {
            repo.startSession(type)
        }
    }

    fun endSession() {
        viewModelScope.launch {
            repo.endCurrentSession()
        }
    }
}