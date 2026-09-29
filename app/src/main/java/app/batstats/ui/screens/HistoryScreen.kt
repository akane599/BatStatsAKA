package app.batstats.ui.screens

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.BatteryUnknown
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Battery3Bar
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batstats.R
import app.batstats.battery.data.db.SessionType
import app.batstats.ui.components.AppIconDefaults
import app.batstats.ui.components.EmptyState
import app.batstats.ui.components.InfoSheet
import app.batstats.ui.components.Notice
import app.batstats.ui.components.Panel
import app.batstats.ui.components.QuietText
import app.batstats.ui.components.SegmentedTabs
import app.batstats.ui.components.StatCell
import app.batstats.ui.components.chart.BarChart
import app.batstats.ui.components.chart.BarEntry
import app.batstats.ui.components.chart.BarSegment
import app.batstats.ui.components.chart.ChartMath
import app.batstats.ui.components.chart.MINUS_SIGN
import app.batstats.ui.components.chart.NumberFormatter
import app.batstats.ui.components.chart.TimeAxisFormatter
import app.batstats.ui.components.chart.TimeGranularity
import app.batstats.ui.components.chart.rememberTimeAxisFormatter
import app.batstats.ui.components.headerActionOverhang
import app.batstats.ui.format.currentLocale
import app.batstats.ui.format.durationString
import app.batstats.ui.format.formatNumber
import app.batstats.ui.format.percentUnit
import app.batstats.ui.format.formatPercent
import app.batstats.ui.format.mahText
import app.batstats.ui.theme.batColors
import app.batstats.ui.theme.chartColors
import app.batstats.ui.theme.numericBody
import app.batstats.ui.theme.spacing
import app.batstats.viewmodel.AppUsageHint
import app.batstats.viewmodel.DayEntry
import app.batstats.viewmodel.DayFigures
import app.batstats.viewmodel.DayRange
import app.batstats.viewmodel.HistoryEvent
import app.batstats.viewmodel.HistoryMode
import app.batstats.viewmodel.HistoryUiState
import app.batstats.viewmodel.HistoryViewModel
import app.batstats.viewmodel.SessionFilter
import app.batstats.viewmodel.SessionRow
import app.batstats.viewmodel.SessionsState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import org.koin.androidx.compose.koinViewModel

/** Two columns (chart beside the day list) from this window width, as on Now. */
private const val TWO_COLUMN_MIN_WIDTH_DP = 840

// Widest realistic figures (StatCell sizing templates), so cells keep their size as the selection changes.
private const val MAH_TEMPLATE = 8_888.0
private const val PERCENT_TEMPLATE = 100.0

/**
 * History, wired: the Koin [HistoryViewModel] (mode, range, chip and selected day survive process death through its
 * SavedStateHandle). Opening a session leaves through [onOpenSession]; every other [HistoryEvent] goes to the VM.
 * [showToday] (Now's Today card) switches to Days with today selected, once, then reports [onTodayShown].
 */
@Composable
fun HistoryScreen(
    onOpenSession: (sessionId: String) -> Unit,
    modifier: Modifier = Modifier,
    showToday: Boolean = false,
    onTodayShown: () -> Unit = {},
    vm: HistoryViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(showToday) {
        if (showToday) {
            vm.onEvent(HistoryEvent.ShowToday)
            onTodayShown()
        }
    }
    HistoryContent(
        state = state,
        onEvent = { event -> if (event is HistoryEvent.OpenSession) onOpenSession(event.sessionId) else vm.onEvent(event) },
        modifier = modifier,
    )
}

/**
 * History, stateless: [state] in, [onEvent] out. One scrolling page under the status bar: the title (with its ⓘ),
 * Days | Sessions, then either the day chart and daily totals (side by side from 840 dp) or the session chips and
 * the sessions grouped by the day they started.
 */
