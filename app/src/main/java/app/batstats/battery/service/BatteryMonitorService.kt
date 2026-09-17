package app.batstats.battery.service

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
    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started) return START_STICKY
        try {
            val notification = notifications.getNotification()
            if (Build.VERSION.SDK_INT >= 34) startForeground(DrainNotificationManager.NOTIFICATION_ID,
                notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(DrainNotificationManager.NOTIFICATION_ID, notification)
        } catch (e: RuntimeException) {
            Log.e("BatteryMonitorService", "Could not start monitoring", e)
            Notifier.promptStartOnBoot(this)
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
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
        serviceScope.launch {
            var lastPush = 0L
            var previousImportant: String? = null
            combine(repository.realtimeFlow, repository.observation, repository.error, shell.access, shell.lastError) {
                    reading, observation, historyError, access, accessError ->
                Triple(reading, observation, Triple(access, historyError, accessError))
            }.collect { (reading, observation, state) ->
                val (access, historyError, accessError) = state
                val issue = historyError ?: accessError?.let { "Advanced statistics: $it" }
                val important = "${reading.level}/${reading.powerState}/$access/$issue/${observation.startedAt}/${observation.gaps}"
                val now = SystemClock.elapsedRealtime()
                if (important != previousImportant || now - lastPush >= 30_000) {
                    val label = if (access == ShellRunner.Mode.NONE) "Standard · advanced access unavailable" else "Advanced source: $access"
                    getSystemService(NotificationManager::class.java).notify(DrainNotificationManager.NOTIFICATION_ID,
                        notifications.getNotification(reading, observation, label, issue))
                    reading.sample?.let { WidgetUpdater.push(this@BatteryMonitorService, it) }
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
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
