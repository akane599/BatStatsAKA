package app.batstats.battery.drain

import android.content.Context
import app.batstats.R
import app.batstats.battery.data.SessionDrain
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.data.sampling.ChargerType
import app.batstats.battery.measurement.EtaHold
import app.batstats.battery.measurement.PowerState
import app.batstats.settings.StatusIconValue
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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
 *
 * A reading must never be clipped, so the slots whose text can outgrow a narrow shade at a large font hold their
 * forms longest first; [NotificationFitter] shows the first that fits. An empty list hides the slot.
 */
data class NotificationContent(
    /** "78% · On battery": the plain-text title (Wear, notification listeners, accessibility fallbacks). */
    val title: String,
    val level: String,
    val headline: List<String>,
    val summary: List<String>,
    val state: List<String>,
    val cells: List<Cell>,
    val footer: List<String>,
    val issue: List<String>,
    /** ≤ 4 glyphs for the status-bar icon, or null for the static icon. */
    val statusIcon: String?,
) {
    /** A grid cell: its label and its value's forms, longest first (e.g. "−1,240 mA", then "−1.24 A"). */
    data class Cell(val label: String, val values: List<Quantity>)

    /** The viewer's locale and clock formats; [time] and [dateTime] carry [zone]. */
    class Formats(val locale: Locale, val zone: TimeZone, val time: DateFormat, val dateTime: DateFormat)

    /** The pure builder: string resources through [resolve], so JVM tests use the real English templates. */
    class Builder(private val resolve: (Int, Array<out Any>) -> String) {
        // No arguments: getString(id) skips the formatter, so a literal "%" in a label (es "Al 100 %") stays text.
        constructor(context: Context) : this({ id, arguments ->
            if (arguments.isEmpty()) context.getString(id) else context.getString(id, *arguments)
        })

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
            val level = realtime.level?.let { percent(it.toDouble(), locale) } ?: NO_VALUE
            val powerValue = powerQuantity(realtime.powerMw, locale)
            val temperature = formatTemperature(realtime.temperatureC?.toDouble(), input.fahrenheit, locale)
            val onMa = drain?.screenOn?.currentMa
            val offMa = drain?.screenOff?.currentMa
            val screenOn = formatDrainRate(onMa, locale)
            val screenOff = formatDrainRate(offMa, locale)
            val summary = when {
                sample == null -> emptyList()
                power == PowerState.DISCHARGING && eta != null -> listOf(
                    text(R.string.notification_summary_drain_left, screenOn, screenOff, duration(eta, locale)),
                    text(R.string.notification_summary_drain_left, screenOn, screenOff, compactDuration(eta, locale).toString()),
                    text(R.string.notification_summary_drain_left_short, screenOn, screenOff, compactDuration(eta, locale).toString()),
                    text(R.string.notification_summary_drain_short, screenOn, screenOff),
                    text(R.string.notification_summary_left, compactDuration(eta, locale).toString()),
                )
                power == PowerState.DISCHARGING -> listOf(
                    text(R.string.notification_summary_drain, screenOn, screenOff),
                    text(R.string.notification_summary_drain_short, screenOn, screenOff),
                )
                power == PowerState.CHARGING && eta != null -> listOf(
                    text(R.string.notification_summary_to_full, duration(eta, locale), temperature),
                    text(R.string.notification_summary_to_full, compactDuration(eta, locale).toString(), temperature),
                )
                else -> listOf(
                    text(R.string.notification_summary_state, stateText, temperature),
                    text(R.string.notification_summary_state, text(shortStateLabel(power, realtime.level)), temperature),
                )
            }.shorterForms()
            val state = listOfNotNull(
                charger?.let { text(R.string.notification_state_with_charger, stateText, text(chargerLabel(it))) },
                stateText,
                text(shortStateLabel(power, realtime.level)),
            ).shorterForms()
            val sessionMah = session?.deltaUah?.takeIf { session.counterCoveredMs > 0 }?.div(1_000.0)
            val cells = listOf(
                cell(R.string.notification_label_current, currentQuantity(realtime.currentMa, locale), currentAmpsQuantity(realtime.currentMa, locale)),
                cell(R.string.notification_label_power, powerValue),
                cell(R.string.notification_label_temperature, temperatureQuantity(realtime.temperatureC?.toDouble(), input.fahrenheit, locale)),
                cell(R.string.notification_label_voltage, voltageQuantity(realtime.voltageMv, locale)),
                cell(R.string.notification_label_screen_on, drainRateQuantity(onMa, locale), drainAmpsQuantity(onMa, locale)),
                cell(R.string.notification_label_screen_off, drainRateQuantity(offMa, locale), drainAmpsQuantity(offMa, locale)),
                cell(R.string.notification_label_deep_sleep, drain?.deepSleepPercent?.let { Quantity(percent(it, locale)) }),
                cell(R.string.notification_label_session, sessionChargeQuantity(sessionMah, locale), sessionChargeAhQuantity(sessionMah, locale)),
                cell(
                    if (power == PowerState.CHARGING) R.string.notification_label_time_to_full else R.string.notification_label_time_left,
                    eta?.takeIf { power == PowerState.CHARGING || power == PowerState.DISCHARGING }?.let { compactDuration(it, locale) },
                ),
            )
            val footer = sample?.let {
                val updated = formats.time.format(Date(it.timestamp))
                session?.let { open ->
                    val since = dayAwareTime(open.startTime, it.timestamp, formats)
                    listOf(
                        text(R.string.notification_footer, since, updated),
                        text(R.string.notification_footer_range, since, updated),
                        text(R.string.notification_footer_since, since),
                    )
                } ?: listOf(text(R.string.notification_footer_updated, updated))
            }.orEmpty().shorterForms()
            val waiting = text(R.string.notification_waiting)
            val headline = listOf(
                text(R.string.notification_headline, formatCurrent(realtime.currentMa, locale), powerValue?.toString() ?: NO_VALUE),
                text(R.string.notification_headline, currentAmpsQuantity(realtime.currentMa, locale)?.toString() ?: NO_VALUE,
                    powerValue?.toString() ?: NO_VALUE),
            ).shorterForms()
            return NotificationContent(
                title = if (sample == null) waiting else text(R.string.notification_title, level, state.first()),
                level = level,
                headline = if (sample == null) listOf(waiting) else headline,
                summary = summary,
                state = if (sample == null) listOf(waiting) else state,
                cells = cells,
                footer = footer,
                issue = when (input.issue) {
                    NotificationIssue.COLLECTION -> listOf(R.string.notification_issue_collection,
                        R.string.notification_issue_collection_short, R.string.notification_issue_collection_minimal)
                    NotificationIssue.ADVANCED -> listOf(R.string.notification_issue_advanced,
                        R.string.notification_issue_advanced_short, R.string.notification_issue_advanced_minimal)
                    null -> emptyList()
                }.map { text(it) }.shorterForms(),
                statusIcon = StatusIconText.of(input.statusIcon, realtime, input.fahrenheit, locale),
            )
        }

        /** A cell with its value's forms, longest first; a missing value is "—". */
        private fun cell(label: Int, vararg values: Quantity?) =
            NotificationContent.Cell(text(label), values.filterNotNull().shorterForms().ifEmpty { listOf(Quantity.NONE) })

        /**
         * Keeps a form only if it is shorter than the one kept before it: the fitter tries them in order, so a form
         * that is no shorter (e.g. "−0.612 A" after "−612 mA") could never be the one that fits.
         */
        private fun <T : Any> List<T>.shorterForms(): List<T> = fold(emptyList()) { kept, form ->
            if (kept.isEmpty() || form.toString().length < kept.last().toString().length) kept + form else kept
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

        /** A grid cell's duration: "5:10" h, or minutes under an hour ("45" min, "<1" min). */
        private fun compactDuration(ms: Long, locale: Locale): Quantity {
            val minutes = ms.coerceAtLeast(0) / MINUTE_MS
            return when {
                minutes < 1 -> Quantity("<1", text(R.string.notification_unit_minutes))
                minutes < 60 -> Quantity(formatNumber(minutes.toDouble(), 0, locale), text(R.string.notification_unit_minutes))
                else -> Quantity(formatNumber((minutes / 60).toDouble(), 0, locale) + ":" + String.format(locale, "%02d", minutes % 60),
                    text(R.string.notification_unit_hours))
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

        /** The shortest form of a state, for the narrowest shade: only "Plugged in, not charging" has one. */
        private fun shortStateLabel(power: PowerState, level: Int?) =
            if (power == PowerState.PLUGGED && level != FULL_LEVEL) R.string.notification_state_plugged_short else stateLabel(power, level)

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
