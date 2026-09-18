package app.batstats.battery.drain

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Receiver for notification actions (reset, etc.)
 */
class DrainNotificationReceiver : BroadcastReceiver(), KoinComponent {
    companion object {
        const val ACTION_RESET = "app.batstats.battery.drain.ACTION_RESET"
    }

    private val drainTracker: AdvancedDrainTracker by inject()

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_RESET -> {
                drainTracker.resetSession()
            }
        }
    }
}