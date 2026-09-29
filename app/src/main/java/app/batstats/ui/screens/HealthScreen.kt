package app.batstats.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batstats.R
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.measurement.CapacityConfidence
import app.batstats.settings.DesignCapacity
import app.batstats.ui.components.DetailTopBar
import app.batstats.ui.components.EmptyState
import app.batstats.ui.components.InfoSheet
import app.batstats.ui.components.Notice
import app.batstats.ui.components.Panel
import app.batstats.ui.components.QuietText
import app.batstats.ui.components.StatCell
import app.batstats.ui.components.chart.ChartDefaults
import app.batstats.ui.components.chart.ChartReference
import app.batstats.ui.components.chart.ChartSeries
import app.batstats.ui.components.chart.NumberFormatter
import app.batstats.ui.components.chart.TimeAxisFormatter
import app.batstats.ui.components.chart.TimeGranularity
import app.batstats.ui.components.chart.TimePoint
import app.batstats.ui.components.chart.TimeSeriesChart
import app.batstats.ui.components.chart.rememberTimeAxisFormatter
import app.batstats.ui.components.headerActionOverhang
import app.batstats.ui.format.currentLocale
import app.batstats.ui.format.formatNumber
import app.batstats.ui.format.styledTemplate
import app.batstats.ui.format.unitSpan
import app.batstats.ui.format.percentAnnotated
import app.batstats.ui.theme.batColors
import app.batstats.ui.theme.numericBody
import app.batstats.ui.theme.numericDisplay
import app.batstats.ui.theme.spacing
import app.batstats.viewmodel.CapacityPoint
import app.batstats.viewmodel.CycleCountState
import app.batstats.viewmodel.DesignCapacityState
import app.batstats.viewmodel.DesignSource
import app.batstats.viewmodel.HealthFigures
import app.batstats.viewmodel.HealthUiState
import app.batstats.viewmodel.HealthViewModel
import org.koin.androidx.compose.koinViewModel

/** Two columns from this window width (the Material "expanded" breakpoint), as on Now. */
private const val TWO_COLUMN_MIN_WIDTH_DP = 840

/** The trend line needs two estimates; fewer show an empty state. */
private const val MIN_TREND_POINTS = 2

/** How many of the newest estimates are listed under the trend. */
private const val RECENT_ESTIMATES = 5

private const val FULL_PERCENT = 100.0

/** The widest mAh/cycle value the readouts size for, so the three cells share one value size. */
private const val READOUT_TEMPLATE = 8_888.0

/**
 * Health, wired: the Koin [HealthViewModel]. Navigation leaves through the lambdas: back, Settings (design capacity)
 * and a session's details from the estimates list.
 */
@Composable
fun HealthScreen(
    onBack: () -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
    modifier: Modifier = Modifier,
    vm: HealthViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    HealthContent(state, onBack, vm::setDesignCapacity, onOpenSession, modifier)
}

/**
 * Health, stateless. The pinned detail header, then the hero (health % of design with its bar, or why it can't be
 * shown yet, and the capacity / design / cycles readouts behind it), the capacity trend and the latest estimates.
 * Every estimate is shown with its confidence; how they're measured is in the hero's ⓘ. Without a design capacity,
 * "Set design capacity" opens the Settings dialog right here and [onSetDesignCapacity] writes it. From 840 dp the hero
 * and the history sit side by side.
 */
@Composable
fun HealthContent(
    state: HealthUiState,
    onBack: () -> Unit,
    onSetDesignCapacity: (mAh: Int) -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val twoColumns = LocalWindowInfo.current.containerSize.width / LocalDensity.current.density >= TWO_COLUMN_MIN_WIDTH_DP
    val column = Arrangement.spacedBy(spacing.sm)
    var editDesign by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = { DetailTopBar(stringResource(R.string.health_title), onBack) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = spacing.md, end = spacing.md, bottom = spacing.md),
            verticalArrangement = column,
        ) {
            if (!state.loaded) return@Column
            HealthBody(state, twoColumns, onEditDesign = { editDesign = true }, onOpenSession)
        }
    }

    if (editDesign) {
        DesignCapacityDialog(
            current = (state.design as? DesignCapacityState.Known)?.takeIf { it.source == DesignSource.SETTINGS }?.mah
                ?: DesignCapacity.AUTO,
            onSave = { mAh ->
                editDesign = false
                onSetDesignCapacity(mAh)
            },
            onDismiss = { editDesign = false },
        )
    }
}

