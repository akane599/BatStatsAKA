package app.batstats.battery.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.drain.DrainNotificationManager
import kotlinx.coroutines.flow.StateFlow

/** Starts and stops [BatteryMonitorService]; the service itself starts and stops the sampler. */
class MonitoringController(
    private val context: Context,
    repository: BatteryRepository,
) : MonitoringControl {
    override val isMonitoring: StateFlow<Boolean> = repository.isMonitoringFlow

    override fun start(): MonitoringControl.StartResult {
        if (isMonitoring.value) return MonitoringControl.StartResult.ALREADY_RUNNING
        DrainNotificationManager.ensureChannel(context)
        return try {
            ContextCompat.startForegroundService(context, service())
            MonitoringControl.StartResult.STARTED
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) is an IllegalStateException, as is
            // any other "not allowed to start service" refusal from the background.
            Log.w("MonitoringController", "Foreground service start refused (${e.javaClass.simpleName})")
            MonitoringControl.StartResult.BLOCKED
        }
    }

    override fun stop() {
        context.stopService(service())
    }

    private fun service() = Intent(context, BatteryMonitorService::class.java)
}
