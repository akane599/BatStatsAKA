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

internal fun resumesMonitoring(
    action: String?,
    autoStart: Boolean = true,
    monitoringWanted: Boolean = true,
): Boolean = autoStart && when (action) {
    Intent.ACTION_BOOT_COMPLETED -> true
    Intent.ACTION_MY_PACKAGE_REPLACED -> monitoringWanted
    else -> false
}

/** Resumes after reboot or a wanted app-update restart when auto-start is on; offers a prompt if refused. */
class BootReceiver : BroadcastReceiver(), KoinComponent {
    private val monitoring: MonitoringControl by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (!resumesMonitoring(intent.action)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val settings = withTimeout(8_000) { BatteryGraph.settings.flow.first() }
                if (resumesMonitoring(intent.action, settings.autoStartOnBoot, monitoring.monitoringWanted) &&
                    monitoring.start() == MonitoringControl.StartResult.BLOCKED
                ) {
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
