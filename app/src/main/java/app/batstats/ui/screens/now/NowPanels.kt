package app.batstats.ui.screens.now

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.batstats.R
import app.batstats.battery.apps.AppUsageBasis
import app.batstats.battery.measurement.CapacityConfidence
import app.batstats.battery.measurement.CurrentCalibration
import app.batstats.battery.measurement.CurrentSign
import app.batstats.battery.measurement.CurrentUnit
import app.batstats.ui.components.AppLabelIcon
import app.batstats.ui.components.AppRow
import app.batstats.ui.components.InfoSheet
import app.batstats.ui.components.Panel
import app.batstats.ui.components.StatCell
import app.batstats.ui.components.displayName
import app.batstats.ui.components.chart.TimeAxisFormatter
import app.batstats.ui.components.chart.TimeGranularity
import app.batstats.ui.components.chart.rememberTimeAxisFormatter
import app.batstats.ui.theme.spacing
import app.batstats.viewmodel.DrainState
import app.batstats.viewmodel.HealthState
import app.batstats.viewmodel.SinceUnplugState
import app.batstats.viewmodel.TodayState
import app.batstats.viewmodel.TopAppsState
import java.util.Calendar
import java.util.TimeZone

private const val TODAY_MAH_TEMPLATE = 8_888.0

/**
 * The on-battery window from one DISCHARGE session row: screen on and screen off as %/h (mA and duration below) and
 * deep sleep. The open session reads "Since unplug · 9:12 AM" with a confirmed Reset; while plugged in (or not
 * monitoring) the newest closed one reads "Last on battery · 6:10–9:20 AM". Times carry a date when not today.
 */
@Composable
internal fun SinceUnplugPanel(
    state: SinceUnplugState?,
    monitoring: Boolean,
    nowMs: Long,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatter = rememberTimeAxisFormatter()
    val title = when {
        state == null -> stringResource(R.string.now_since_title)
        state.current -> stringResource(R.string.now_since_title_at, dayAwareTime(formatter, state.startedAtMs, nowMs))
        else -> stringResource(
            R.string.now_since_title_last,
            dayAwareTime(formatter, state.startedAtMs, nowMs),
            dayAwareTime(formatter, state.endedAtMs, state.startedAtMs),
        )
    }
    Panel(
        modifier,
        title = title,
        trailing = { InfoSheet(stringResource(R.string.now_since_info_title), stringResource(R.string.now_since_info_body)) },
    ) {
        if (state == null) {
            QuietText(stringResource(if (monitoring) R.string.now_since_empty_monitoring else R.string.now_since_empty))
            return@Panel
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
            DrainCell(stringResource(R.string.now_screen_on), state.screenOn, Modifier.weight(1f))
            DrainCell(stringResource(R.string.now_screen_off), state.screenOff, Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatCell(
                stringResource(R.string.now_deep_sleep),
                state.deepSleepPercent?.let { formatNumber(it, 0, currentLocale()) } ?: stringResource(R.string.component_no_value),
                Modifier.weight(1f),
                unit = stringResource(R.string.now_unit_percent),
            )
            // Reset starts a new window; it has nothing to do with a window that already ended.
            if (state.current) TextButton(onClick = onReset) { Text(stringResource(R.string.now_reset)) }
        }
    }
}

/** [timeMs] as a time on [referenceMs]'s local day, else with its date ("Oct 8, 6:10 PM"). */
internal fun dayAwareTime(formatter: TimeAxisFormatter, timeMs: Long, referenceMs: Long): String =
    formatter.format(timeMs, if (sameLocalDay(timeMs, referenceMs, formatter.zone)) TimeGranularity.MINUTES else TimeGranularity.DATE_TIME)

/** %/h when the capacity is known, else the average mA; the other figure and the duration go underneath. */
@Composable
private fun DrainCell(label: String, drain: DrainState, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    val noValue = stringResource(R.string.component_no_value)
    val duration = durationString(drain.durationMs)
    val milliamps = drain.currentMa?.let { formatNumber(it, 0, locale) }
    val perHour = drain.percentPerHour
    if (perHour != null) {
        StatCell(
            label,
            formatRate(perHour, locale),
            modifier,
            unit = stringResource(R.string.now_unit_percent_per_hour),
            supporting = stringResource(R.string.now_drain_supporting, milliamps ?: noValue, duration),
        )
    } else {
        StatCell(label, milliamps ?: noValue, modifier, unit = stringResource(R.string.now_unit_ma), supporting = duration)
    }
}

/** Today's row from the daily summary; the whole panel opens History. */
@Composable
internal fun TodayPanel(today: TodayState?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Panel(modifier, title = stringResource(R.string.now_today_title), trailing = { Chevron() }, onClick = onOpen) {
        if (today == null) {
            QuietText(stringResource(R.string.now_today_empty))
            return@Panel
        }
        val locale = currentLocale()
        val mah = stringResource(R.string.now_unit_mah)
        // One template for both charge cells, so "1,240" and "800" share a size (also at large font).
        val template = formatNumber(TODAY_MAH_TEMPLATE, 0, locale)
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
            StatCell(stringResource(R.string.now_today_used), formatNumber(today.usedMah, 0, locale), Modifier.weight(1f), unit = mah, sizingTemplate = template)
            StatCell(stringResource(R.string.now_today_charged), formatNumber(today.chargedMah, 0, locale), Modifier.weight(1f), unit = mah, sizingTemplate = template)
            val screenOn = compactDuration(today.screenOnMs, locale)
            StatCell(stringResource(R.string.now_today_screen_on), screenOn.value, Modifier.weight(1f), unit = stringResource(screenOn.unit))
        }
    }
}

