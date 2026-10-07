package app.batstats.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batstats.R
import app.batstats.battery.apps.AppLabel
import app.batstats.battery.shizuku.ShizukuBridge
import app.batstats.ui.components.DetailTopBar
import app.batstats.ui.components.AppLabelIcon
import app.batstats.ui.components.InfoSheet
import app.batstats.ui.components.Notice
import app.batstats.ui.components.Panel
import app.batstats.ui.components.QuietText
import app.batstats.ui.components.StatCell
import app.batstats.ui.components.chart.BarChart
import app.batstats.ui.components.chart.BarEntry
import app.batstats.ui.components.chart.BarSegment
import app.batstats.ui.components.chart.BreakdownBar
import app.batstats.ui.components.chart.BreakdownSegment
import app.batstats.ui.components.chart.NumberFormatter
import app.batstats.ui.components.chart.TimeGranularity
import app.batstats.ui.components.chart.rememberTimeAxisFormatter
import app.batstats.ui.components.displayName
import app.batstats.ui.format.currentLocale
import app.batstats.ui.format.dayAwareTime
import app.batstats.ui.format.formatNumber
import app.batstats.ui.format.formatMah
import app.batstats.ui.format.percentUnit
import app.batstats.ui.theme.batColors
import app.batstats.ui.theme.chartColors
import app.batstats.ui.theme.numericHeadline
import app.batstats.ui.theme.spacing
import app.batstats.viewmodel.AppDetailsEvent
import app.batstats.viewmodel.AppDetailsUiState
import app.batstats.viewmodel.AppDetailsViewModel
import app.batstats.viewmodel.AppHistory
import app.batstats.viewmodel.AppHistoryState
import app.batstats.viewmodel.AppUsageDetails
import app.batstats.viewmodel.HardwareUsage
import app.batstats.viewmodel.NetworkUsage
import app.batstats.viewmodel.TaskItem
import app.batstats.viewmodel.WakelockKind
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

/** Lists show this many entries until "Show all". */
private const val LIST_PREVIEW = 5

/** Hardware cells per row. */
private const val HARDWARE_COLUMNS = 3

/**
 * AppDetails, wired: the Koin [AppDetailsViewModel] for [uid]/[packageName], a read of Android's per-app stats when
 * the screen starts (within the 60 s cache when coming from Apps), Shizuku's permission prompt, and "App info"
 * (Android's settings page for the package).
 */
@Composable
fun AppDetailsScreen(
    uid: Int,
    packageName: String,
    onBack: () -> Unit,
    onOpenAccessSetup: () -> Unit,
    modifier: Modifier = Modifier,
    vm: AppDetailsViewModel = koinViewModel(parameters = { parametersOf(uid, packageName) }),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shizuku: ShizukuBridge = koinInject()
    LifecycleStartEffect(vm) {
        vm.onStart()
        onStopOrDispose { vm.onStop() }
    }
    AppDetailsContent(
        state = state,
        onEvent = { event ->
            when (event) {
                AppDetailsEvent.Back -> onBack()
                AppDetailsEvent.OpenAccessSetup -> onOpenAccessSetup()
                AppDetailsEvent.AllowShizuku -> shizuku.requestPermission()
                AppDetailsEvent.OpenAppInfo -> openAppInfo(context, state.packageName)
                else -> vm.onEvent(event)
            }
        },
        modifier = modifier,
    )
}

private fun openAppInfo(context: Context, packageName: String) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Every Android build has this settings page; a device without it simply has nothing to open.
    }
}

