package app.batstats.battery.drain

import android.content.Context
import android.content.Intent
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.service.BatteryMonitorService

/** Screen accounting uses ordinary Android readings and has exactly one service-owned sampler. */
class AdvancedDrainTracker(private val context: Context, private val repository: BatteryRepository) {
    val drainState = repository.observation
    val isTracking = repository.isMonitoringFlow
    fun isRunning() = isTracking.value
    fun start() { context.startForegroundService(Intent(context, BatteryMonitorService::class.java)) }
    fun stop() { context.stopService(Intent(context, BatteryMonitorService::class.java)) }
    fun resetSession() = repository.resetObservation()
}
