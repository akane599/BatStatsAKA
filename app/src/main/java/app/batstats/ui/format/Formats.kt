package app.batstats.ui.format

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import app.batstats.R
import app.batstats.ui.components.StatCellDefaults
import app.batstats.ui.components.chart.MINUS_SIGN
import app.batstats.ui.components.chart.TimeAxisFormatter
import app.batstats.ui.components.chart.TimeGranularity
import app.batstats.ui.components.chart.formatWithMinus
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

// Number, duration and time formatting shared by the screens (Now, History, SessionDetails, Health, Apps, Settings,
// Status). Screen-specific labels stay private to their screen. The duration/unit strings keep their `now_*` keys.

private const val MINUTE_MS = 60_000L
private val PLACEHOLDER = Regex("%(\\d+)\\$[sd]|%%")

@Composable
@ReadOnlyComposable
internal fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

private fun numberFormat(decimals: Int, locale: Locale): NumberFormat = NumberFormat.getNumberInstance(locale).apply {
    minimumFractionDigits = decimals
    maximumFractionDigits = decimals
}

/** Grouped, with exactly [decimals] fraction digits; negatives with the typographic minus ([MINUS_SIGN]). */
internal fun formatNumber(value: Double, decimals: Int, locale: Locale): String = numberFormat(decimals, locale).formatWithMinus(value)

/** "+1,450" into the battery, "−412" out of it, a bare "0" when it rounds to zero. */
internal fun formatSigned(value: Double, decimals: Int, locale: Locale): String {
    val format = numberFormat(decimals, locale)
    val text = format.formatWithMinus(value)
    return if (value > 0 && text != format.format(0.0)) "+$text" else text
}

/** mAh with one decimal below 10, so small users don't all read "0". */
internal fun formatMah(value: Double, locale: Locale): String = formatNumber(value, if (abs(value) < 10) 1 else 0, locale)

/** A number and its unit as one string ("812 mAh", "1:24 h"): the one join every screen uses. */
@Composable
internal fun valueWithUnit(value: String, unit: String): String = stringResource(R.string.value_unit, value, unit)

/** "812 mAh" ([formatMah] + the unit), for lists, captions and sentences. */
@Composable
internal fun mahText(value: Double): String = valueWithUnit(formatMah(value, currentLocale()), stringResource(R.string.now_unit_mah))

/**
 * A percentage the locale's way, from the one `percent_value` template: "94%", Spanish "94 %", Turkish "%94". Every
 * percent on screen goes through it (text) or [percentUnit] (a StatCell's value + unit).
 */
@Composable
internal fun formatPercent(value: Double, decimals: Int = 0): String =
    stringResource(R.string.percent_value, formatNumber(value, decimals, currentLocale()))

/** The "%" sign as a StatCell unit and whether this locale writes it first ([StatCellDefaults]; `unitFirst`). */
internal class PercentUnit(val sign: String, val first: Boolean)

@Composable
internal fun percentUnit(): PercentUnit = percentUnitOf(stringResource(R.string.percent_value))

/** The sign and its side from a `percent_value` template (en "%1$s%%", tr "%%%1$s"): pure, for tests. */
internal fun percentUnitOf(template: String): PercentUnit = PercentUnit("%", first = template.trimStart().startsWith("%%"))

/** A percentage with the number in the surrounding style and the sign in [rest] (quieter), in the locale's order. */
@Composable
internal fun percentAnnotated(number: String, rest: SpanStyle): AnnotatedString =
    styledTemplate(stringResource(R.string.percent_value), listOf(number), rest)

/** Small rates keep a second decimal so a real drain never reads as 0.0. */
internal fun formatRate(value: Double, locale: Locale): String = formatNumber(value, if (abs(value) < 1) 2 else 1, locale)

/** A duration as a string resource and its numbers: whole minutes, hours + minutes, or days + hours. */
internal class DurationText(val template: Int, val numbers: List<Long>)