@Composable
fun HistoryContent(
    state: HistoryUiState,
    onEvent: (HistoryEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val twoColumns = LocalWindowInfo.current.containerSize.width / LocalDensity.current.density >= TWO_COLUMN_MIN_WIDTH_DP
    val labels = rememberDayLabels()
    val formatter = rememberTimeAxisFormatter()
    LazyColumn(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
        contentPadding = PaddingValues(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        item(key = "header") { Header() }
        item(key = "mode") {
            // In two columns the toggle spans the start column only, like the chart under it.
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                ModeTabs(state.mode, onSelect = { onEvent(HistoryEvent.SelectMode(it)) }, Modifier.weight(1f))
                if (twoColumns) Spacer(Modifier.weight(1f))
            }
        }
        when (state.mode) {
            HistoryMode.DAYS -> daysItems(state, labels, twoColumns, onEvent)
            HistoryMode.SESSIONS -> sessionsItems(state, labels, formatter, twoColumns, onEvent)
        }
    }
}

@Composable
private fun ModeTabs(selected: HistoryMode, onSelect: (HistoryMode) -> Unit, modifier: Modifier = Modifier) {
    val modes = HistoryMode.entries
    SegmentedTabs(
        labels = modes.map { stringResource(modeLabel(it)) },
        selectedIndex = modes.indexOf(selected),
        onSelect = { onSelect(modes[it]) },
        modifier = modifier,
        contentDescription = stringResource(R.string.history_mode_label),
    )
}

@Composable
private fun Header(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.history),
            modifier = Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        InfoSheet(
            stringResource(R.string.history_info_title),
            stringResource(R.string.history_info_body),
            Modifier.headerActionOverhang(MaterialTheme.spacing.sm),
        )
    }
}

private fun modeLabel(mode: HistoryMode): Int = when (mode) {
    HistoryMode.DAYS -> R.string.history_mode_days
    HistoryMode.SESSIONS -> R.string.history_mode_sessions
}

// ---- Days ----

private fun LazyListScope.daysItems(state: HistoryUiState, labels: DayLabels, twoColumns: Boolean, onEvent: (HistoryEvent) -> Unit) {
    val days = state.days
    when {
        days.failed -> item(key = "days-failed") { LoadFailed(onRetry = { onEvent(HistoryEvent.Retry) }) }
        days.loading -> item(key = "days-loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        twoColumns && days.recorded -> item(key = "days") {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                DayChartPanel(state, labels, onEvent, Modifier.weight(1f))
                DayListPanel(state, labels, onEvent, Modifier.weight(1f))
            }
        }
        else -> {
            item(key = "days-chart") { DayChartPanel(state, labels, onEvent, Modifier.fillMaxWidth()) }
            if (days.recorded) item(key = "days-list") { DayListPanel(state, labels, onEvent, Modifier.fillMaxWidth()) }
        }
    }
}

/**
 * 7 · 14 · 30 days, the stacked bars (screen on under screen off, in mAh), then the selected day's figures or the
 * daily average. With nothing recorded in the range the panel keeps its range tabs over an empty state.
 */
