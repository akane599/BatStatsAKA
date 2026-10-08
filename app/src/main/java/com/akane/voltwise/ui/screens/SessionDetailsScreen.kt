package com.akane.voltwise.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.akane.voltwise.R
import com.akane.voltwise.battery.apps.AppUsageBasis
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.data.sampling.ChargerType
import com.akane.voltwise.battery.measurement.CapacityConfidence
import com.akane.voltwise.battery.service.SamplingDemand
import com.akane.voltwise.ui.components.AppIconPlaceholder
import com.akane.voltwise.ui.components.AppLabelIcon
import com.akane.voltwise.ui.components.AppRow
import com.akane.voltwise.ui.components.DetailTopBar
import com.akane.voltwise.ui.components.EmptyState
import com.akane.voltwise.ui.components.InfoSheet
import com.akane.voltwise.ui.components.Notice
import com.akane.voltwise.ui.components.NoticeTone
import com.akane.voltwise.ui.components.Panel
import com.akane.voltwise.ui.components.QuietText
import com.akane.voltwise.ui.components.StatCell
import com.akane.voltwise.ui.components.chart.ChartDefaults
import com.akane.voltwise.ui.components.chart.ChartScrubState
import com.akane.voltwise.ui.components.chart.ChartSeries
import com.akane.voltwise.ui.components.chart.NumberFormatter
import com.akane.voltwise.ui.components.chart.TimeGranularity
import com.akane.voltwise.ui.components.chart.TimeSeriesChart
import com.akane.voltwise.ui.components.chart.rememberChartScrubState
import com.akane.voltwise.ui.components.chart.rememberTimeAxisFormatter
import com.akane.voltwise.ui.components.displayName
import com.akane.voltwise.ui.components.headerActionOverhang
import com.akane.voltwise.ui.format.compactDuration
import com.akane.voltwise.ui.format.currentLocale
import com.akane.voltwise.ui.format.dayAwareTime
import com.akane.voltwise.ui.format.durationAnnotated
import com.akane.voltwise.ui.format.formatNumber
import com.akane.voltwise.ui.format.formatPercent
import com.akane.voltwise.ui.format.formatRate
import com.akane.voltwise.ui.format.styledTemplate
import com.akane.voltwise.ui.format.valueWithUnit
import com.akane.voltwise.ui.format.unitSpan
import com.akane.voltwise.ui.format.percentUnit
import com.akane.voltwise.ui.format.mahText
import com.akane.voltwise.ui.theme.batColors
import com.akane.voltwise.ui.theme.chartColors
import com.akane.voltwise.ui.theme.numericDisplay
import com.akane.voltwise.ui.theme.numericHeadline
import com.akane.voltwise.ui.theme.spacing
import com.akane.voltwise.viewmodel.SessionApps
import com.akane.voltwise.viewmodel.SessionCharts
import com.akane.voltwise.viewmodel.SessionDetailsEvent
import com.akane.voltwise.viewmodel.SessionDetailsUiState
import com.akane.voltwise.viewmodel.SessionDetailsViewModel
import com.akane.voltwise.viewmodel.SessionInsights
import com.akane.voltwise.viewmodel.SessionSummary
import org.koin.compose.koinInject

private const val DEMAND_TAG = "SessionDetails"

/** Two columns from this window width (as on Now): the session and its charts on the start, the breakdowns on the end. */
private const val TWO_COLUMN_MIN_WIDTH_DP = 840

/** Apps listed before "Show all". */
private const val COLLAPSED_APPS = 10

/** Below this share of the observed time, the header says how much the charge counter covered. */
private const val PARTIAL_COVERAGE = 0.9

/**
 * SessionDetails, wired: the ViewModel (Koin, keyed to the session id by the caller), 2 s sampling while a
 * **recording** session is shown and the screen is started (released at onStop, and as soon as the session ends), and
 * the way back after a delete. [onOpenApp] opens an app's details from the per-app list; [onOpenAccessSetup] opens
 * Settings › Status from its no-access line.
 */
