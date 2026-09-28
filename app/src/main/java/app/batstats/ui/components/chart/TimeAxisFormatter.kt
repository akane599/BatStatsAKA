package app.batstats.ui.components.chart

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** How precise a time label is; picked from the tick step (axes) or the window span (scrub readout). */
enum class TimeGranularity { SECONDS, MINUTES, HOURS, DAYS, MONTHS, DATE_TIME }

/**
 * Locale-aware time labels in [zone], following the system 12/24-hour setting. Build it with
 * [rememberTimeAxisFormatter]; not thread-safe (use it from composition and draw caches only).
 */
@Stable
class TimeAxisFormatter internal constructor(
    val zone: TimeZone,
    private val locale: Locale,
    private val patterns: Map<TimeGranularity, String>,
    private val axisMinutesPattern: String,
) {
    private var axisMinutes: SimpleDateFormat? = null
    private val formats = HashMap<TimeGranularity, SimpleDateFormat>()
    private val date = Date(0)

    /** [timeMs] at [granularity]. */
    fun format(timeMs: Long, granularity: TimeGranularity): String {
        val format = formats.getOrPut(granularity) {
            SimpleDateFormat(patterns.getValue(granularity), locale).also { it.timeZone = zone }
        }
        date.time = timeMs
        return format.format(date)
    }

    /**
     * An axis tick label: like [format], but a local-midnight tick on an hour/minute axis shows the date, and
     * minute ticks drop AM/PM ("8:45") so more fit; hour ticks keep it ("9 AM").
     */
    fun axisLabel(timeMs: Long, granularity: TimeGranularity): String {
        val atMidnight = Math.floorMod(timeMs + zone.getOffset(timeMs), ChartMath.DAY_MS) == 0L
        if (atMidnight && (granularity == TimeGranularity.HOURS || granularity == TimeGranularity.MINUTES)) {
            return format(timeMs, TimeGranularity.DAYS)
        }
        if (granularity != TimeGranularity.MINUTES) return format(timeMs, granularity)
        val format = axisMinutes ?: SimpleDateFormat(axisMinutesPattern, locale).also {
            it.timeZone = zone
            axisMinutes = it
        }
        date.time = timeMs
        return format.format(date)
    }

    companion object {
        /** Patterns from CLDR skeletons: "09:00"/"9 AM" hour ticks, "Oct 9" days, "Oct" months. */
        fun create(locale: Locale, zone: TimeZone, use24Hour: Boolean): TimeAxisFormatter =
            create(locale, zone, use24Hour, DateFormat::getBestDateTimePattern)

        /** [create] with the skeleton → pattern lookup injected (JVM tests have no ICU skeleton data). */
        internal fun create(
            locale: Locale,
            zone: TimeZone,
            use24Hour: Boolean,
            bestPattern: (Locale, String) -> String,
        ): TimeAxisFormatter {
            fun best(skeleton: String) = bestPattern(locale, skeleton)
            val hour = if (use24Hour) "H" else "h"
            val patterns = mapOf(
                TimeGranularity.SECONDS to best("${hour}ms"),
                TimeGranularity.MINUTES to best("${hour}m"),
                TimeGranularity.HOURS to if (use24Hour) best("Hm") else best("ha"),
                TimeGranularity.DAYS to best("MMMd"),
                TimeGranularity.MONTHS to best("MMM"),
                TimeGranularity.DATE_TIME to best("MMMd${hour}m"),
            )
            val axisMinutes = if (use24Hour) patterns.getValue(TimeGranularity.MINUTES) else withoutDayPeriod(best("hm"))
            return TimeAxisFormatter(zone, locale, patterns, axisMinutes)
        }

        /** [pattern] without its AM/PM field (unquoted a/b/B) and the space next to it. */
        internal fun withoutDayPeriod(pattern: String): String {
            val out = StringBuilder()
            var quoted = false
            for (c in pattern) {
                if (c == '\'') quoted = !quoted
                if (!quoted && (c == 'a' || c == 'b' || c == 'B')) continue
                out.append(c)
            }
            return out.toString().trim { it.isWhitespace() || it == '\u202F' || it == '\u00A0' }
        }
    }
}

/**
 * The default [TimeAxisFormatter]: current locale, default time zone, system 12/24-hour setting. It is rebuilt when
 * any of the three changes: the locale through the Configuration, the zone and the 12/24-hour setting through the
 * system's time-zone / time-changed broadcasts (neither is part of the Configuration), so a chart that nothing else
 * recomposes still re-formats.
 */
@Composable
fun rememberTimeAxisFormatter(): TimeAxisFormatter {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    timeSettingsRevision() // subscribes: a zone or 12/24-hour change recomposes this, and the reads below see it
    val use24Hour = DateFormat.is24HourFormat(context)
    val zone = TimeZone.getDefault()
    return remember(locale, use24Hour, zone.id) { TimeAxisFormatter.create(locale, zone, use24Hour) }
}

/**
 * Bumped on `ACTION_TIMEZONE_CHANGED` and `ACTION_TIME_CHANGED` (which the system also sends when the 12/24-hour
 * setting changes). Previews have no broadcasts and skip the receiver.
 */
@Composable
private fun timeSettingsRevision(): Int {
    var revision by remember { mutableIntStateOf(0) }
    if (!LocalInspectionMode.current) {
        val context = LocalContext.current
        DisposableEffect(context) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    revision++
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            onDispose { context.unregisterReceiver(receiver) }
        }
    }
    return revision
}