@Composable
private fun DayChartPanel(state: HistoryUiState, labels: DayLabels, onEvent: (HistoryEvent) -> Unit, modifier: Modifier = Modifier) {
    val days = state.days
    Panel(modifier) {
        val ranges = DayRange.entries
        SegmentedTabs(
            labels = ranges.map { stringResource(rangeLabel(it)) },
            selectedIndex = ranges.indexOf(state.range),
            onSelect = { onEvent(HistoryEvent.SelectRange(ranges[it])) },
            contentDescription = stringResource(R.string.history_range_label),
        )
        if (!days.recorded) {
            EmptyState(
                title = stringResource(R.string.history_days_empty_title),
                body = stringResource(if (state.monitoring) R.string.history_days_empty_monitoring else R.string.history_days_empty_body),
                icon = Icons.Rounded.BarChart,
            )
            return@Panel
        }
        val mah = stringResource(R.string.now_unit_mah)
        val screenOn = stringResource(R.string.history_screen_on)
        val screenOff = stringResource(R.string.history_screen_off)
        val colors = MaterialTheme.chartColors
        val segments = remember(screenOn, screenOff, colors) {
            listOf(BarSegment(screenOn, colors.drain), BarSegment(screenOff, colors.drainSecondary))
        }
        val entries = remember(days.days, state.range, labels) {
            days.days.map { day ->
                BarEntry(
                    labels.bar(day.epochDay, state.range),
                    listOf(day.figures?.screenOnMah ?: 0.0, day.figures?.screenOffMah ?: 0.0),
                    shortLabel = labels.barShort(day.epochDay, state.range),
                )
            }
        }
        val selectedIndex = days.days.indexOfFirst { it.epochDay == state.selectedDay }.takeIf { it >= 0 }
        BarChart(
            entries = entries,
            segments = segments,
            unit = mah,
            format = remember(mah) { NumberFormatter(mah, maxDecimals = 0) },
            selectedIndex = selectedIndex,
            onSelect = { index -> onEvent(HistoryEvent.SelectDay(index?.let { days.days[it].epochDay })) },
            emptyText = stringResource(R.string.history_chart_empty),
        )
        val selected = selectedIndex?.let { days.days[it] }
        DayFiguresBlock(
            title = selected?.let { labels.full(it.epochDay, state.todayEpochDay) } ?: stringResource(R.string.history_average),
            figures = if (selected != null) selected.figures else days.average,
            modifier = Modifier.padding(top = MaterialTheme.spacing.xs),
        )
    }
}

private fun rangeLabel(range: DayRange): Int = when (range) {
    DayRange.WEEK -> R.string.history_range_week
    DayRange.TWO_WEEKS -> R.string.history_range_two_weeks
    DayRange.MONTH -> R.string.history_range_month
}

/** A day's (or the average day's) figures, 2 × 2: screen on, screen off (named as in the chart legend), deep sleep, charged. */
@Composable
private fun DayFiguresBlock(title: String, figures: DayFigures?, modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.spacing
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (figures == null) {
            QuietText(stringResource(R.string.history_day_not_recorded))
            return@Column
        }
        val locale = currentLocale()
        val mah = stringResource(R.string.now_unit_mah)
        val mahTemplate = formatNumber(MAH_TEMPLATE, 0, locale)
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            StatCell(
                stringResource(R.string.history_screen_on),
                formatNumber(figures.screenOnMah, 0, locale),
                Modifier.weight(1f),
                unit = mah,
                supporting = durationString(figures.screenOnMs),
                sizingTemplate = mahTemplate,
            )
            StatCell(
                stringResource(R.string.history_screen_off),
                formatNumber(figures.screenOffMah, 0, locale),
                Modifier.weight(1f),
                unit = mah,
                supporting = durationString(figures.screenOffMs),
                sizingTemplate = mahTemplate,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            StatCell(
                stringResource(R.string.history_deep_sleep),
                figures.deepSleepPercent?.let { formatNumber(it, 0, locale) } ?: stringResource(R.string.component_no_value),
                Modifier.weight(1f),
                unit = percentUnit().sign,
                unitFirst = percentUnit().first,
                sizingTemplate = formatNumber(PERCENT_TEMPLATE, 0, locale),
            )
            StatCell(
                stringResource(R.string.history_charged),
                formatNumber(figures.chargedMah, 0, locale),
                Modifier.weight(1f),
                unit = mah,
                sizingTemplate = mahTemplate,
            )
        }
    }
}

/** The recorded days, newest first: used mAh, screen-on time and charge taken in. A row selects its bar. */
@Composable
private fun DayListPanel(state: HistoryUiState, labels: DayLabels, onEvent: (HistoryEvent) -> Unit, modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.spacing
    val recorded = remember(state.days.days) { state.days.days.filter { it.figures != null }.asReversed() }
    Panel(
        modifier,
        title = stringResource(R.string.history_days_title),
        contentPadding = PaddingValues(top = spacing.md, bottom = spacing.xs),
    ) {
        Column {
            recorded.forEach { day ->
                val selected = day.epochDay == state.selectedDay
                DayRow(
                    day = day,
                    label = labels.full(day.epochDay, state.todayEpochDay),
                    selected = selected,
                    onClick = { onEvent(HistoryEvent.SelectDay(if (selected) null else day.epochDay)) },
                )
            }
        }
    }
}

