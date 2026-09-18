package app.batstats.battery.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import app.batstats.R
import app.batstats.battery.BatteryGraph
import app.batstats.battery.BatteryMainActivity
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.util.TimeEstimator
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException

/** Widgets have no periodic alarm; service updates and explicit host requests supply snapshots. */
object WidgetUpdater {
    const val ACTION_REFRESH = "app.batstats.battery.widget.ACTION_REFRESH"

    private var lastFahrenheit = false

    fun refresh(context: Context, pending: BroadcastReceiver.PendingResult) {
        BatteryGraph.repo.refreshNow { reading ->
            try {
                val fahrenheit = try { BatteryGraph.repo.getSettings().temperatureUnitIndex == 1 }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { lastFahrenheit }
                push(context, reading.sample, BatteryGraph.repo.isMonitoringFlow.value, fahrenheit)
            }
            finally { pending.finish() }
        }
    }
    fun push(context: Context, sample: BatterySample?, monitoring: Boolean = true, fahrenheit: Boolean = lastFahrenheit) {
        lastFahrenheit = fahrenheit
        val manager = AppWidgetManager.getInstance(context)
        val providers = listOf(BatteryLevelWidget::class.java, BatteryTempWidget::class.java, BatteryTimeWidget::class.java)
        for (provider in providers) {
            val ids = manager.getAppWidgetIds(ComponentName(context, provider))
            if (ids.isEmpty()) continue
            val (title, value) = when (provider) {
                BatteryLevelWidget::class.java -> R.string.widget_battery to (sample?.levelPercent?.let { "$it%" } ?: "—")
                BatteryTempWidget::class.java -> R.string.widget_temperature to (sample?.temperatureDeciC?.let {
                    if (fahrenheit) String.format(Locale.getDefault(), "%.1f °F", it / 10.0 * 1.8 + 32)
                    else String.format(Locale.getDefault(), "%.1f °C", it / 10.0)
                } ?: "—")
                else -> R.string.widget_estimate to (if (monitoring) TimeEstimator.etaString(sample) ?: "—" else "—")
            }
            val freshness = sample?.timestamp?.let {
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
            } ?: context.getString(R.string.widget_no_reading)
            val caption = if (monitoring) context.getString(R.string.widget_read_at, freshness)
                else context.getString(R.string.widget_paused_at, freshness)
            val views = RemoteViews(context.packageName, R.layout.widget_common).apply {
                setTextViewText(R.id.title, context.getString(title))
                setTextViewText(R.id.value, value)
                setViewVisibility(R.id.subtitle, View.VISIBLE)
                setTextViewText(R.id.subtitle, caption)
                setContentDescription(R.id.root, "${context.getString(title)}: $value. $caption")
                setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(context, 0,
                    Intent(context, BatteryMainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            manager.updateAppWidget(ids, views)
        }
    }
    fun showPlaceholder(context: Context) = push(context, null, monitoring = false)
    fun requestRefresh(context: Context) { context.sendBroadcast(Intent(ACTION_REFRESH).setPackage(context.packageName)) }
}
