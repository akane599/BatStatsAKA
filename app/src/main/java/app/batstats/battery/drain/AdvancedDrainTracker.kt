package app.batstats.battery.drain

import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.service.MonitoringControl

/** Screen accounting uses ordinary Android readings and has exactly one service-owned sampler. */
class AdvancedDrainTracker(private val repository: BatteryRepository, private val monitoring: MonitoringControl) {
    val drainState = repository.observation
    val isTracking = monitoring.isMonitoring
    fun isRunning() = isTracking.value
    fun start() { monitoring.start() }
    fun stop() = monitoring.stop()
    fun resetSession() = repository.resetObservation()
}
