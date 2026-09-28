package app.batstats.battery.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
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
import app.batstats.settings.useFahrenheit
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Widgets have no periodic alarm: the monitoring service pushes gated updates, and host requests
 * ([refresh]) take one fresh reading that is never saved.
 */
object WidgetUpdater {
    const val ACTION_REFRESH = "app.batstats.battery.widget.ACTION_REFRESH"

    /** The widgets' visible text; the service's update gate compares it to skip unchanged pushes. */
    data class Content(val level: String, val temperature: String, val estimate: String, val caption: String)

    private val providers = listOf(BatteryLevelWidget::class.java, BatteryTempWidget::class.java, BatteryTimeWidget::class.java)

    @Volatile private var lastFahrenheit = false

    private val widgetIds = WidgetIdCache<Class<out AppWidgetProvider>>()

    /** Called from each provider's onUpdate/onDeleted/onEnabled/onDisabled: its placed widgets changed. */
    fun invalidate(provider: Class<out AppWidgetProvider>) = widgetIds.invalidate(provider)

    fun refresh(context: Context, pending: BroadcastReceiver.PendingResult) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                val repository = BatteryGraph.repo
                val fahrenheit = try { repository.getSettings().useFahrenheit }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { lastFahrenheit }
                // Read last, so the push follows the capture in the sampler's queue order. Stays within
                // the receiver's goAsync window, falling back to the last realtime value.
                val reading = withTimeoutOrNull(REFRESH_TIMEOUT_MS) { repository.readOnce() } ?: repository.realtimeFlow.value
                push(appContext, reading.sample, repository.isMonitoringFlow.value, fahrenheit)
            }
            finally { pending.finish() }
        }
    }

    fun push(context: Context, sample: BatterySample?, monitoring: Boolean = true, fahrenheit: Boolean = lastFahrenheit) =
        deliver(context, content(context, sample, monitoring, fahrenheit))

    fun content(context: Context, sample: BatterySample?, monitoring: Boolean, fahrenheit: Boolean): Content {
        lastFahrenheit = fahrenheit // For pushes that don't pass it, e.g. the service's final paused push.
        val freshness = sample?.timestamp?.let {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
        } ?: context.getString(R.string.widget_no_reading)
        return Content(
            level = sample?.levelPercent?.let { "$it%" } ?: "—",
            temperature = sample?.temperatureDeciC?.let {
                if (fahrenheit) String.format(Locale.getDefault(), "%.1f °F", it / 10.0 * 1.8 + 32)
                else String.format(Locale.getDefault(), "%.1f °C", it / 10.0)
            } ?: "—",
            estimate = if (monitoring) TimeEstimator.etaString(context, sample) ?: "—" else "—",
            caption = if (monitoring) context.getString(R.string.widget_read_at, freshness)
                else context.getString(R.string.widget_paused_at, freshness),
        )
    }

    fun deliver(context: Context, content: Content) {
        val manager = AppWidgetManager.getInstance(context)
        for (provider in providers) {
            val ids = widgetIds.get(provider) { manager.getAppWidgetIds(ComponentName(context, provider)) }
            if (ids.isEmpty()) continue
            val (title, value) = when (provider) {
                BatteryLevelWidget::class.java -> R.string.widget_battery to content.level
                BatteryTempWidget::class.java -> R.string.widget_temperature to content.temperature
                else -> R.string.widget_estimate to content.estimate
            }
            val views = RemoteViews(context.packageName, R.layout.widget_common).apply {
                setTextViewText(R.id.title, context.getString(title))
                setTextViewText(R.id.value, value)
                setViewVisibility(R.id.subtitle, View.VISIBLE)
                setTextViewText(R.id.subtitle, content.caption)
                setContentDescription(R.id.root, "${context.getString(title)}: $value. ${content.caption}")
                setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(context, 0,
                    Intent(context, BatteryMainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            manager.updateAppWidget(ids, views)
        }
    }

    fun showPlaceholder(context: Context) = push(context, null, monitoring = false)

    private const val REFRESH_TIMEOUT_MS = 8_000L
}
