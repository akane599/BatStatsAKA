package app.batstats.battery.drain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.batstats.battery.BatteryMainActivity
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.measurement.ObservationSummary
import java.text.DateFormat
import java.util.Date

/** Stable foreground notification. The service owns updates and polling. */
class DrainNotificationManager(private val context: Context, private val repository: BatteryRepository) {
    companion object {
        const val CHANNEL_ID = "drain_stats_channel"
        const val NOTIFICATION_ID = 2001
    }
    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Battery monitoring", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Live battery readings and observed drain; silent while monitoring"
                setShowBadge(false); enableLights(false); enableVibration(false)
            }
        )
    }
    fun getNotification(
        reading: BatteryRepository.Realtime = repository.realtimeFlow.value,
        summary: ObservationSummary = repository.observation.value,
        access: String = "Standard battery readings",
        error: String? = repository.error.value
    ): Notification {
        val content = PendingIntent.getActivity(context, 0,
            Intent(context, BatteryMainActivity::class.java).putExtra("open_drain_stats", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val reset = PendingIntent.getBroadcast(context, 1,
            Intent(context, DrainNotificationReceiver::class.java).setAction(DrainNotificationReceiver.ACTION_RESET),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val freshness = reading.sample?.timestamp?.let {
            "Reading ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}"
        } ?: "Waiting for Android battery data"
        val expanded = buildString {
            appendLine("$freshness · $access")
            error?.let { appendLine("Collection issue: $it") }
            append(MonitoringText.expanded(summary))
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("${reading.level?.let { "$it%" } ?: "—"} · ${MonitoringText.state(reading.powerState)}")
            .setContentText(error ?: "On ${formatDrainRate(summary.screenOn.rateMa)} · Off ${formatDrainRate(summary.screenOff.rateMa)} · $freshness")
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            .setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true)
            .setWhen(0L).setShowWhen(false).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_menu_rotate, "Reset observation", reset).build()
    }
    fun stopNotification() { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
}