@Composable
private fun HealthBody(state: HealthUiState, twoColumns: Boolean, onEditDesign: () -> Unit, onOpenSession: (String) -> Unit) {
    val spacing = MaterialTheme.spacing
    val column = Arrangement.spacedBy(spacing.sm)
    if (state.designWriteFailed) Notice(stringResource(R.string.settings_write_failed), framed = true)
    val hero: @Composable () -> Unit = { HealthHero(state, onEditDesign, Modifier.fillMaxWidth()) }
    val trend: @Composable () -> Unit = { TrendPanel(state.estimates, state.design, Modifier.fillMaxWidth()) }
    val listed = state.estimates.isNotEmpty()
    val list: @Composable () -> Unit = { EstimatesPanel(state.estimates, onOpenSession, Modifier.fillMaxWidth()) }
    if (twoColumns) {
        // Hero + trend beside the list keeps the columns close in height; without a list the trend moves over.
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Column(Modifier.weight(1f), verticalArrangement = column) {
                hero()
                if (listed) trend()
            }
            Column(Modifier.weight(1f), verticalArrangement = column) { if (listed) list() else trend() }
        }
    } else {
        hero()
        trend()
        if (listed) list()
    }
}

/**
 * Health % of design with its bar when both an estimate and a design capacity exist; otherwise what's missing. The
 * readouts below always show the inputs (capacity with its confidence, design with its source, cycles on API 34+),
 * and the Settings action shows whenever no design capacity is known.
 */
@Composable
private fun HealthHero(state: HealthUiState, onEditDesign: () -> Unit, modifier: Modifier = Modifier) {
    Panel(modifier, color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.health_label),
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Box(Modifier.headerActionOverhang(MaterialTheme.spacing.sm)) {
                InfoSheet(stringResource(R.string.health_info_title), stringResource(R.string.health_info_body))
            }
        }
        val summary = state.summary
        val percent = summary?.healthPercent
        when {
            percent != null -> {
                HealthFigure(percent)
                HealthBar(percent)
            }
            summary == null -> Message(stringResource(R.string.health_no_estimate), stringResource(R.string.health_no_estimate_body))
            state.design == DesignCapacityState.Checking -> QuietText(stringResource(R.string.health_checking_design))
            else -> Message(stringResource(R.string.health_needs_design), stringResource(R.string.health_needs_design_body))
        }
        Readouts(summary, state.design, state.cycles)
        if (state.design == DesignCapacityState.Unknown) {
            FilledTonalButton(onClick = onEditDesign, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.health_set_design))
            }
        }
    }
}

/** "94 % of design capacity" read as one phrase; the caption drops under the number when both don't fit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HealthFigure(percent: Double) {
    val style = MaterialTheme.typography.numericDisplay
    val number = formatNumber(percent, 0, currentLocale())
    FlowRow(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs),
        itemVerticalAlignment = Alignment.Bottom,
    ) {
        Text(
            percentAnnotated(number, unitSpan(style)),
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(R.string.health_of_design),
            // Lifts the caption from the display line's descent onto roughly the number's baseline.
            modifier = Modifier.padding(bottom = MaterialTheme.spacing.xs),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Capacity as a share of design, full at 100 % (a new battery can read above it). Decorative: the number is read. */
@Composable
private fun HealthBar(percent: Double) {
    val fraction = (percent / FULL_PERCENT).coerceIn(0.0, 1.0).toFloat()
    val shape = MaterialTheme.shapes.extraSmall
    Box(
        Modifier
            .fillMaxWidth()
            .height(MaterialTheme.spacing.sm)
            .clearAndSetSemantics { }
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, shape),
    ) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(MaterialTheme.batColors.info, shape))
    }
}

@Composable
private fun Message(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        QuietText(body)
    }
}


/** Capacity · Design · Cycles (the last only where Android reports cycles, API 34+). */
@Composable
private fun Readouts(summary: HealthFigures?, design: DesignCapacityState, cycles: CycleCountState) {
    val locale = currentLocale()
    val noValue = stringResource(R.string.component_no_value)
    val mah = stringResource(R.string.health_unit_mah)
    val template = formatNumber(READOUT_TEMPLATE, 0, locale)
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
        StatCell(
            stringResource(R.string.health_capacity),
            summary?.let { formatNumber(it.capacityMah.toDouble(), 0, locale) } ?: noValue,
            Modifier.weight(1f),
            unit = mah.takeIf { summary != null },
            supporting = summary?.let { stringResource(confidenceLabel(it.confidence)) } ?: stringResource(R.string.health_capacity_none),
            sizingTemplate = template,
        )
        val known = design as? DesignCapacityState.Known
        StatCell(
            stringResource(R.string.health_design),
            known?.let { formatNumber(it.mah.toDouble(), 0, locale) } ?: noValue,
            Modifier.weight(1f),
            unit = mah.takeIf { known != null },
            supporting = stringResource(designLabel(design)),
            sizingTemplate = template,
        )
        if (cycles != CycleCountState.Unsupported) {
            val count = cycles as? CycleCountState.Count
            StatCell(
                stringResource(R.string.health_cycles),
                count?.let { formatNumber(it.cycles.toDouble(), 0, locale) } ?: noValue,
                Modifier.weight(1f),
                supporting = if (count == null) stringResource(R.string.health_cycles_not_reported) else null,
                sizingTemplate = template,
            )
        }
    }
}