/**
 * AppDetails, stateless: [state] in, [onEvent] out. Back and "App info" on top; the app's hero (name, the dump's
 * window, battery used and its share); time by state; the app across stored sessions on battery; then CPU, network,
 * sensors and hardware, wakelocks, alarms, jobs and syncs (each only when Android counted something). Pull to
 * refresh forces a new read. From 840 dp the activity lists move to a second column.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailsContent(
    state: AppDetailsUiState,
    onEvent: (AppDetailsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val twoColumns = LocalWindowInfo.current.containerSize.width / LocalDensity.current.density >= TWO_PANE_MIN_WIDTH_DP
    val column = Arrangement.spacedBy(spacing.sm)
    val usage = state.usage

    val primary: @Composable () -> Unit = {
        StatsProblemNotice(
            state.problem,
            onSetUp = { onEvent(AppDetailsEvent.OpenAccessSetup) },
            onAllowShizuku = { onEvent(AppDetailsEvent.AllowShizuku) },
            onRetry = { onEvent(AppDetailsEvent.Refresh) },
        )
        DetailsHero(state, Modifier.fillMaxWidth())
        usage?.let { TimePanel(it, Modifier.fillMaxWidth()) }
        // Right after "how much now": is this app always a drainer?
        when (val history = state.history) {
            is AppHistoryState.Loaded -> HistoryPanel(history.history, Modifier.fillMaxWidth())
            AppHistoryState.Failed -> HistoryFailedPanel(onRetry = { onEvent(AppDetailsEvent.RetryHistory) }, Modifier.fillMaxWidth())
            AppHistoryState.Loading -> Unit
        }
        if (usage != null) {
            CpuPanel(usage, Modifier.fillMaxWidth())
            usage.network?.let { NetworkPanel(it, Modifier.fillMaxWidth()) }
            HardwarePanel(usage.hardware, Modifier.fillMaxWidth())
            // A light app ends after a few panels: say so, so the empty space below reads as the end.
            if (usage.isSparse) QuietText(stringResource(R.string.apps_details_nothing_else), Modifier.padding(horizontal = spacing.md))
        }
    }
    val secondary: @Composable () -> Unit = {
        if (usage != null) {
            ActivityPanels(usage)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            // The hero names the app; the bar keeps Back and "App info" (Android's settings page for the package).
            DetailTopBar(title = null, onBack = { onEvent(AppDetailsEvent.Back) }) {
                if (state.canOpenAppInfo) {
                    TextButton(onClick = { onEvent(AppDetailsEvent.OpenAppInfo) }) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.apps_details_app_info))
                    }
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { onEvent(AppDetailsEvent.Refresh) },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = spacing.md, end = spacing.md, bottom = spacing.md),
                verticalArrangement = column,
            ) {
                if (twoColumns) {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        Column(Modifier.weight(1f), verticalArrangement = column) { primary() }
                        Column(Modifier.weight(1f), verticalArrangement = column) { secondary() }
                    }
                } else {
                    primary()
                    secondary()
                }
            }
        }
    }
}

/** Nothing past CPU: no network, hardware, wakelocks, alarms, jobs or syncs. */
private val AppUsageDetails.isSparse: Boolean
    get() = network == null && hardware == HardwareUsage() && wakelocks.isEmpty() && alarms.isEmpty() && jobs.isEmpty() && syncs.isEmpty()

/**
 * The app (icon, name), the dump's window, and its battery use with its share of all apps. Without a row in the
 * dump: a line saying Android counted nothing; before the first read: what's happening.
 */
@Composable
private fun DetailsHero(state: AppDetailsUiState, modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.spacing
    val formatter = rememberTimeAxisFormatter()
    val locale = currentLocale()
    val label = state.label ?: AppLabel.Unknown
    Panel(modifier, color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.md), verticalAlignment = Alignment.CenterVertically) {
            AppLabelIcon(state.packageName, label, Modifier.size(spacing.xxl))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                Text(
                    if (state.label == null) "" else label.displayName(),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val captured = state.capturedAtMs
                if (captured != null) {
                    val updated = dayAwareTime(formatter, captured, state.nowMs)
                    QuietText(
                        state.startedAtMs?.let { stringResource(R.string.apps_details_caption_since, dayAwareTime(formatter, it, captured), updated) }
                            ?: stringResource(R.string.apps_updated, updated),
                    )
                }
            }
        }
        val usage = state.usage
        when {
            usage != null -> Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                StatCell(
                    stringResource(R.string.apps_details_battery),
                    formatMah(usage.powerMah, locale),
                    Modifier.weight(1f),
                    unit = stringResource(R.string.now_unit_mah),
                    valueStyle = MaterialTheme.typography.numericHeadline,
                )
                StatCell(
                    stringResource(R.string.apps_details_share),
                    formatNumber(usage.share * 100.0, if (usage.share < 0.1f) 1 else 0, locale),
                    Modifier.weight(1f),
                    unit = percentUnit().sign,
                    unitFirst = percentUnit().first,
                    valueStyle = MaterialTheme.typography.numericHeadline,
                )
            }
            state.capturedAtMs != null -> QuietText(stringResource(R.string.apps_details_not_counted))
            state.problem == null -> QuietText(stringResource(R.string.apps_loading))
        }
    }
}

