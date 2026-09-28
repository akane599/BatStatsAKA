package app.batstats.ui.screens.now

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import app.batstats.R
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** Typographic minus: the same width as a tabular digit's neighbours, unlike "-". */
internal const val MINUS = "−"

private const val MINUTE_MS = 60_000L
private val PLACEHOLDER = Regex("%(\\d+)\\$[sd]|%%")

@Composable
@ReadOnlyComposable
internal fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

/** Grouped, with exactly [decimals] fraction digits. */
internal fun formatNumber(value: Double, decimals: Int, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = decimals
        maximumFractionDigits = decimals
    }.format(value)

/** "+1,450" into the battery, "−412" out of it, a bare "0" when it rounds to zero. */
internal fun formatSigned(value: Double, decimals: Int, locale: Locale): String {
    val magnitude = formatNumber(abs(value), decimals, locale)
    return when {
        magnitude == formatNumber(0.0, decimals, locale) -> magnitude
        value < 0 -> MINUS + magnitude
        else -> "+$magnitude"
    }
}

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
