package app.batstats.battery.service

import app.batstats.battery.diagnostics.DiagnosticCode
import app.batstats.battery.diagnostics.DiagnosticStore
import app.batstats.R
import android.app.Service
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.drain.DrainNotificationManager
import app.batstats.battery.util.DetailedStatsCollector
import app.batstats.settings.detailedStatsIntervalMs
import app.batstats.battery.shizuku.ShizukuBridge
import app.batstats.battery.util.ShellRunner
import app.batstats.battery.util.Notifier
import app.batstats.battery.measurement.BatteryAlerts
import app.batstats.battery.measurement.BatteryAlert
import app.batstats.battery.measurement.BatteryAlertSettings
import app.batstats.battery.measurement.AlertReading
import app.batstats.settings.monitoringIntervalMs
import app.batstats.battery.widget.WidgetUpdater
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.ext.android.inject

class BatteryMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository: BatteryRepository by inject()
    private val notifications: DrainNotificationManager by inject()
    private val shell: ShellRunner by inject()
    private val shizuku: ShizukuBridge by inject()
    private val collector: DetailedStatsCollector by inject()
    private val diagnostics: DiagnosticStore by inject()
    private var started = false
    private var monitoringStartedElapsed = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (repository.isClearingHistory) { stopSelf(); return START_NOT_STICKY }
        if (started) return START_STICKY
        try {
            val notification = notifications.getNotification()
            if (Build.VERSION.SDK_INT >= 34) startForeground(DrainNotificationManager.NOTIFICATION_ID,
                notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(DrainNotificationManager.NOTIFICATION_ID, notification)
        } catch (e: RuntimeException) {
            diagnostics.record(DiagnosticCode.START_FAILED)
            Log.e("BatteryMonitorService", "Could not start monitoring", e)
            runCatching { Notifier.promptStartOnBoot(this) }
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        monitoringStartedElapsed = SystemClock.elapsedRealtime()
        repository.startSampling()
        serviceScope.launch {
            repository.settingsFlow.map { it.detailedStatsIntervalMs }.distinctUntilChanged().collectLatest { interval ->
                while (isActive) { collector.refresh(); delay(interval) }
            }
        }
        serviceScope.launch {
            combine(shizuku.running, shizuku.granted) { running, granted -> running to granted }
                .collect {
                    val mode = shell.detectMode(forceRefresh = true)
                    collector.accessChanged(mode)
                    if (mode != ShellRunner.Mode.NONE) collector.refresh(force = true)
                }
        }
        serviceScope.launch(Dispatchers.IO) {
            // Private, excluded from automatic backup; changes are written only at episode boundaries.
            val preferences = getSharedPreferences("battery_alert_episodes", MODE_PRIVATE)
            val saved = preferences.getStringSet("latched", emptySet()).orEmpty()
            val alerts = BatteryAlerts(BatteryAlert.entries.filter { it.name in saved }.toSet())
            var alertChannelReady = false
            combine(repository.realtimeFlow, repository.settingsFlow) { reading, settings -> reading.sample to settings }
                .collect { (sample, settings) ->
                    if (sample == null || sample.elapsedMs == null || sample.elapsedMs < monitoringStartedElapsed) return@collect
                    val before = alerts.latches
                    val events = alerts.accept(AlertReading(sample.elapsedMs, sample.levelPercent,
                        sample.status, sample.plugged, sample.currentNowUa, sample.temperatureDeciC, settings.monitoringIntervalMs),
                        BatteryAlertSettings(settings.lowBatteryAlertEnabled, settings.lowBatteryThreshold,
                            settings.highBatteryAlertEnabled, settings.highBatteryThreshold,
                            settings.temperatureWarningEnabled, settings.temperatureThreshold.toDouble(),
                            settings.dischargeAlertEnabled, settings.dischargeCurrentThreshold, settings.chargingCompleteAlert))
                    try {
                        if (events.isNotEmpty()) {
                            if (!alertChannelReady) {
                                Notifier.ensureAlertChannel(this@BatteryMonitorService, settings)
                                alertChannelReady = true
                            }
                            if (Notifier.canPostAlerts(this@BatteryMonitorService)) {
                                events.forEach { Notifier.batteryAlert(this@BatteryMonitorService, it, sample, settings) }
                            } else alerts.restoreLatches(before)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: RuntimeException) {
                        alerts.restoreLatches(before)
                        diagnostics.record(DiagnosticCode.ALERT_FAILED)
                        Log.w("BatteryAlerts", "Alert delivery failed (${e.javaClass.simpleName})")
                    }
                    if (alerts.latches != before) preferences.edit().putStringSet("latched", alerts.latches.map { it.name }.toSet()).apply()
                }
        }
        serviceScope.launch {
            var lastPush = 0L
            var previousImportant: String? = null
            val advanced = combine(shell.access, shell.lastError, collector.error) { access, shellError, collectorError ->
                access to (collectorError ?: shellError)
            }
            combine(repository.realtimeFlow, repository.observation, repository.error, advanced) {
                    reading, observation, historyError, accessState ->
                Triple(reading, observation, Triple(accessState.first, historyError, accessState.second))
            }.collect { (reading, observation, state) ->
                val (access, historyError, accessError) = state
                val issue = historyError ?: accessError?.let { getString(R.string.monitor_advanced_issue, it) }
                val important = "${reading.level}/${reading.powerState}/$access/$issue/${observation.startedAt}/${observation.gaps}"
                val now = SystemClock.elapsedRealtime()
                if (important != previousImportant || now - lastPush >= 30_000) {
                    val label = if (access == ShellRunner.Mode.NONE) getString(R.string.monitor_standard_unavailable) else getString(R.string.monitor_source, access.name)
                    try {
                        getSystemService(NotificationManager::class.java).notify(DrainNotificationManager.NOTIFICATION_ID,
                            notifications.getNotification(reading, observation, label, issue, advancedIssue = historyError == null && accessError != null))
                        reading.sample?.let { WidgetUpdater.push(this@BatteryMonitorService, it,
                            fahrenheit = repository.getSettings().temperatureUnitIndex == 1) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: RuntimeException) {
                        diagnostics.record(DiagnosticCode.NOTIFICATION_FAILED)
                        Log.w("BatteryMonitorService", "Monitoring display update failed (${e.javaClass.simpleName})")
                    }
                    // Failed display updates retry on the next cadence or important state change.
                    previousImportant = important; lastPush = now
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        started = false
        repository.stopSampling()
        serviceScope.cancel()
        notifications.stopNotification()
        WidgetUpdater.push(this, repository.realtimeFlow.value.sample, monitoring = false)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