@Composable
private fun DayRow(day: DayEntry, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val figures = day.figures ?: return
    val spacing = MaterialTheme.spacing
    val locale = currentLocale()
    val mah = stringResource(R.string.now_unit_mah)
    val screenOn = durationString(figures.screenOnMs)
    val detail = if (figures.chargedMah >= 1) {
        stringResource(R.string.history_day_detail_charged, screenOn, mahText(figures.chargedMah))
    } else {
        stringResource(R.string.history_day_detail, screenOn)
    }
    Column(
        modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest) else Modifier)
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xxs),
    ) {
        TitleAndValue(label, numberWithUnit(formatNumber(figures.usedMah, 0, locale), mah), MaterialTheme.typography.titleSmall)
        QuietText(detail)
    }
}

/**
 * A row's first line: [title] at the start and its number at the end, on one baseline; the lines under it get the
 * full width. When the title would wrap beside the number (a long time range at a large font), the number moves under
 * the whole title instead of sitting beside its first line.
 */
@Composable
private fun TitleAndValue(title: String, value: AnnotatedString, titleStyle: TextStyle, modifier: Modifier = Modifier) {
    val gap = MaterialTheme.spacing.sm
    Layout(
        content = {
            Text(title, style = titleStyle, color = MaterialTheme.colorScheme.onSurface)
            Text(value, style = MaterialTheme.typography.numericBody)
        },
        modifier = modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val number = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val titleWidth = measurables[0].maxIntrinsicWidth(Constraints.Infinity)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else titleWidth + gapPx + number.width
        if (titleWidth + gapPx + number.width <= width) {
            val text = measurables[0].measure(Constraints(maxWidth = width - gapPx - number.width))
            val baseline = max(text[FirstBaseline], number[FirstBaseline])
            val height = max(baseline - text[FirstBaseline] + text.height, baseline - number[FirstBaseline] + number.height)
            layout(width, height) {
                text.placeRelative(0, baseline - text[FirstBaseline])
                number.placeRelative(width - number.width, baseline - number[FirstBaseline])
            }
        } else {
            val text = measurables[0].measure(Constraints(maxWidth = width))
            layout(width, text.height + number.height) {
                text.placeRelative(0, 0)
                number.placeRelative(0, text.height)
            }
        }
    }
}

// ---- Sessions ----

