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

internal fun resumesMonitoring(action: String?): Boolean =
    action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED

/** Resumes monitoring after a reboot or app update when auto-start is on; if refused, offers a start notification. */
class BootReceiver : BroadcastReceiver(), KoinComponent {
    private val monitoring: MonitoringControl by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (!resumesMonitoring(intent.action)) return
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
