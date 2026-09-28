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
import app.batstats.battery.apps.SessionSnapshotCollector
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.drain.DrainNotificationManager
import app.batstats.battery.util.DetailedStatsCollector
import app.batstats.battery.util.ShellRunner
import app.batstats.battery.util.Notifier
import app.batstats.battery.measurement.BatteryAlerts
import app.batstats.battery.measurement.BatteryAlert
import app.batstats.battery.measurement.BatteryAlertSettings
import app.batstats.battery.measurement.AlertReading
import app.batstats.battery.util.UpdateGate
import app.batstats.battery.widget.WidgetUpdater
import app.batstats.settings.useFahrenheit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.ext.android.inject

class BatteryMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository: BatteryRepository by inject()
    private val notifications: DrainNotificationManager by inject()
    private val shell: ShellRunner by inject()
    @Suppress("DEPRECATION") // The old advanced-stats error line, until P4c reworks the notification source.
    private val collector: DetailedStatsCollector by inject()
    private val sessionSnapshots: SessionSnapshotCollector by inject()
    private val diagnostics: DiagnosticStore by inject()
    private var started = false
    private var monitoringStartedElapsed = 0L

    private fun startMonitoringForeground() {
        val notification = notifications.getNotification()
        if (Build.VERSION.SDK_INT >= 34) startForeground(DrainNotificationManager.NOTIFICATION_ID,
            notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(DrainNotificationManager.NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (repository.isClearingHistory) {
            // The service may have been started with startForegroundService(); Android requires
            // startForeground() to be called regardless, or it throws ForegroundServiceDidNotStartInTimeException.
            try { startMonitoringForeground() }
            catch (e: RuntimeException) { Log.e("BatteryMonitorService", "Could not start monitoring while clearing", e) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_STICKY
        try {
            startMonitoringForeground()
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
        // Per-app baselines/ends at unplug and plug-in; runs (and dumps) only while monitoring runs.
        serviceScope.launch(Dispatchers.Default) { sessionSnapshots.run() }
        serviceScope.launch(Dispatchers.IO) {
            // Private, excluded from automatic backup; changes are written only at episode boundaries.
            val preferences = getSharedPreferences("battery_alert_episodes", MODE_PRIVATE)
            val saved = preferences.getStringSet("latched", emptySet()).orEmpty()
            val alerts = BatteryAlerts(BatteryAlert.entries.filter { it.name in saved }.toSet())
            var alertChannelReady = false
            var previousIntervalMs: Long? = null
            combine(repository.realtimeFlow, repository.settingsFlow) { reading, settings -> reading to settings }
                .collect { (reading, settings) ->
                    val sample = reading.sample
                    if (sample == null || sample.elapsedMs == null || sample.elapsedMs < monitoringStartedElapsed) return@collect
                    val before = alerts.latches
                    // The longer of the two cadences around this interval, as ObservationEngine's gap rule
                    // allows: a 300 s → 30 s switch at screen-on is not a gap.
                    val intervalMs = maxOf(previousIntervalMs ?: reading.expectedIntervalMs, reading.expectedIntervalMs)
                    previousIntervalMs = reading.expectedIntervalMs
                    // Calibrated current: an inverted or mA-reporting device still trips the discharge alert.
                    val events = alerts.accept(AlertReading(sample.elapsedMs, sample.levelPercent,
                        sample.status, sample.plugged, reading.currentUa, sample.temperatureDeciC, intervalMs),
                        BatteryAlertSettings(settings.lowBatteryAlertEnabled, settings.lowBatteryThreshold,
                            settings.highBatteryAlertEnabled, settings.highBatteryThreshold,
                            settings.temperatureWarningEnabled, settings.temperatureThreshold.toDouble(),
                            settings.dischargeAlertEnabled, settings.dischargeCurrentThreshold, settings.chargingCompleteAlert))
                    try {
                        if (events.isNotEmpty()) {
                            if (!alertChannelReady) {
                                Notifier.ensureAlertChannel(this@BatteryMonitorService)
                                alertChannelReady = true
                            }
                            if (Notifier.canPostAlerts(this@BatteryMonitorService)) {
                                // The alert text shows the calibrated current, as the realtime values do.
                                val shown = sample.copy(currentNowUa = reading.currentUa)
                                events.forEach { Notifier.batteryAlert(this@BatteryMonitorService, it, shown, settings) }
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
        // Display updates: only with the screen on, on changed content, ≥5 s apart; SCREEN_ON pushes at once.
        serviceScope.launch {
            val gate = UpdateGate<DrainNotificationManager.Content>()
            val advanced = combine(shell.access, shell.lastError, collector.error) { access, shellError, collectorError ->
                access to (collectorError ?: shellError)
            }
            combine(repository.realtimeFlow, repository.observation, repository.error, advanced) {
                    reading, observation, historyError, (access, accessError) ->
                val issue = historyError ?: accessError?.let { getString(R.string.monitor_advanced_issue, it) }
                val label = if (access == ShellRunner.Mode.NONE) getString(R.string.monitor_standard_unavailable) else getString(R.string.monitor_source, access.name)
                val content = notifications.content(reading, observation, label, issue, advancedIssue = historyError == null && accessError != null)
                content to (reading.sample?.screenOn != false)
            }.collectLatest { (content, screenOn) ->
                if (!gate.awaitTurn(content, screenOn, SystemClock::uptimeMillis)) return@collectLatest
                val shown = display("notification") {
                    getSystemService(NotificationManager::class.java).notify(DrainNotificationManager.NOTIFICATION_ID, notifications.build(content))
                }
                // A failed update retries on the next change, still ≥5 s after this attempt.
                gate.pushed(content.takeIf { shown }, SystemClock.uptimeMillis())
            }
        }
        serviceScope.launch {
            val gate = UpdateGate<WidgetUpdater.Content>()
            combine(repository.realtimeFlow, repository.settingsFlow.map { it.useFahrenheit }.distinctUntilChanged()) { reading, fahrenheit ->
                reading.sample?.let { sample -> WidgetUpdater.content(this@BatteryMonitorService, sample, monitoring = true, fahrenheit) to sample.screenOn }
            }.filterNotNull().collectLatest { (content, screenOn) ->
                if (!gate.awaitTurn(content, screenOn, SystemClock::uptimeMillis)) return@collectLatest
                val shown = display("widget") { WidgetUpdater.deliver(this@BatteryMonitorService, content) }
                gate.pushed(content.takeIf { shown }, SystemClock.uptimeMillis())
            }
        }
        return START_STICKY
    }

    private inline fun display(surface: String, update: () -> Unit): Boolean = try {
        update()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        diagnostics.record(DiagnosticCode.NOTIFICATION_FAILED)
        Log.w("BatteryMonitorService", "Monitoring $surface update failed (${e.javaClass.simpleName})")
        false
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