private fun LazyListScope.sessionsItems(
    state: HistoryUiState,
    labels: DayLabels,
    formatter: TimeAxisFormatter,
    twoColumns: Boolean,
    onEvent: (HistoryEvent) -> Unit,
) {
    val sessions: SessionsState = state.sessions
    item(key = "filters") { FilterChips(state.filter, onSelect = { onEvent(HistoryEvent.SelectFilter(it)) }) }
    when {
        sessions.failed -> item(key = "sessions-failed") { LoadFailed(onRetry = { onEvent(HistoryEvent.Retry) }) }
        sessions.loading -> item(key = "sessions-loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        sessions.rows.isEmpty() -> item(key = "sessions-empty") {
            EmptyState(
                title = stringResource(emptySessionsTitle(state.filter)),
                body = stringResource(R.string.history_sessions_empty_body),
                icon = Icons.Rounded.History,
            )
        }
        else -> {
            // Newest first, so each start day is one contiguous group. Two columns read row by row, newest first.
            val open: (String) -> Unit = { onEvent(HistoryEvent.OpenSession(it)) }
            sessions.days.chunked(if (twoColumns) 2 else 1).forEach { line ->
                item(key = "sessions-day-${line.first().epochDay}") {
                    // Side by side, the two day panels of a line share their height.
                    Row(
                        if (twoColumns) Modifier.height(IntrinsicSize.Max) else Modifier,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                    ) {
                        line.forEach { day ->
                            SessionDayPanel(
                                title = labels.full(day.epochDay, state.todayEpochDay),
                                rows = day.rows,
                                formatter = formatter,
                                onOpen = open,
                                modifier = Modifier.weight(1f).then(if (twoColumns) Modifier.fillMaxHeight() else Modifier),
                            )
                        }
                        if (twoColumns && line.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            if (sessions.hasMore) {
                item(key = "sessions-more") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { onEvent(HistoryEvent.LoadMore) }) { Text(stringResource(R.string.history_show_more)) }
                    }
                }
            }
        }
    }
}

private fun emptySessionsTitle(filter: SessionFilter): Int = when (filter) {
    SessionFilter.ALL -> R.string.history_sessions_empty_all
    SessionFilter.DISCHARGE -> R.string.history_sessions_empty_discharge
    SessionFilter.CHARGE -> R.string.history_sessions_empty_charge
}

/** All · Discharge · Charge as tonal filter chips (no outline). */
@Composable
private fun FilterChips(selected: SessionFilter, onSelect: (SessionFilter) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val colors = FilterChipDefaults.filterChipColors(
        containerColor = scheme.surfaceContainer,
        labelColor = scheme.onSurfaceVariant,
        selectedContainerColor = scheme.secondaryContainer,
        selectedLabelColor = scheme.onSecondaryContainer,
    )
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
        SessionFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = { Text(stringResource(filterLabel(filter))) },
                colors = colors,
                border = null,
            )
        }
    }
}

private fun filterLabel(filter: SessionFilter): Int = when (filter) {
    SessionFilter.ALL -> R.string.history_filter_all
    SessionFilter.DISCHARGE -> R.string.history_filter_discharge
    SessionFilter.CHARGE -> R.string.history_filter_charge
}

@Composable
private fun SessionDayPanel(
    title: String,
    rows: List<SessionRow>,
    formatter: TimeAxisFormatter,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    Panel(modifier, title = title, contentPadding = PaddingValues(top = spacing.md, bottom = spacing.xs)) {
        Column { rows.forEach { row -> SessionRowItem(row, formatter, onClick = { onOpen(row.sessionId) }) } }
    }
}

/**
 * Type icon, then the time span with the charge moved at its end ("+" into the battery, "−" out of it), then
 * "duration · level → level" and, on a discharge whose per-app breakdown is missing, a quiet hint saying why.
 * The whole row opens the session.
 */
