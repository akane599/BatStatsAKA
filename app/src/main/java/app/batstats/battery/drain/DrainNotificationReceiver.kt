package app.batstats.battery.drain

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.service.MonitoringControl
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The ongoing notification's actions. Its PendingIntents name this receiver explicitly; the manifest's intent filter
 * still lists both actions, so delivery keeps working if Android starts requiring explicit intents to match a filter.
 */
class DrainNotificationReceiver : BroadcastReceiver(), KoinComponent {
    companion object {
        const val ACTION_RESET = "app.batstats.battery.drain.ACTION_RESET"
        const val ACTION_STOP = "app.batstats.battery.drain.ACTION_STOP"
    }

    private val repository: BatteryRepository by inject()
    private val monitoring: MonitoringControl by inject()

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // The writer resets only an open discharge window, as Now's Reset does.
            ACTION_RESET -> repository.resetObservation()
            // Stopping the service removes the notification (onDestroy).
            ACTION_STOP -> monitoring.stop()
        }
    }
}