/** Foreground, foreground service, background and cached time as one bar with a legend. */
@Composable
private fun TimePanel(usage: AppUsageDetails, modifier: Modifier = Modifier) {
    val chart = MaterialTheme.chartColors
    val segments = listOf(
        timeSegment(stringResource(R.string.apps_details_time_foreground), usage.foregroundMs, chart.drain),
        timeSegment(stringResource(R.string.apps_details_time_fgs), usage.foregroundServiceMs, chart.drainSecondary),
        timeSegment(stringResource(R.string.apps_details_time_background), usage.backgroundMs, MaterialTheme.batColors.info),
        timeSegment(stringResource(R.string.apps_details_time_cached), usage.cachedMs, chart.level),
    ).filter { it.value > 0 }
    if (segments.isEmpty()) return
    Panel(
        modifier,
        title = stringResource(R.string.apps_details_time_title),
        trailing = { InfoSheet(stringResource(R.string.apps_details_time_info_title), stringResource(R.string.apps_details_time_info_body)) },
    ) {
        BreakdownBar(segments, format = rememberDurationFormatter())
    }
}

private fun timeSegment(label: String, ms: Long?, color: Color) = BreakdownSegment(label, (ms ?: 0L).toDouble(), color)

/** CPU time and time kept awake; says so when the app had no wakelocks, alarms, jobs or syncs. */
@Composable
private fun CpuPanel(usage: AppUsageDetails, modifier: Modifier = Modifier) {
    val quietApp = usage.wakelocks.isEmpty() && usage.alarms.isEmpty() && usage.jobs.isEmpty() && usage.syncs.isEmpty()
    if (usage.cpuTimeMs == null && usage.wakelockTimeMs == null && !quietApp) return
    Panel(modifier, title = stringResource(R.string.apps_details_cpu_title)) {
        if (usage.cpuTimeMs != null || usage.wakelockTimeMs != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
                DurationCell(stringResource(R.string.apps_details_cpu_time), usage.cpuTimeMs, Modifier.weight(1f))
                DurationCell(stringResource(R.string.apps_details_kept_awake), usage.wakelockTimeMs, Modifier.weight(1f))
            }
        }
        if (quietApp) QuietText(stringResource(R.string.apps_details_no_background_work))
    }
}

/** Wakelocks, alarms, jobs and syncs: one panel each, only when there's something in it. */
@Composable
private fun ActivityPanels(usage: AppUsageDetails) {
    val cpu = stringResource(R.string.apps_details_wakelock_cpu)
    val screen = stringResource(R.string.apps_details_wakelock_screen)
    if (usage.wakelocks.isNotEmpty()) {
        ListPanel(
            title = stringResource(R.string.apps_details_wakelocks_title),
            info = R.string.apps_details_wakelocks_info_title to R.string.apps_details_wakelocks_info_body,
            items = usage.wakelocks,
        ) { lock ->
            DetailItem(
                title = lock.tag,
                value = appDurationText(lock.totalMs),
                supporting = stringResource(
                    R.string.apps_details_joined,
                    pluralStringResource(R.plurals.apps_details_times, lock.count, lock.count),
                    if (lock.kind == WakelockKind.CPU) cpu else screen,
                ),
            )
        }
    }
    if (usage.alarms.isNotEmpty()) {
        ListPanel(stringResource(R.string.apps_details_alarms_title), usage.alarms) { alarm ->
            DetailItem(title = alarm.tag, value = pluralStringResource(R.plurals.apps_details_wakeups, alarm.wakeups, alarm.wakeups))
        }
    }
    if (usage.jobs.isNotEmpty()) {
        ListPanel(stringResource(R.string.apps_details_jobs_title), usage.jobs) { job -> TaskRow(job, R.plurals.apps_details_runs) }
    }
    if (usage.syncs.isNotEmpty()) {
        ListPanel(stringResource(R.string.apps_details_syncs_title), usage.syncs) { sync -> TaskRow(sync, R.plurals.apps_details_syncs) }
    }
}

@Composable
private fun TaskRow(task: TaskItem, countPlural: Int) {
    DetailItem(
        title = task.name,
        value = appDurationText(task.totalMs),
        supporting = pluralStringResource(countPlural, task.count, task.count),
    )
}

/** A titled list showing the first [LIST_PREVIEW] entries, with "Show all" for the rest. */
@Composable
private fun <T> ListPanel(
    title: String,
    items: List<T>,
    modifier: Modifier = Modifier,
    info: Pair<Int, Int>? = null,
    item: @Composable (T) -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    Panel(
        modifier.fillMaxWidth(),
        title = title,
        trailing = info?.let { (infoTitle, infoBody) -> { InfoSheet(stringResource(infoTitle), stringResource(infoBody)) } },
    ) {
        (if (expanded) items else items.take(LIST_PREVIEW)).forEach { item(it) }
        if (items.size > LIST_PREVIEW) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    if (expanded) stringResource(R.string.apps_details_show_fewer)
                    else stringResource(R.string.apps_details_show_all, items.size),
                )
            }
        }
    }
}

