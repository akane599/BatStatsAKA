package app.batstats.battery.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import app.batstats.R
import app.batstats.battery.BatteryGraph
import app.batstats.battery.mainActivityIntent
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.measurement.BatteryReading
import app.batstats.battery.measurement.EtaHold
import app.batstats.battery.measurement.PowerState
import app.batstats.battery.util.TimeEstimator
import app.batstats.ui.format.percentText
import app.batstats.settings.useFahrenheit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import android.text.format.DateFormat as AndroidDateFormat

/** Keep the short date, but choose the clock fields from the system preference rather than the locale. */
internal fun widgetFreshnessPattern(
    is24h: Boolean,
    locale: Locale,
    bestPattern: (Locale, String) -> String,
): String = bestPattern(locale, if (is24h) "yMdHm" else "yMdhm")

/**
 * Widgets have no periodic alarm: the monitoring service pushes gated updates, and host requests
 * ([refresh]) take one fresh reading that is never saved.
 */
object WidgetUpdater {
    const val ACTION_REFRESH = "app.batstats.battery.widget.ACTION_REFRESH"
    internal const val OPEN_APP_REQUEST_CODE = 22

    /** The widgets' visible text; the service's update gate compares it to skip unchanged pushes. */
    data class Content(
        val level: String,
        val temperature: String,
        val estimate: String,
        val caption: String,
        val direction: Direction,
    )

    /**
     * Icon accent for the level and time widgets: energy direction, mirroring
     * `ui/screens/now/NowHero.directionColor`. The temperature widget ignores this and always uses [HEAT].
     */
    enum class Direction(val colorRes: Int) {
        CHARGE(R.color.widget_charge),
        DRAIN(R.color.widget_drain),
        HEAT(R.color.widget_heat),
        NEUTRAL(R.color.widget_on_surface_variant),
    }

    /** At or below this level while discharging, the icon turns to [Direction.HEAT] (mirrors NowHero.LOW_LEVEL). */
    internal const val LOW_BATTERY_LEVEL = 15

    internal fun directionFor(power: PowerState, level: Int?): Direction = when (power) {
        PowerState.CHARGING, PowerState.PLUGGED -> Direction.CHARGE
        PowerState.DISCHARGING -> if (level != null && level <= LOW_BATTERY_LEVEL) Direction.HEAT else Direction.DRAIN
        PowerState.UNKNOWN -> Direction.NEUTRAL
    }

    private val providers = listOf(BatteryLevelWidget::class.java, BatteryTempWidget::class.java, BatteryTimeWidget::class.java)

    @Volatile private var lastFahrenheit = false

    private val widgetIds = WidgetIdCache<Class<out AppWidgetProvider>>()

    /** Called from each provider's onUpdate/onDeleted/onEnabled/onDisabled: its placed widgets changed. */
    fun invalidate(provider: Class<out AppWidgetProvider>) = widgetIds.invalidate(provider)

    /**
     * The monitoring service's widget stream: each capture with its held estimate ([EtaHold], as the notification
     * holds it). Realtime carries every capture twice, raw (no discharge estimate) and then the writer's copy; without
     * the hold the time widget would flash "—" and push twice per capture. Readings without a sample are skipped.
     */
    fun readings(realtime: Flow<BatteryRepository.Realtime>): Flow<EtaHold.Reading> = realtime
        .scan(EtaHold.Reading()) { held, reading -> EtaHold.next(held, reading) }
        .drop(1)
        .filter { it.reading.sample != null }