/** The combined capacity estimate, its confidence and (with a design capacity set) health; opens Health. */
@Composable
internal fun HealthPanel(health: HealthState?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Panel(modifier, title = stringResource(R.string.now_health_title), trailing = { Chevron() }, onClick = onOpen) {
        if (health == null) {
            QuietText(stringResource(R.string.now_health_empty))
            return@Panel
        }
        val locale = currentLocale()
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
            StatCell(
                stringResource(R.string.now_health_capacity),
                formatNumber(health.capacityMah.toDouble(), 0, locale),
                Modifier.weight(1f),
                unit = stringResource(R.string.now_unit_mah),
                supporting = stringResource(confidenceLabel(health.confidence)),
            )
            health.healthPercent?.let { percent ->
                StatCell(
                    stringResource(R.string.now_health_of_design),
                    formatNumber(percent, 0, locale),
                    Modifier.weight(1f),
                    unit = stringResource(R.string.now_unit_percent),
                )
            }
        }
    }
}

private fun confidenceLabel(confidence: CapacityConfidence): Int = when (confidence) {
    CapacityConfidence.LOW -> R.string.now_health_confidence_low
    CapacityConfidence.MEDIUM -> R.string.now_health_confidence_medium
    CapacityConfidence.HIGH -> R.string.now_health_confidence_high
}

/**
 * The top apps from the last cached dump (never a new one): icon · label · mAh · share, each opening its details.
 * With no data, a quiet line and a link to Apps, which fetches on demand.
 */
@Composable
internal fun TopAppsPanel(
    apps: TopAppsState,
    nowMs: Long,
    onOpenApps: () -> Unit,
    onOpenApp: (uid: Int, packageName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    Panel(
        modifier,
        title = stringResource(R.string.now_apps_title),
        trailing = if (apps is TopAppsState.Ready) {
            { TextButton(onClick = onOpenApps) { Text(stringResource(R.string.now_apps_all)) } }
        } else null,
        contentPadding = PaddingValues(vertical = spacing.md),
    ) {
        when (apps) {
            TopAppsState.Empty -> Column(Modifier.padding(horizontal = spacing.md)) {
                QuietText(stringResource(R.string.now_apps_empty))
                TextButton(onClick = onOpenApps) { Text(stringResource(R.string.now_apps_open)) }
            }
            is TopAppsState.Ready -> {
                val formatter = rememberTimeAxisFormatter()
                QuietText(
                    // The cache can be days old: then the time carries its date.
                    stringResource(basisLabel(apps.basis), dayAwareTime(formatter, apps.capturedAtMs, nowMs)),
                    Modifier.padding(horizontal = spacing.md),
                )
                val locale = currentLocale()
                val mah = stringResource(R.string.now_unit_mah)
                apps.rows.forEach { app ->
                    val label = app.label.displayName()
                    AppRow(
                        icon = { AppLabelIcon(app.packageName, app.label) },
                        label = label,
                        value = "${formatNumber(app.powerMah, if (app.powerMah < 10) 1 else 0, locale)} $mah",
                        share = app.share,
                        onClick = { onOpenApp(app.uid, app.packageName) },
                    )
                }
            }
        }
    }
}

private fun basisLabel(basis: AppUsageBasis): Int = when (basis) {
    AppUsageBasis.DELTA -> R.string.now_apps_basis_delta
    AppUsageBasis.WINDOW_RESET -> R.string.now_apps_basis_reset
    AppUsageBasis.ABSOLUTE -> R.string.now_apps_basis_absolute
}

/** A detected current correction was applied: say what changed, with Undo and Keep. */
@Composable
internal fun CalibrationNotice(
    calibration: CurrentCalibration,
    onUndo: () -> Unit,
    onKeep: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = MaterialTheme.colorScheme.onTertiaryContainer
    val body = when {
        calibration.unit == CurrentUnit.MILLIAMPS && calibration.sign == CurrentSign.INVERTED -> R.string.now_calibration_both
        calibration.unit == CurrentUnit.MILLIAMPS -> R.string.now_calibration_unit
        calibration.sign == CurrentSign.INVERTED -> R.string.now_calibration_sign
        else -> R.string.now_calibration_same
    }
    Panel(modifier, color = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
            Icon(Icons.Rounded.Tune, contentDescription = null, tint = content)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs)) {
                Text(
                    stringResource(R.string.now_calibration_title),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleSmall,
                    color = content,
                )
                Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = content)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            val colors = ButtonDefaults.textButtonColors(contentColor = content)
            TextButton(onClick = onUndo, colors = colors) { Text(stringResource(R.string.now_calibration_undo)) }
            TextButton(onClick = onKeep, colors = colors) { Text(stringResource(R.string.now_calibration_keep)) }
        }
    }
}

@Composable
private fun QuietText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A navigation hint for a tappable panel, sized like a touch target so it lines up with the gutter. */
@Composable
private fun Chevron() {
    Box(Modifier.minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(MaterialTheme.spacing.lg),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun sameLocalDay(firstMs: Long, secondMs: Long, zone: TimeZone): Boolean {
    val first = Calendar.getInstance(zone).apply { timeInMillis = firstMs }
    val second = Calendar.getInstance(zone).apply { timeInMillis = secondMs }
    return first.get(Calendar.ERA) == second.get(Calendar.ERA) &&
        first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
        first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
}