private fun confidenceLabel(confidence: CapacityConfidence): Int = when (confidence) {
    CapacityConfidence.LOW -> R.string.health_confidence_low
    CapacityConfidence.MEDIUM -> R.string.health_confidence_medium
    CapacityConfidence.HIGH -> R.string.health_confidence_high
}

private fun designLabel(design: DesignCapacityState): Int = when (design) {
    DesignCapacityState.Checking -> R.string.health_design_checking
    DesignCapacityState.Unknown -> R.string.health_design_not_set
    is DesignCapacityState.Known -> when (design.source) {
        DesignSource.SETTINGS -> R.string.health_design_settings
        DesignSource.BATTERY -> R.string.health_design_battery
    }
}

/** Every estimate as a point over time, the design capacity as a dashed reference; an empty state under two points. */
@Composable
private fun TrendPanel(estimates: List<CapacityPoint>, design: DesignCapacityState, modifier: Modifier = Modifier) {
    Panel(modifier, title = stringResource(R.string.health_trend_title)) {
        if (estimates.size < MIN_TREND_POINTS) {
            val (title, body) = if (estimates.isEmpty()) {
                R.string.health_trend_empty_title to R.string.health_trend_empty_body
            } else {
                R.string.health_trend_one_title to R.string.health_trend_one_body
            }
            EmptyState(stringResource(title), body = stringResource(body), icon = Icons.AutoMirrored.Rounded.ShowChart)
            return@Panel
        }
        val label = stringResource(R.string.health_trend_series)
        val unit = stringResource(R.string.health_unit_mah)
        val style = ChartDefaults.solidStyle(MaterialTheme.batColors.info)
        val series = remember(estimates, label, unit, style) {
            listOf(
                ChartSeries(
                    label = label,
                    points = estimates.map { TimePoint(it.timeMs, it.capacityMah.toDouble()) },
                    style = style,
                    unit = unit,
                    format = NumberFormatter(unit, maxDecimals = 0),
                    showPoints = true,
                ),
            )
        }
        val designLabel = stringResource(R.string.health_trend_design)
        val references = remember(design, designLabel) {
            (design as? DesignCapacityState.Known)?.let { listOf(ChartReference(it.mah.toDouble(), designLabel)) }.orEmpty()
        }
        TimeSeriesChart(series, Modifier.fillMaxWidth(), references = references, markLatest = true)
    }
}

/** The newest few estimates, each with its confidence and the session it came from (tap → session details). */
@Composable
private fun EstimatesPanel(estimates: List<CapacityPoint>, onOpenSession: (String) -> Unit, modifier: Modifier = Modifier) {
    val formatter = rememberTimeAxisFormatter()
    Panel(
        modifier,
        title = stringResource(R.string.health_estimates_title),
        contentPadding = PaddingValues(vertical = MaterialTheme.spacing.md),
    ) {
        Column {
            estimates.asReversed().take(RECENT_ESTIMATES).forEach { point ->
                EstimateRow(point, formatter, onOpen = { onOpenSession(point.sessionId) })
            }
        }
    }
}

@Composable
private fun EstimateRow(point: CapacityPoint, formatter: TimeAxisFormatter, onOpen: () -> Unit) {
    val spacing = MaterialTheme.spacing
    val locale = currentLocale()
    val numberStyle = MaterialTheme.typography.numericBody
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.health_estimate_open), onClick = onOpen)
            .heightIn(min = spacing.xxl)
            .padding(horizontal = spacing.md, vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                formatter.format(point.timeMs, TimeGranularity.DAYS),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            QuietText(sessionLabel(point))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                styledTemplate(
                    stringResource(R.string.health_estimate_mah),
                    listOf(formatNumber(point.capacityMah.toDouble(), 0, locale)),
                    unitSpan(numberStyle),
                ),
                style = numberStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            QuietText(stringResource(confidenceLabel(point.confidence)))
        }
    }
}

/** "Charge · 18%–80%", or just the kind when a level is missing. */
@Composable
private fun sessionLabel(point: CapacityPoint): String {
    val locale = currentLocale()
    val start = point.startLevel
    val end = point.endLevel
    val levels = if (start != null && end != null) {
        listOf(formatNumber(start.toDouble(), 0, locale), formatNumber(end.toDouble(), 0, locale))
    } else null
    return when (point.type) {
        SessionType.CHARGE -> levels?.let { stringResource(R.string.health_estimate_charge, it[0], it[1]) }
            ?: stringResource(R.string.health_estimate_charge_plain)
        SessionType.DISCHARGE -> levels?.let { stringResource(R.string.health_estimate_discharge, it[0], it[1]) }
            ?: stringResource(R.string.health_estimate_discharge_plain)
        SessionType.PLUGGED, SessionType.UNKNOWN -> stringResource(R.string.health_estimate_session)
    }
}
