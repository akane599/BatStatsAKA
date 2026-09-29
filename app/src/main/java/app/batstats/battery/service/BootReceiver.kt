package app.batstats.battery.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.batstats.battery.BatteryGraph
import app.batstats.battery.util.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Resumes monitoring after a reboot when auto-start is on; if Android refuses, a notification offers to start it. */
class BootReceiver : BroadcastReceiver(), KoinComponent {
    private val monitoring: MonitoringControl by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val settings = withTimeout(8_000) { BatteryGraph.settings.flow.first() }
                if (settings.autoStartOnBoot && monitoring.start() == MonitoringControl.StartResult.BLOCKED) {
                    Notifier.promptStartOnBoot(context)
                }
            } catch (e: Exception) {
                Log.w("BootReceiver", "Could not resume battery monitoring", e)
            } finally {
                pending.finish()
            }
        }
    }
}
