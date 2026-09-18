package app.batstats.battery.drain

import app.batstats.R
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
    private val text = MonitoringText(context)
    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.monitor_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.monitor_channel_description)
                setShowBadge(false); enableLights(false); enableVibration(false)
            }
        )
    }
    fun getNotification(
        reading: BatteryRepository.Realtime = repository.realtimeFlow.value,
        summary: ObservationSummary = repository.observation.value,
        access: String = context.getString(R.string.monitor_standard),
        error: String? = repository.error.value
    ): Notification {
        val content = PendingIntent.getActivity(context, NOTIFICATION_ID,
            Intent(context, BatteryMainActivity::class.java).setAction("app.batstats.OPEN_DRAIN")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("open_drain_stats", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val reset = PendingIntent.getBroadcast(context, 1,
            Intent(context, DrainNotificationReceiver::class.java).setAction(DrainNotificationReceiver.ACTION_RESET),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val freshness = reading.sample?.timestamp?.let {
            context.getString(R.string.monitor_read_at, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)))
        } ?: context.getString(R.string.monitor_waiting_battery)
        val expanded = buildString {
            appendLine("$freshness · $access")
            error?.let { appendLine(context.getString(R.string.monitor_collection_issue, it)) }
            append(text.expanded(summary))
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("${reading.level?.let { "$it%" } ?: "—"} · ${text.state(reading.powerState)}")
            .setContentText(error ?: context.getString(R.string.monitor_collapsed, formatDrainRate(summary.screenOn.rateMa), formatDrainRate(summary.screenOff.rateMa), freshness))
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            .setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true)
            .setWhen(0L).setShowWhen(false).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_menu_rotate, context.getString(R.string.monitor_reset), reset).build()
    }
    fun stopNotification() { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
}
