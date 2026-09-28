package app.batstats.ui.components.chart

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
        fun create(locale: Locale, zone: TimeZone, use24Hour: Boolean): TimeAxisFormatter {
            fun best(skeleton: String) = DateFormat.getBestDateTimePattern(locale, skeleton)
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

/** The default [TimeAxisFormatter]: current locale, default time zone, system 12/24-hour setting. */
@Composable
fun rememberTimeAxisFormatter(): TimeAxisFormatter {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val use24Hour = DateFormat.is24HourFormat(context)
    return remember(locale, use24Hour) { TimeAxisFormatter.create(locale, TimeZone.getDefault(), use24Hour) }
}