internal fun durationText(ms: Long): DurationText {
    val minutes = ms.coerceAtLeast(0) / MINUTE_MS
    val hours = minutes / 60
    return when {
        minutes < 1 -> DurationText(R.string.now_duration_under_minute, emptyList())
        hours >= 24 -> DurationText(R.string.now_duration_days_hours, listOf(hours / 24, hours % 24))
        hours >= 1 -> DurationText(R.string.now_duration_hours_minutes, listOf(hours, minutes % 60))
        else -> DurationText(R.string.now_duration_minutes, listOf(minutes))
    }
}

/** A duration for a [app.batstats.ui.components.StatCell]: "2:10" with the hours unit, or "45" with the minutes one. */
internal class CompactDuration(val value: String, val unit: Int)

internal fun compactDuration(ms: Long, locale: Locale): CompactDuration {
    val minutes = ms.coerceAtLeast(0) / MINUTE_MS
    return if (minutes >= 60) {
        val hours = formatNumber((minutes / 60).toDouble(), 0, locale)
        CompactDuration("$hours:${String.format(locale, "%02d", minutes % 60)}", R.string.now_unit_hours)
    } else {
        CompactDuration(formatNumber(minutes.toDouble(), 0, locale), R.string.now_unit_minutes)
    }
}

/** "5 h 40 min" as plain text. */
@Composable
internal fun durationString(ms: Long): String {
    val text = durationText(ms)
    val locale = currentLocale()
    return stringResource(text.template, *text.numbers.map { formatNumber(it.toDouble(), 0, locale) }.toTypedArray())
}

/** "5 h 40 min" with the numbers in the surrounding style and everything else in [rest] (quieter units). */
@Composable
internal fun durationAnnotated(ms: Long, rest: SpanStyle): AnnotatedString {
    val text = durationText(ms)
    val locale = currentLocale()
    return styledTemplate(stringResource(text.template), text.numbers.map { formatNumber(it.toDouble(), 0, locale) }, rest)
}

/**
 * Fills a raw string-resource [template] ("%1$s h %2$s min", "%%%1$s") with [args]; the arguments keep the text's
 * own style and the literal parts (units, words, "%") get [rest]. Keeps each locale's word order.
 */
internal fun styledTemplate(template: String, args: List<String>, rest: SpanStyle): AnnotatedString = buildAnnotatedString {
    var index = 0
    PLACEHOLDER.findAll(template).forEach { match ->
        if (match.range.first > index) withStyle(rest) { append(template.substring(index, match.range.first)) }
        if (match.value == "%%") {
            withStyle(rest) { append('%') }
        } else {
            append(args.getOrElse(match.groupValues[1].toInt() - 1) { "" })
        }
        index = match.range.last + 1
    }
    if (index < template.length) withStyle(rest) { append(template.substring(index)) }
}

/** The quieter span for units and words next to a number in [style]. */
@Composable
internal fun unitSpan(style: TextStyle): SpanStyle = SpanStyle(
    fontSize = if (style.fontSize != TextUnit.Unspecified) style.fontSize * StatCellDefaults.UnitScale else TextUnit.Unspecified,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** [timeMs] as a time on [referenceMs]'s local day, else with its date ("Oct 8, 6:10 PM"). */
internal fun dayAwareTime(formatter: TimeAxisFormatter, timeMs: Long, referenceMs: Long): String =
    formatter.format(timeMs, if (sameLocalDay(timeMs, referenceMs, formatter.zone)) TimeGranularity.MINUTES else TimeGranularity.DATE_TIME)

internal fun sameLocalDay(firstMs: Long, secondMs: Long, zone: TimeZone): Boolean {
    val first = Calendar.getInstance(zone).apply { timeInMillis = firstMs }
    val second = Calendar.getInstance(zone).apply { timeInMillis = secondMs }
    return first.get(Calendar.ERA) == second.get(Calendar.ERA) &&
        first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
        first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
}
