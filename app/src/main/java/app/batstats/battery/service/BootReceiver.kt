package app.batstats.battery.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.batstats.battery.BatteryGraph
import app.batstats.battery.util.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val settings = withTimeout(8_000) { BatteryGraph.settings.flow.first() }
                if (settings.autoStartOnBoot) {
                    try {
                        context.startForegroundService(Intent(context, BatteryMonitorService::class.java))
                    } catch (_: RuntimeException) {
                        Notifier.promptStartOnBoot(context)
                    }
                }
            } catch (e: Exception) {
                Log.w("BootReceiver", "Could not resume battery monitoring", e)
            } finally {
                pending.finish()
            }
        }
    }
}