@Composable
fun SessionDetailsScreen(
    onBack: () -> Unit,
    onOpenApp: (uid: Int, packageName: String) -> Unit,
    onOpenAccessSetup: () -> Unit,
    vm: SessionDetailsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val demand: SamplingDemand = koinInject()
    val recording = (state as? SessionDetailsUiState.Ready)?.summary?.recording == true
    LifecycleStartEffect(demand, recording) {
        val token = if (recording) demand.acquire(DEMAND_TAG) else null
        onStopOrDispose { token?.close() }
    }
    val back by rememberUpdatedState(onBack)
    val deleted = state == SessionDetailsUiState.Deleted
    LaunchedEffect(deleted) { if (deleted) back() }
    SessionDetailsContent(
        state = state,
        onEvent = { event ->
            when (event) {
                SessionDetailsEvent.Back -> onBack()
                is SessionDetailsEvent.OpenApp -> onOpenApp(event.uid, event.packageName)
                SessionDetailsEvent.OpenAccessSetup -> onOpenAccessSetup()
                else -> vm.onEvent(event)
            }
        },
        modifier = modifier,
    )
}

/**
 * SessionDetails, stateless: [state] in, [onEvent] out. A top bar (type, back, Delete behind a confirmation), then one
 * scrolling page: the header, level + current and temperature charts sharing [scrubState], then drain or charging
 * insights and the per-app list. From 840 dp the breakdowns move to a second column.
 */
@Composable
fun SessionDetailsContent(
    state: SessionDetailsUiState,
    onEvent: (SessionDetailsEvent) -> Unit,
    modifier: Modifier = Modifier,
    scrubState: ChartScrubState = rememberChartScrubState(),
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val ready = state as? SessionDetailsUiState.Ready
    val canDelete = ready?.canDelete == true
    val deleteFailed = stringResource(R.string.sessiondetails_delete_failed)
    LaunchedEffect(ready?.deleteFailed) {
        if (ready?.deleteFailed == true) {
            snackbar.showSnackbar(deleteFailed)
            onEvent(SessionDetailsEvent.DismissDeleteError)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            DetailTopBar(
                title = stringResource(ready?.summary?.type?.let(::typeLabel) ?: R.string.sessiondetails_title),
                onBack = { onEvent(SessionDetailsEvent.Back) },
            ) {
                // Hidden while recording: the writer would re-create the session at its next save.
                if (canDelete) {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.sessiondetails_delete))
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (state) {
            SessionDetailsUiState.Loading, SessionDetailsUiState.Deleted -> Unit
            SessionDetailsUiState.Missing -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = stringResource(R.string.sessiondetails_missing_title),
                    body = stringResource(R.string.sessiondetails_missing_body),
                    icon = Icons.Rounded.History,
                    actionLabel = stringResource(R.string.sessiondetails_missing_action),
                    onAction = { onEvent(SessionDetailsEvent.Back) },
                )
            }
            is SessionDetailsUiState.Ready -> SessionPage(state, onEvent, scrubState, Modifier.padding(padding))
        }
    }

    if (confirmDelete && canDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.sessiondetails_delete_title)) },
            text = { Text(stringResource(R.string.sessiondetails_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onEvent(SessionDetailsEvent.Delete)
                }) { Text(stringResource(R.string.sessiondetails_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.sessiondetails_cancel)) }
            },
        )
    }
}

@Composable
private fun SessionPage(
    state: SessionDetailsUiState.Ready,
    onEvent: (SessionDetailsEvent) -> Unit,
    scrubState: ChartScrubState,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val twoColumns = LocalWindowInfo.current.containerSize.width / LocalDensity.current.density >= TWO_COLUMN_MIN_WIDTH_DP
    val column = Arrangement.spacedBy(spacing.sm)
    val summary = state.summary

    val session: @Composable () -> Unit = {
        SessionHeader(summary, Modifier.fillMaxWidth())
        ChartPanels(state.charts, summary.recording, state.useFahrenheit, scrubState)
    }
    val breakdowns: @Composable () -> Unit = {
        when (val insights = state.insights) {
            is SessionInsights.Drain -> DrainPanel(insights, Modifier.fillMaxWidth())
            is SessionInsights.Charging -> ChargingPanel(insights, state.useFahrenheit, Modifier.fillMaxWidth())
            null -> Unit
        }
        state.apps?.let { apps ->
            AppsPanel(
                apps,
                referenceMs = summary.endedAtMs,
                onOpenApp = { uid, packageName -> onEvent(SessionDetailsEvent.OpenApp(uid, packageName)) },
                onOpenAccessSetup = { onEvent(SessionDetailsEvent.OpenAccessSetup) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.md),
        verticalArrangement = column,
    ) {
        if (twoColumns) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Column(Modifier.weight(1f), verticalArrangement = column) { session() }
                Column(Modifier.weight(1f), verticalArrangement = column) { breakdowns() }
            }
        } else {
            session()
            breakdowns()
        }
    }
}

