package app.batstats.battery.drain

import android.content.Context
import app.batstats.R
import app.batstats.battery.data.SessionDrain
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.data.sampling.ChargerType
import app.batstats.battery.measurement.PowerState
import app.batstats.settings.StatusIconValue
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** Why the notification shows an issue line; the details are in the app. */
enum class NotificationIssue { COLLECTION, ADVANCED }

/**
 * What the ongoing notification is built from — the sources Now uses: the calibrated realtime reading with its held
 * estimate, and the open session, of which only a DISCHARGE row counts (the "since unplug" window of Now's panel).
 */
data class NotificationInput(
    val reading: EtaHold.Reading,
    val session: ChargeSession? = null,
    val statusIcon: StatusIconValue = StatusIconValue.STATIC,
    val fahrenheit: Boolean = false,
    val issue: NotificationIssue? = null,
)

/**
 * Everything the notification shows, as text. Also the service's update key: equal content is never re-posted.
 * Collapsed: [headline] with [level] trailing, then [summary]. Expanded: [state] with [level] trailing, the 3×3
 * [cells] (row by row), [footer] and, only when needed, [issue].
 */
data class NotificationContent(
    /** "78% · On battery": the plain-text title (Wear, notification listeners, accessibility fallbacks). */
    val title: String,
    val level: String,
    val headline: String,
    val summary: String?,
    val state: String,
    val cells: List<Cell>,
    val footer: String?,
    val issue: String?,
    /** ≤ 4 glyphs for the status-bar icon, or null for the static icon. */
    val statusIcon: String?,
) {
    data class Cell(val label: String, val value: String)

    /** The viewer's locale and clock formats; [time] and [dateTime] carry [zone]. */
    class Formats(val locale: Locale, val zone: TimeZone, val time: DateFormat, val dateTime: DateFormat)

    /** The pure builder: string resources through [resolve], so JVM tests use the real English templates. */
    class Builder(private val resolve: (Int, Array<out Any>) -> String) {
        constructor(context: Context) : this({ id, arguments -> context.getString(id, *arguments) })

        private fun text(id: Int, vararg arguments: Any) = resolve(id, arguments)

        fun build(input: NotificationInput, formats: Formats): NotificationContent {
            val locale = formats.locale
            val realtime = input.reading.reading
            val sample = realtime.sample
            val power = realtime.powerState
            val session = input.session?.takeIf { it.type == SessionType.DISCHARGE }
            val drain = session?.let { SessionDrain.of(it, fullUah = null) }
            val eta = input.reading.remainingMs
            val stateText = text(stateLabel(power, realtime.level))
            val charger = if (power == PowerState.DISCHARGING) null else ChargerType.of(realtime.plugged)
            val state = charger?.let { text(R.string.notification_state_with_charger, stateText, text(chargerLabel(it))) } ?: stateText
            val level = realtime.level?.let { percent(it.toDouble(), locale) } ?: NO_VALUE
            val powerText = formatPower(realtime.powerMw, locale)
            val temperature = formatTemperature(realtime.temperatureC?.toDouble(), input.fahrenheit, locale)
            val screenOn = formatDrainRate(drain?.screenOn?.currentMa, locale)
            val screenOff = formatDrainRate(drain?.screenOff?.currentMa, locale)
            val summary = when {
                sample == null -> null
                power == PowerState.DISCHARGING -> eta?.let { text(R.string.notification_summary_drain_left, screenOn, screenOff, duration(it, locale)) }
                    ?: text(R.string.notification_summary_drain, screenOn, screenOff)
                power == PowerState.CHARGING && eta != null -> text(R.string.notification_summary_to_full, duration(eta, locale), powerText, temperature)
                else -> text(R.string.notification_summary_state, stateText, powerText, temperature)
            }
            val sessionMah = session?.deltaUah?.takeIf { session.counterCoveredMs > 0 }?.div(1_000.0)
            val cells = listOf(
                NotificationContent.Cell(text(R.string.notification_label_current), formatCurrent(realtime.currentMa, locale)),
                NotificationContent.Cell(text(R.string.notification_label_power), powerText),
                NotificationContent.Cell(text(R.string.notification_label_temperature), temperature),
                NotificationContent.Cell(text(R.string.notification_label_voltage), formatVoltage(realtime.voltageMv, locale)),
                NotificationContent.Cell(text(R.string.notification_label_screen_on), screenOn),
                NotificationContent.Cell(text(R.string.notification_label_screen_off), screenOff),
                NotificationContent.Cell(text(R.string.notification_label_deep_sleep),
                    drain?.deepSleepPercent?.let { percent(it, locale) } ?: NO_VALUE),
                NotificationContent.Cell(text(R.string.notification_label_session),
                    sessionMah?.let { "${formatNumber(it, if (abs(it) < 10) 1 else 0, locale)} mAh" } ?: NO_VALUE),
                NotificationContent.Cell(
                    text(if (power == PowerState.CHARGING) R.string.notification_label_time_to_full else R.string.notification_label_time_left),
                    eta?.takeIf { power == PowerState.CHARGING || power == PowerState.DISCHARGING }?.let { compactDuration(it, locale) } ?: NO_VALUE,
                ),
            )
            val footer = sample?.let {
                val updated = formats.time.format(Date(it.timestamp))
                session?.let { open -> text(R.string.notification_footer, dayAwareTime(open.startTime, it.timestamp, formats), updated) }
                    ?: text(R.string.notification_footer_updated, updated)
            }
            val waiting = text(R.string.notification_waiting)
            return NotificationContent(
                title = if (sample == null) waiting else text(R.string.notification_title, level, state),
                level = level,
                headline = if (sample == null) waiting
                    else text(R.string.notification_headline, formatCurrent(realtime.currentMa, locale), powerText),
                summary = summary,
                state = if (sample == null) waiting else state,
                cells = cells,
                footer = footer,
                issue = when (input.issue) {
                    NotificationIssue.COLLECTION -> text(R.string.notification_issue_collection)
                    NotificationIssue.ADVANCED -> text(R.string.notification_issue_advanced)
                    null -> null
                },
                statusIcon = StatusIconText.of(input.statusIcon, realtime, input.fahrenheit, locale),
            )
        }

        private fun percent(value: Double, locale: Locale) = text(R.string.notification_percent, formatNumber(value, 0, locale))

        /** "5 h 10 min", "45 min", "2 d 3 h", "<1 min". */
        private fun duration(ms: Long, locale: Locale): String {
            val minutes = ms.coerceAtLeast(0) / MINUTE_MS
            val hours = minutes / 60
            fun number(value: Long) = formatNumber(value.toDouble(), 0, locale)
            return when {
                minutes < 1 -> text(R.string.notification_duration_under_minute)
                hours >= 24 -> text(R.string.notification_duration_days_hours, number(hours / 24), number(hours % 24))
                hours >= 1 -> text(R.string.notification_duration_hours_minutes, number(hours), number(minutes % 60))
                else -> text(R.string.notification_duration_minutes, number(minutes))
            }
        }

        /** A grid cell's duration: "5:10 h", or minutes under an hour. */
        private fun compactDuration(ms: Long, locale: Locale): String {
            val minutes = ms.coerceAtLeast(0) / MINUTE_MS
            return when {
                minutes < 1 -> text(R.string.notification_duration_under_minute)
                minutes < 60 -> text(R.string.notification_duration_minutes, formatNumber(minutes.toDouble(), 0, locale))
                else -> text(R.string.notification_duration_compact_hours,
                    formatNumber((minutes / 60).toDouble(), 0, locale) + ":" + String.format(locale, "%02d", minutes % 60))
            }
        }

        /** The time alone on [referenceMs]'s day; with the date when the session began on an earlier day. */
        private fun dayAwareTime(ms: Long, referenceMs: Long, formats: Formats): String {
            fun day(at: Long) = Calendar.getInstance(formats.zone).apply { timeInMillis = at }.let {
                it.get(Calendar.ERA) to it.get(Calendar.YEAR) * 1_000 + it.get(Calendar.DAY_OF_YEAR)
            }
            return (if (day(ms) == day(referenceMs)) formats.time else formats.dateTime).format(Date(ms))
        }

        private fun stateLabel(power: PowerState, level: Int?) = when (power) {
            PowerState.CHARGING -> R.string.notification_state_charging
            PowerState.DISCHARGING -> R.string.notification_state_discharging
            PowerState.PLUGGED -> if (level == FULL_LEVEL) R.string.notification_state_full else R.string.notification_state_plugged
            PowerState.UNKNOWN -> R.string.notification_state_unknown
        }

        private fun chargerLabel(charger: ChargerType) = when (charger) {
            ChargerType.AC -> R.string.notification_charger_ac
            ChargerType.USB -> R.string.notification_charger_usb
            ChargerType.WIRELESS -> R.string.notification_charger_wireless
            ChargerType.DOCK -> R.string.notification_charger_dock
        }
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val FULL_LEVEL = 100
    }
}