@Composable
private fun SessionRowItem(row: SessionRow, formatter: TimeAxisFormatter, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.spacing
    val locale = currentLocale()
    val start = formatter.format(row.startMs, TimeGranularity.MINUTES)
    // An end within a day of the start is a time only: the duration under it already says "overnight".
    val end = formatter.format(
        row.endMs,
        if (row.endMs - row.startMs < ChartMath.DAY_MS) TimeGranularity.MINUTES else TimeGranularity.DATE_TIME,
    )
    val span = if (row.recording) {
        stringResource(R.string.history_session_span_now, start)
    } else {
        stringResource(R.string.history_session_span, start, end)
    }
    val noValue = stringResource(R.string.component_no_value)
    val levels = stringResource(
        R.string.history_level_change,
        row.startLevel?.let { formatPercent(it.toDouble()) } ?: noValue,
        row.endLevel?.let { formatPercent(it.toDouble()) } ?: noValue,
    )
    val detail = stringResource(R.string.history_session_detail, durationString(row.endMs - row.startMs), levels)
    val charge = row.chargeMah?.let { mah ->
        val number = formatNumber(mah, 0, locale)
        when {
            number == formatNumber(0.0, 0, locale) -> number
            row.type == SessionType.CHARGE -> "+$number"
            row.type == SessionType.DISCHARGE -> MINUS_SIGN + number
            else -> number
        }
    } ?: noValue
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.history_open_session), onClick = onClick)
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SessionTypeIcon(row.type)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            TitleAndValue(span, numberWithUnit(charge, stringResource(R.string.now_unit_mah)), MaterialTheme.typography.bodyLarge)
            QuietText(detail)
            row.appUsage?.let { hint ->
                Text(
                    stringResource(appUsageHint(hint)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun appUsageHint(hint: AppUsageHint): Int = when (hint) {
    AppUsageHint.NO_ACCESS -> R.string.history_app_usage_no_access
    AppUsageHint.FAILED -> R.string.history_app_usage_failed
}

/** The session's direction as a glyph on a tonal circle: chartreuse charging, amber on battery, neutral otherwise. */
@Composable
private fun SessionTypeIcon(type: SessionType, modifier: Modifier = Modifier) {
    val (icon, tint, label) = when (type) {
        SessionType.CHARGE -> Triple(Icons.Rounded.BatteryChargingFull, MaterialTheme.batColors.charge, R.string.history_type_charge)
        SessionType.DISCHARGE -> Triple(Icons.Rounded.Battery3Bar, MaterialTheme.batColors.drain, R.string.history_type_discharge)
        SessionType.PLUGGED -> Triple(Icons.Rounded.Power, MaterialTheme.colorScheme.onSurfaceVariant, R.string.history_type_plugged)
        SessionType.UNKNOWN -> Triple(Icons.AutoMirrored.Rounded.BatteryUnknown, MaterialTheme.colorScheme.onSurfaceVariant, R.string.history_type_unknown)
    }
    Box(
        modifier
            .size(AppIconDefaults.Size)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = stringResource(label), modifier = Modifier.size(MaterialTheme.spacing.lg), tint = tint)
    }
}

// ---- Shared ----

/** A failed read, as the app's quiet [Notice], with Try again. */
@Composable
private fun LoadFailed(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Notice(
        message = stringResource(R.string.history_failed_body),
        modifier = modifier,
        title = stringResource(R.string.history_failed_title),
        framed = true,
    ) {
        TextButton(onClick = onRetry) { Text(stringResource(R.string.history_retry)) }
    }
}


/** "812 mAh" with the unit at label size (as the chart's unit caption) and in the quieter color. */
@Composable
private fun numberWithUnit(number: String, unit: String): AnnotatedString {
    val quiet = SpanStyle(
        fontSize = MaterialTheme.typography.labelMedium.fontSize,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    return buildAnnotatedString {
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) { append(number) }
        withStyle(quiet) { append(" $unit") }
    }
}

/**
 * Day names for the list ("Today", "Yesterday", "Tue, Oct 7") and the bars ("Tue" for a week, "Oct 7" beyond; when
 * those don't fit, e.g. at a large font, the short "T" / "7" under every bar).
 */
@Stable
private class DayLabels(locale: Locale, private val today: String, private val yesterday: String) {
    private val full = pattern(locale, "MMMEd")
    private val weekday = pattern(locale, "EEE")
    private val narrowWeekday = pattern(locale, "EEEEE")
    private val short = pattern(locale, "MMMd")
    private val dayOfMonth = pattern(locale, "d")

    fun full(epochDay: Long, todayEpochDay: Long): String = when (epochDay) {
        todayEpochDay -> today
        todayEpochDay - 1 -> yesterday
        else -> full.format(LocalDate.ofEpochDay(epochDay))
    }

    fun bar(epochDay: Long, range: DayRange): String =
        (if (range == DayRange.WEEK) weekday else short).format(LocalDate.ofEpochDay(epochDay))

    fun barShort(epochDay: Long, range: DayRange): String =
        (if (range == DayRange.WEEK) narrowWeekday else dayOfMonth).format(LocalDate.ofEpochDay(epochDay))

    private companion object {
        fun pattern(locale: Locale, skeleton: String): DateTimeFormatter =
            DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    }
}

@Composable
private fun rememberDayLabels(): DayLabels {
    val locale = currentLocale()
    val today = stringResource(R.string.history_today)
    val yesterday = stringResource(R.string.history_yesterday)
    return remember(locale, today, yesterday) { DayLabels(locale, today, yesterday) }
}