// ---- Header ----

/** When and how long, the level change, and the counter's figures; the direction dot keys the session type. */
@Composable
private fun SessionHeader(summary: SessionSummary, modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.spacing
    Panel(modifier, color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SpanLine(summary, typeColor(summary.type), Modifier.weight(1f))
            Box(Modifier.headerActionOverhang(spacing.sm)) {
                InfoSheet(stringResource(R.string.sessiondetails_info_title), stringResource(R.string.sessiondetails_info_body))
            }
        }
        LevelsAndDuration(summary)
        if (summary.measured) {
            HeaderCells(summary)
            summary.counterCoverage?.takeIf { it < PARTIAL_COVERAGE }?.let { coverage ->
                QuietText(stringResource(R.string.sessiondetails_coverage, formatNumber(coverage * 100, 0, currentLocale())))
            }
        } else {
            QuietText(stringResource(R.string.sessiondetails_legacy))
        }
    }
}

/** "Oct 9, 6:10 AM – 8:10 AM" (the end carries a date only when it is another day), or "Recording since …". */
@Composable
private fun SpanLine(summary: SessionSummary, color: Color, modifier: Modifier = Modifier) {
    val formatter = rememberTimeAxisFormatter()
    val start = formatter.format(summary.startedAtMs, TimeGranularity.DATE_TIME)
    val text = if (summary.recording) {
        stringResource(R.string.sessiondetails_recording_since, start)
    } else {
        stringResource(R.string.sessiondetails_span, start, dayAwareTime(formatter, summary.endedAtMs, summary.startedAtMs))
    }
    Row(
        modifier.semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
    ) {
        Box(Modifier.size(MaterialTheme.spacing.xs).background(color, CircleShape))
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** "92% → 41%" on the start edge, the duration on the end; they stack when both don't fit (large font). */
@Composable
private fun LevelsAndDuration(summary: SessionSummary) {
    val spacing = MaterialTheme.spacing
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(spacing.xxs),
        itemVerticalAlignment = Alignment.Bottom,
    ) {
        LevelsText(summary.startLevel, summary.endLevel)
        Spacer(Modifier.width(spacing.md))
        val style = MaterialTheme.typography.numericHeadline
        Text(
            durationAnnotated(summary.endedAtMs - summary.startedAtMs, unitSpan(style)),
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun LevelsText(start: Int?, end: Int?) {
    val locale = currentLocale()
    val noValue = stringResource(R.string.component_no_value)
    val first = start?.let { formatNumber(it.toDouble(), 0, locale) } ?: noValue
    val last = end?.let { formatNumber(it.toDouble(), 0, locale) } ?: noValue
    val spoken = stringResource(R.string.sessiondetails_levels_spoken, first, last)
    val style = MaterialTheme.typography.numericDisplay
    Text(
        styledTemplate(stringResource(R.string.sessiondetails_levels), listOf(first, last), unitSpan(style)),
        modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * Used / Charged in mAh (the share of the battery and the energy underneath), the average as %/h of the capacity
 * when it is known (mA underneath; else mA alone, like DrainCell), and this session's capacity estimate when it has one.
 */
@Composable
private fun HeaderCells(summary: SessionSummary) {
    val locale = currentLocale()
    val noValue = stringResource(R.string.component_no_value)
    val mah = stringResource(R.string.now_unit_mah)
    val milliamps = stringResource(R.string.now_unit_ma)
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
        val share = summary.chargePercent?.let { formatPercent(it) }
        val energy = summary.energyWh?.let { stringResource(R.string.sessiondetails_energy, formatRate(it, locale)) }
        StatCell(
            stringResource(if (summary.type == SessionType.CHARGE) R.string.sessiondetails_charged else R.string.sessiondetails_used),
            summary.chargeMah?.let { formatNumber(it, 0, locale) } ?: noValue,
            Modifier.weight(1f),
            unit = mah,
            supporting = if (share != null && energy != null) {
                stringResource(R.string.sessiondetails_charge_supporting, share, energy)
            } else share ?: energy,
        )
        val average = summary.averageMa?.let { formatNumber(it, 0, locale) }
        val perHour = summary.percentPerHour
        if (perHour != null && average != null) {
            StatCell(
                stringResource(R.string.sessiondetails_average),
                formatRate(perHour, locale),
                Modifier.weight(1f),
                unit = stringResource(R.string.now_unit_percent_per_hour),
                supporting = valueWithUnit(average, milliamps),
            )
        } else {
            StatCell(stringResource(R.string.sessiondetails_average), average ?: noValue, Modifier.weight(1f), unit = milliamps)
        }
        summary.capacity?.let { capacity ->
            StatCell(
                stringResource(R.string.sessiondetails_capacity),
                formatNumber(capacity.mah.toDouble(), 0, locale),
                Modifier.weight(1f),
                unit = mah,
                supporting = stringResource(confidenceLabel(capacity.confidence)),
            )
        }
    }
}

// ---- Charts ----

/**
 * Level + current (current on the left axis in the direction colors, level on the right) and temperature, both over
 * the session's window and sharing [scrubState], so scrubbing one moves the other. No readings: one quiet panel.
 */
@Composable
private fun ChartPanels(charts: SessionCharts, recording: Boolean, useFahrenheit: Boolean, scrubState: ChartScrubState) {
    val modifier = Modifier.fillMaxWidth()
    if (!charts.hasReadings) {
        Panel(modifier, title = stringResource(R.string.sessiondetails_readings_title)) {
            QuietText(stringResource(R.string.sessiondetails_no_readings))
        }
        return
    }
    val empty = stringResource(R.string.sessiondetails_chart_empty)
    val currentLabel = stringResource(R.string.sessiondetails_series_current)
    val levelLabel = stringResource(R.string.sessiondetails_series_level)
    val milliamps = stringResource(R.string.now_unit_ma)
    val percent = percentUnit().sign
    val direction = ChartDefaults.directionStyle()
    val levelStyle = ChartDefaults.solidStyle(MaterialTheme.chartColors.level)
    val main = remember(charts.currentMa, charts.level, currentLabel, levelLabel, milliamps, percent, direction, levelStyle) {
        listOf(
            ChartSeries(currentLabel, charts.currentMa, direction, unit = milliamps, format = NumberFormatter(milliamps, maxDecimals = 0)),
            ChartSeries(
                levelLabel,
                charts.level,
                levelStyle,
                unit = percent,
                format = NumberFormatter(percent, maxDecimals = 0),
                axisMin = 0.0,
                axisMax = 100.0,
                axisBounds = 0.0..100.0,
            ),
        )
    }
    Panel(modifier, title = stringResource(R.string.sessiondetails_chart_title)) {
        TimeSeriesChart(
            series = main,
            modifier = Modifier.fillMaxWidth(),
            window = charts.window,
            markLatest = recording,
            scrubState = scrubState,
            emptyText = empty,
        )
    }

    val temperatureLabel = stringResource(R.string.sessiondetails_temperature_title)
    val degrees = stringResource(if (useFahrenheit) R.string.now_unit_fahrenheit else R.string.now_unit_celsius)
    val temperatureStyle = ChartDefaults.solidStyle(MaterialTheme.chartColors.temperature)
    val temperature = remember(charts.temperature, temperatureLabel, degrees, temperatureStyle) {
        listOf(ChartSeries(temperatureLabel, charts.temperature, temperatureStyle, unit = degrees, format = NumberFormatter(degrees, maxDecimals = 1)))
    }
    Panel(modifier, title = temperatureLabel) {
        TimeSeriesChart(
            series = temperature,
            modifier = Modifier.fillMaxWidth(),
            window = charts.window,
            markLatest = recording,
            scrubState = scrubState,
            emptyText = empty,
        )
    }
}

// ---- Insights ----

/** Screen on / off as %/h (mA and duration below) and deep sleep, from the session row. */
@Composable
private fun DrainPanel(drain: SessionInsights.Drain, modifier: Modifier = Modifier) {
    Panel(
        modifier,
        title = stringResource(R.string.sessiondetails_drain_title),
        trailing = { InfoSheet(stringResource(R.string.sessiondetails_drain_info_title), stringResource(R.string.sessiondetails_drain_info_body)) },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
            DrainCell(stringResource(R.string.sessiondetails_screen_on), drain.screenOn, Modifier.weight(1f))
            DrainCell(stringResource(R.string.sessiondetails_screen_off), drain.screenOff, Modifier.weight(1f))
        }
        StatCell(
            stringResource(R.string.sessiondetails_deep_sleep),
            drain.deepSleepPercent?.let { formatNumber(it, 0, currentLocale()) } ?: stringResource(R.string.component_no_value),
            unit = percentUnit().sign,
            unitFirst = percentUnit().first,
            supporting = if (drain.deepSleepScreenOff) stringResource(R.string.sessiondetails_deep_sleep_screen_off) else null,
        )
    }
}

/** Charger, average power (peak under it), peak temperature and the 20 → 80 time, as a 2×2 grid. */
@Composable
private fun ChargingPanel(charging: SessionInsights.Charging, useFahrenheit: Boolean, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    val noValue = stringResource(R.string.component_no_value)
    val spacing = MaterialTheme.spacing
    Panel(
        modifier,
        title = stringResource(R.string.sessiondetails_charging_title),
        trailing = { InfoSheet(stringResource(R.string.sessiondetails_charging_info_title), stringResource(R.string.sessiondetails_charging_info_body)) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                StatCell(
                    stringResource(R.string.sessiondetails_charger),
                    charging.charger?.let { stringResource(chargerLabel(it)) } ?: noValue,
                    Modifier.weight(1f),
                )
                StatCell(
                    stringResource(R.string.sessiondetails_average_power),
                    charging.averagePowerW?.let { formatNumber(it, 1, locale) } ?: noValue,
                    Modifier.weight(1f),
                    unit = stringResource(R.string.now_unit_w),
                    supporting = charging.peakPowerW?.let { stringResource(R.string.sessiondetails_peak_power, formatNumber(it, 1, locale)) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                StatCell(
                    stringResource(R.string.sessiondetails_peak_temperature),
                    charging.peakTemperature?.let { formatNumber(it, 1, locale) } ?: noValue,
                    Modifier.weight(1f),
                    unit = stringResource(if (useFahrenheit) R.string.now_unit_fahrenheit else R.string.now_unit_celsius),
                )
                val span = charging.twentyToEightyMs
                if (span != null) {
                    val compact = compactDuration(span, locale)
                    StatCell(stringResource(R.string.sessiondetails_twenty_to_eighty), compact.value, Modifier.weight(1f), unit = stringResource(compact.unit))
                } else {
                    StatCell(
                        stringResource(R.string.sessiondetails_twenty_to_eighty),
                        noValue,
                        Modifier.weight(1f),
                        supporting = stringResource(R.string.sessiondetails_not_covered),
                    )
                }
            }
        }
    }
}

// ---- Apps ----

/**
 * The per-app breakdown: its basis, then icon · label · mAh · share rows (each opens the app), the folded tail, and
 * "Show all" past [COLLAPSED_APPS]. Without rows, one line says why ([SessionApps] status); without access it offers
 * the access setup, as Apps does.
 */
@Composable
private fun AppsPanel(
    apps: SessionApps,
    referenceMs: Long,
    onOpenApp: (uid: Int, packageName: String) -> Unit,
    onOpenAccessSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    Panel(
        modifier,
        title = stringResource(R.string.sessiondetails_apps_title),
        trailing = { InfoSheet(stringResource(R.string.sessiondetails_apps_info_title), stringResource(R.string.sessiondetails_apps_info_body)) },
        contentPadding = PaddingValues(vertical = spacing.md),
    ) {
        val gutter = Modifier.padding(horizontal = spacing.md)
        when (apps) {
            is SessionApps.Ready -> AppRows(apps, referenceMs, onOpenApp, gutter)
            SessionApps.Pending -> QuietText(stringResource(R.string.sessiondetails_apps_pending), gutter)
            SessionApps.Collecting -> QuietText(stringResource(R.string.sessiondetails_apps_collecting), gutter)
            SessionApps.NoAccess -> Notice(
                stringResource(R.string.sessiondetails_apps_no_access),
                gutter,
                tone = NoticeTone.INFO,
                icon = Icons.Rounded.Key,
            ) {
                TextButton(onClick = onOpenAccessSetup) { Text(stringResource(R.string.apps_access_set_up)) }
            }
            SessionApps.Failed -> Notice(stringResource(R.string.sessiondetails_apps_failed), gutter)
            SessionApps.NotRecorded -> QuietText(stringResource(R.string.sessiondetails_apps_not_recorded), gutter)
            SessionApps.Empty -> QuietText(stringResource(R.string.sessiondetails_apps_empty), gutter)
        }
    }
}

@Composable
private fun AppRows(
    apps: SessionApps.Ready,
    referenceMs: Long,
    onOpenApp: (uid: Int, packageName: String) -> Unit,
    gutter: Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val capturedAt = apps.capturedAtMs
    val caption = if (apps.soFar && capturedAt != null) {
        stringResource(R.string.sessiondetails_apps_so_far, dayAwareTime(rememberTimeAxisFormatter(), capturedAt, referenceMs))
    } else {
        stringResource(basisLabel(apps.basis))
    }
    QuietText(caption, gutter)
    val collapsible = apps.rows.size > COLLAPSED_APPS
    val shown = if (collapsible && !expanded) apps.rows.take(COLLAPSED_APPS) else apps.rows
    shown.forEach { app ->
        AppRow(
            icon = { AppLabelIcon(app.packageName, app.label) },
            label = app.label.displayName(),
            value = mahText(app.powerMah),
            share = app.share,
            onClick = { onOpenApp(app.uid, app.packageName) },
        )
    }
    // The tail beyond the top 30 only makes sense under the full list.
    val othersMah = apps.othersMah
    if (othersMah != null && (expanded || !collapsible)) {
        val others = stringResource(R.string.sessiondetails_apps_others)
        AppRow(
            icon = { AppIconPlaceholder(others, icon = Icons.Rounded.MoreHoriz) },
            label = others,
            value = mahText(othersMah),
            share = apps.othersShare,
        )
    }
    if (collapsible) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(horizontal = MaterialTheme.spacing.xs)) {
            Text(stringResource(if (expanded) R.string.sessiondetails_apps_show_fewer else R.string.sessiondetails_apps_show_all))
        }
    }
}

// ---- Labels ----

private fun typeLabel(type: SessionType): Int = when (type) {
    SessionType.DISCHARGE -> R.string.sessiondetails_type_discharge
    SessionType.CHARGE -> R.string.sessiondetails_type_charge
    SessionType.PLUGGED -> R.string.sessiondetails_type_plugged
    SessionType.UNKNOWN -> R.string.sessiondetails_type_unknown
}

/** Energy direction, as on Now: in (charge), out (drain), neutral when Android didn't say. */
@Composable
private fun typeColor(type: SessionType): Color = when (type) {
    SessionType.CHARGE, SessionType.PLUGGED -> MaterialTheme.batColors.charge
    SessionType.DISCHARGE -> MaterialTheme.batColors.drain
    SessionType.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun confidenceLabel(confidence: CapacityConfidence): Int = when (confidence) {
    CapacityConfidence.LOW -> R.string.sessiondetails_confidence_low
    CapacityConfidence.MEDIUM -> R.string.sessiondetails_confidence_medium
    CapacityConfidence.HIGH -> R.string.sessiondetails_confidence_high
}

private fun chargerLabel(charger: ChargerType): Int = when (charger) {
    ChargerType.AC -> R.string.sessiondetails_charger_ac
    ChargerType.USB -> R.string.sessiondetails_charger_usb
    ChargerType.WIRELESS -> R.string.sessiondetails_charger_wireless
    ChargerType.DOCK -> R.string.sessiondetails_charger_dock
}

private fun basisLabel(basis: AppUsageBasis): Int = when (basis) {
    AppUsageBasis.DELTA -> R.string.sessiondetails_apps_basis_delta
    AppUsageBasis.WINDOW_RESET -> R.string.sessiondetails_apps_basis_reset
    AppUsageBasis.ABSOLUTE -> R.string.sessiondetails_apps_basis_absolute
}

