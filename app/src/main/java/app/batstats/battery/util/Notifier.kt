package app.batstats.battery.util

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.batstats.battery.measurement.BatteryAlert
import app.batstats.battery.data.db.BatterySample
import app.batstats.settings.AppSettings
import java.util.Locale
import app.batstats.R
import app.batstats.battery.BatteryMainActivity
import app.batstats.battery.service.BatteryMonitorService

object Notifier {
    private const val CH_ID = "battery_monitor"
    const val NOTIF_ID = 11

    fun ensureChannel(ctx: Context) {
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CH_ID) == null) {
            val ch = NotificationChannel(
                CH_ID,
                ctx.getString(R.string.monitor_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                enableLights(false)
                enableVibration(false)
                lightColor = Color.GREEN
                setShowBadge(false)
            }
            mgr.createNotificationChannel(ch)
        }
    }

    fun promptStartOnBoot(ctx: Context) {
        ensureChannel(ctx)
        val startIntent = Intent(ctx, BatteryMonitorService::class.java)
        val pi = if (Build.VERSION.SDK_INT >= 26) {
            PendingIntent.getForegroundService(
                ctx, 1, startIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            PendingIntent.getService(
                ctx, 1, startIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val n = NotificationCompat.Builder(ctx, CH_ID)
            .setContentTitle(ctx.getString(R.string.monitoring_ready))
            .setContentText(ctx.getString(R.string.tap_to_start))
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_media_play, ctx.getString(R.string.start_monitoring), pi)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(1000, n)
    }

    fun monitoringNotification(ctx: Context, text: String): Notification {
        ensureChannel(ctx)
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, BatteryMainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(ctx, CH_ID)
            .setContentTitle(ctx.getString(R.string.monitoring_battery))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentIntent(pi)
            .setWhen(0L)
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    const val ALERT_CHANNEL_ID = "battery_alerts"

    fun ensureAlertChannel(ctx: Context, settings: AppSettings) {
        val manager = ctx.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(ALERT_CHANNEL_ID) != null) return
        manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL_ID,
            ctx.getString(R.string.alert_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = ctx.getString(R.string.alert_channel_description)
            enableVibration(settings.alertVibrationEnabled)
            if (!settings.alertSoundEnabled) setSound(null, null)
            setShowBadge(false)
        })
    }

    fun canPostAlerts(ctx: Context): Boolean = NotificationManagerCompat.from(ctx).areNotificationsEnabled() &&
        ctx.getSystemService(NotificationManager::class.java).getNotificationChannel(ALERT_CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE

    fun batteryAlert(ctx: Context, type: BatteryAlert, sample: BatterySample, settings: AppSettings) {
        val (title, text) = when (type) {
            BatteryAlert.LOW -> R.string.low_battery_alert to ctx.getString(R.string.alert_level_reported, sample.levelPercent)
            BatteryAlert.HIGH -> R.string.high_battery_alert to ctx.getString(R.string.alert_level_reported, sample.levelPercent)
            BatteryAlert.FULL -> R.string.charging_complete_alert to ctx.getString(R.string.alert_full_reported)
            BatteryAlert.TEMPERATURE -> {
                val celsius = sample.temperatureDeciC!! / 10.0
                val value = if (settings.temperatureUnitIndex == 1) String.format(Locale.getDefault(), "%.1f °F", celsius * 1.8 + 32)
                    else String.format(Locale.getDefault(), "%.1f °C", celsius)
                R.string.high_temperature to ctx.getString(R.string.alert_temperature_reported, value)
            }
            BatteryAlert.DISCHARGE -> R.string.high_discharge to ctx.getString(R.string.alert_discharge_reported,
                String.format(Locale.getDefault(), "%.0f mA", -sample.currentNowUa!! / 1000.0))
        }
        val content = PendingIntent.getActivity(ctx, 20, Intent(ctx, BatteryMainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(ctx, ALERT_CHANNEL_ID)
            .setContentTitle(ctx.getString(title)).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_warning).setContentIntent(content)
            .setAutoCancel(true).setOnlyAlertOnce(true).setWhen(sample.timestamp)
            .setCategory(NotificationCompat.CATEGORY_STATUS).build()
        // One stable ID per condition; successive observations do not create new notifications.
        ctx.getSystemService(NotificationManager::class.java).notify(1100 + type.ordinal, notification)
    }
}