    /** A host request: one fresh reading within the receiver's goAsync window; a failure never reaches the process. */
    fun refresh(context: Context, pending: BroadcastReceiver.PendingResult) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                withTimeoutOrNull(REFRESH_TIMEOUT_MS) {
                    val repository = BatteryGraph.repo
                    val fahrenheit = try {
                        withTimeoutOrNull(SETTINGS_TIMEOUT_MS) { repository.getSettings().useFahrenheit } ?: lastFahrenheit
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { lastFahrenheit }
                    // Read last, so the push follows the capture in the sampler's queue order; falls back to the last
                    // realtime value. The fresh capture carries no estimate: the service's last one is held across it.
                    val fresh = withTimeoutOrNull(READ_TIMEOUT_MS) { repository.readOnce() } ?: repository.realtimeFlow.value
                    val reading = EtaHold.next(EtaHold.next(EtaHold.Reading(), repository.realtimeFlow.value), fresh)
                    deliver(appContext, content(appContext, reading, repository.isMonitoringFlow.value, fahrenheit))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Widget refresh failed (${e.javaClass.simpleName})")
            } finally {
                pending.finish()
            }
        }
    }

    fun push(context: Context, sample: BatterySample?, monitoring: Boolean = true, fahrenheit: Boolean = lastFahrenheit) =
        deliver(context, content(context, EtaHold.next(EtaHold.Reading(), BatteryRepository.Realtime(sample)), monitoring, fahrenheit))

    fun content(context: Context, reading: EtaHold.Reading, monitoring: Boolean, fahrenheit: Boolean): Content {
        lastFahrenheit = fahrenheit // For pushes that don't pass it, e.g. the service's final paused push.
        val sample = reading.reading.sample
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val freshness = sample?.timestamp?.let {
            val pattern = widgetFreshnessPattern(
                AndroidDateFormat.is24HourFormat(context),
                locale,
                AndroidDateFormat::getBestDateTimePattern,
            )
            SimpleDateFormat(pattern, locale).format(Date(it))
        } ?: context.getString(R.string.widget_no_reading)
        return Content(
            level = sample?.levelPercent?.let { percentText(it.toDouble(), locale) { id, args -> context.getString(id, *args) } } ?: "—",
            temperature = sample?.temperatureDeciC?.let {
                if (fahrenheit) String.format(Locale.getDefault(), "%.1f °F", it / 10.0 * 1.8 + 32)
                else String.format(Locale.getDefault(), "%.1f °C", it / 10.0)
            } ?: "—",
            estimate = if (monitoring) TimeEstimator.etaString(context, reading, locale) ?: "—" else "—",
            caption = if (monitoring) context.getString(R.string.widget_read_at, freshness)
                else context.getString(R.string.widget_paused_at, freshness),
            direction = sample?.let { directionFor(BatteryReading.powerState(it.status, it.plugged), it.levelPercent) }
                ?: Direction.NEUTRAL,
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
            // Temperature isn't an energy direction: it always reads as heat (matches ChartColors.temperature).
            val icon = when (provider) {
                BatteryLevelWidget::class.java -> R.drawable.ic_battery
                BatteryTempWidget::class.java -> R.drawable.ic_thermometer
                else -> R.drawable.ic_time
            }
            val tint = if (provider == BatteryTempWidget::class.java) R.color.widget_heat else content.direction.colorRes
            val views = RemoteViews(context.packageName, R.layout.widget_common).apply {
                setTextViewText(R.id.title, context.getString(title))
                setTextViewText(R.id.value, value)
                setImageViewResource(R.id.icon, icon)
                setInt(R.id.icon, "setColorFilter", ContextCompat.getColor(context, tint))
                setViewVisibility(R.id.subtitle, View.VISIBLE)
                setTextViewText(R.id.subtitle, content.caption)
                setContentDescription(R.id.root, "${context.getString(title)}: $value. ${content.caption}")
                setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(context, OPEN_APP_REQUEST_CODE,
                    mainActivityIntent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            manager.updateAppWidget(ids, views)
        }
    }

    fun showPlaceholder(context: Context) = push(context, null, monitoring = false)

    private const val LOG_TAG = "WidgetUpdater"
    // A receiver's goAsync window is about 10 s: settings then the reading, all of it capped.
    private const val SETTINGS_TIMEOUT_MS = 2_000L
    private const val READ_TIMEOUT_MS = 6_000L
    private const val REFRESH_TIMEOUT_MS = 9_000L
}