/** Mobile data and Wi-Fi (total, with received ↓ and sent ↑), and time with the mobile radio active. */
@Composable
private fun NetworkPanel(network: NetworkUsage, modifier: Modifier = Modifier) {
    Panel(modifier, title = stringResource(R.string.apps_details_network_title)) {
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
            TrafficCell(stringResource(R.string.apps_details_mobile), network.mobileRxBytes, network.mobileTxBytes, Modifier.weight(1f))
            TrafficCell(stringResource(R.string.apps_details_wifi), network.wifiRxBytes, network.wifiTxBytes, Modifier.weight(1f))
        }
        network.radioActiveMs?.takeIf { it > 0 }?.let { radio ->
            DurationCell(stringResource(R.string.apps_details_radio), radio)
        }
    }
}

@Composable
private fun TrafficCell(label: String, rx: Long?, tx: Long?, modifier: Modifier = Modifier) {
    if (rx == null && tx == null) {
        StatCell(label, stringResource(R.string.component_no_value), modifier)
        return
    }
    val size = byteSize((rx ?: 0L) + (tx ?: 0L), currentLocale())
    StatCell(
        label,
        size.value,
        modifier,
        unit = stringResource(size.unit),
        supporting = stringResource(R.string.apps_details_traffic, bytesText(rx ?: 0L), bytesText(tx ?: 0L)),
    )
}

/** GPS, sensors, camera, flashlight, audio, video and Bluetooth scans: the ones Android counted, three to a row. */
@Composable
private fun HardwarePanel(hardware: HardwareUsage, modifier: Modifier = Modifier) {
    val cells = listOfNotNull(
        hardware.gpsMs?.let { R.string.apps_details_gps to it },
        hardware.sensorsMs?.let { R.string.apps_details_sensors to it },
        hardware.cameraMs?.let { R.string.apps_details_camera to it },
        hardware.flashlightMs?.let { R.string.apps_details_flashlight to it },
        hardware.audioMs?.let { R.string.apps_details_audio to it },
        hardware.videoMs?.let { R.string.apps_details_video to it },
        hardware.bluetoothScanMs?.let { R.string.apps_details_bluetooth_scan to it },
    )
    if (cells.isEmpty()) return
    Panel(modifier, title = stringResource(R.string.apps_details_hardware_title)) {
        cells.chunked(HARDWARE_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
                row.forEach { (label, ms) -> DurationCell(stringResource(label), ms, Modifier.weight(1f)) }
                // Keep the grid: a short last row leaves its columns empty instead of stretching.
                repeat(HARDWARE_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The history panel when its read failed: say so (not "no sessions") and offer to read it again. */
@Composable
private fun HistoryFailedPanel(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Panel(modifier, title = stringResource(R.string.apps_details_history_title)) {
        Notice(message = stringResource(R.string.apps_details_history_failed)) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.apps_retry)) }
        }
    }
}

/** This app's mAh in each stored session on battery (oldest first; 0 outside a session's top 30), tap to select. */
@Composable
private fun HistoryPanel(history: AppHistory, modifier: Modifier = Modifier) {
    Panel(
        modifier,
        title = stringResource(R.string.apps_details_history_title),
        trailing = { InfoSheet(stringResource(R.string.apps_details_history_info_title), stringResource(R.string.apps_details_history_info_body)) },
    ) {
        if (history.sessions.isEmpty()) {
            QuietText(stringResource(R.string.apps_details_history_empty))
            return@Panel
        }
        QuietText(stringResource(R.string.apps_details_history_caption, history.listedIn, history.sessions.size))
        val formatter = rememberTimeAxisFormatter()
        val mah = stringResource(R.string.now_unit_mah)
        // Saved by session, so a new session (or a refresh) never moves the selection to another bar.
        var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
        BarChart(
            entries = history.sessions.map { BarEntry(formatter.format(it.startMs, TimeGranularity.DAYS), listOf(it.powerMah ?: 0.0)) },
            segments = listOf(BarSegment(stringResource(R.string.apps_details_history_series), MaterialTheme.chartColors.drain)),
            modifier = Modifier.fillMaxWidth(),
            unit = mah,
            format = NumberFormatter(mah),
            selectedIndex = history.sessions.indexOfFirst { it.sessionId == selectedId }.takeIf { it >= 0 },
            onSelect = { index -> selectedId = index?.let { history.sessions.getOrNull(it)?.sessionId } },
        )
    }
}
