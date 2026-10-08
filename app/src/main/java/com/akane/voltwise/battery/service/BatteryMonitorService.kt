package com.akane.voltwise.battery.service

import com.akane.voltwise.battery.diagnostics.DiagnosticCode
import com.akane.voltwise.battery.diagnostics.DiagnosticStore
import android.app.Service
import android.app.Notification
import androidx.core.app.NotificationCompat
import com.akane.voltwise.R
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.akane.voltwise.battery.apps.SessionSnapshotCollector
import com.akane.voltwise.battery.data.BatteryRepository
import com.akane.voltwise.battery.drain.DrainNotificationManager
import com.akane.voltwise.battery.drain.NotificationIssue
import com.akane.voltwise.battery.util.ShellRunner
import com.akane.voltwise.battery.util.Notifier
import com.akane.voltwise.battery.measurement.BatteryAlerts
import com.akane.voltwise.battery.measurement.BatteryAlert
import com.akane.voltwise.battery.measurement.BatteryAlertSettings
import com.akane.voltwise.battery.measurement.AlertReading
import com.akane.voltwise.battery.util.UpdateGate
import com.akane.voltwise.battery.widget.WidgetUpdater
import com.akane.voltwise.settings.useFahrenheit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.ext.android.inject

class BatteryMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository: BatteryRepository by inject()
    private val notifications: DrainNotificationManager by inject()
    private val shell: ShellRunner by inject()
    private val sessionSnapshots: SessionSnapshotCollector by inject()
    private val diagnostics: DiagnosticStore by inject()
    private var started = false
    private var monitoringStartedElapsed = 0L

    private fun startForegroundWith(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(DrainNotificationManager.NOTIFICATION_ID,
            notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(DrainNotificationManager.NOTIFICATION_ID, notification)
    }

    /**
     * A minimal notification that is cheap to build; the full one replaces it right after [startForegroundWith].
     * It carries the monitoring session's `when`, as every full post does, so the replacement doesn't move it.
     */
    private fun placeholder(sessionWhen: Long): Notification {
        DrainNotificationManager.ensureChannel(this)
        return NotificationCompat.Builder(this, DrainNotificationManager.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentTitle(getString(R.string.monitor_channel))
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .setWhen(sessionWhen).setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // After startForegroundService(), Android crashes the app unless startForeground() comes promptly and before
        // any stopSelf() (ForegroundServiceDidNotStartInTimeException). So promote first, with a cheap notification
        // when not yet running: building the full one can be slow while the main thread is busy. Not yet running
        // starts a monitoring session: one `when` for all its notifications.
        val promoted = try {
            startForegroundWith(if (started) notifications.promotionNotification() else placeholder(notifications.startSession()))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.e("BatteryMonitorService", "Could not enter the foreground", e)
            false
        }
        if (repository.isClearingHistory) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!promoted) {
            diagnostics.record(DiagnosticCode.START_FAILED)
            runCatching { Notifier.promptStartOnBoot(this) }
            stopSelf()
            return START_NOT_STICKY
        }
        display("start prompt") { Notifier.cancelStartPrompt(this) }
        if (started) return START_STICKY
        try {
            notifications.post(notifications.getNotification())
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // Monitoring continues; the update loop below replaces the placeholder on its next push.
            Log.w("BatteryMonitorService", "Could not show the full notification", e)
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
        // Display updates: only with the screen on, on changed content, ≥5 s apart; SCREEN_ON pushes at once (gated in run).
        serviceScope.launch {
            val issue = combine(repository.error, shell.lastError, shell.access) { historyError, shellError, access ->
                NotificationIssue.of(historyError, shellError, hasAdvancedAccess = access != ShellRunner.Mode.NONE)
            }
            notifications.run(issue) { content ->
                display("notification") {
                    notifications.post(notifications.build(content))
                }
            }
        }
        serviceScope.launch {
            val gate = UpdateGate<WidgetUpdater.Content>()
            // Held estimate: one content per capture, and the time widget never drops to "—" between them.
            combine(WidgetUpdater.readings(repository.realtimeFlow), repository.settingsFlow.map { it.useFahrenheit }.distinctUntilChanged()) { reading, fahrenheit ->
                WidgetUpdater.content(this@BatteryMonitorService, reading, monitoring = true, fahrenheit) to (reading.reading.sample?.screenOn != false)
            }.collectLatest { (content, screenOn) ->
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
