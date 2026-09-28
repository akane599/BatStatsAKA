package app.batstats.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import app.batstats.battery.drain.formatDrainRate
import app.batstats.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.drain.MonitoringText
import app.batstats.battery.measurement.ObservationSummary
import app.batstats.battery.shizuku.ShizukuBridge
import app.batstats.battery.util.ShellRunner
import app.batstats.battery.util.TimeEstimator
import app.batstats.settings.AppSettings
import app.batstats.ui.components.TelemetryChart
import app.batstats.ui.components.ChartPoint
import app.batstats.viewmodel.DashboardViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Everything [DashboardContent] renders, as plain values collected by [DashboardScreen]. */
@Immutable
data class DashboardUiState(
    val reading: BatteryRepository.Realtime = BatteryRepository.Realtime(),
    val observing: Boolean = false,
    val summary: ObservationSummary = ObservationSummary(),
    val error: String? = null,
    val samples: List<BatterySample> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val shizukuRunning: Boolean = false,
    val shizukuGranted: Boolean = false,
    val access: ShellRunner.Mode = ShellRunner.Mode.NONE,
)

@Composable
fun DashboardScreen(
    onOpenHistory: () -> Unit, onOpenAlarms: () -> Unit, onOpenSettings: () -> Unit,
    onOpenData: () -> Unit, onOpenDetailedStats: () -> Unit, onOpenDrainStats: () -> Unit, onOpenDiagnostics: () -> Unit,
    vm: DashboardViewModel = koinViewModel(),
) {
    val reading by vm.realtime.collectAsStateWithLifecycle()
    val observing by vm.isMonitoring.collectAsStateWithLifecycle()
    val summary by vm.observation.collectAsStateWithLifecycle()
    val error by vm.collectionError.collectAsStateWithLifecycle()
    val samples by vm.recentSamples.collectAsStateWithLifecycle(emptyList())
    val settings by vm.settings.collectAsStateWithLifecycle()
    val shizuku: ShizukuBridge = koinInject()
    val shell: ShellRunner = koinInject()
    val running by shizuku.running.collectAsStateWithLifecycle()
    val granted by shizuku.granted.collectAsStateWithLifecycle()
    val access by shell.access.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose {} }
    LaunchedEffect(running, granted) { shell.detectMode(forceRefresh = true) }
    DashboardContent(
        state = DashboardUiState(
            reading = reading,
            observing = observing,
            summary = summary,
            error = error,
            samples = samples,
            settings = settings,
            shizukuRunning = running,
            shizukuGranted = granted,
            access = access,
        ),
        onToggleMonitoring = vm::toggleMonitoring,
        onRefresh = vm::refresh,
        onRequestShizukuPermission = { shizuku.requestPermission() },
        onOpenHistory = onOpenHistory,
        onOpenAlarms = onOpenAlarms,
        onOpenSettings = onOpenSettings,
        onOpenData = onOpenData,
        onOpenDetailedStats = onOpenDetailedStats,
        onOpenDrainStats = onOpenDrainStats,
        onOpenDiagnostics = onOpenDiagnostics,
    )
}

/** Stateless dashboard body; [DashboardScreen] supplies state and routes every event. */
@Composable
fun DashboardContent(
    state: DashboardUiState,
    onToggleMonitoring: () -> Unit,
    onRefresh: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAlarms: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenData: () -> Unit,
    onOpenDetailedStats: () -> Unit,
    onOpenDrainStats: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val text = remember(context) { MonitoringText(context) }
    val reading = state.reading
    val observing = state.observing
    val summary = state.summary
    val error = state.error
    val samples = state.samples
    val settings = state.settings
    val running = state.shizukuRunning
    val granted = state.shizukuGranted
    val access = state.access
    Scaffold(modifier = modifier, topBar = {
        TopAppBar(title = { Text("BatStats") }, actions = {
            IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, stringResource(R.string.settings)) }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(if (observing) R.string.monitor_active else R.string.monitor_stopped), style = MaterialTheme.typography.labelLarge)
                        Text(reading.level?.let { "$it%" } ?: "—", style = MaterialTheme.typography.displayLarge)
                        Text(text.state(reading.powerState), style = MaterialTheme.typography.titleLarge)
                        reading.level?.let { level -> LinearProgressIndicator(progress = { level / 100f }, modifier = Modifier.fillMaxWidth()) }
                        Text(reading.sample?.timestamp?.let { stringResource(R.string.monitor_read_at, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(it))) }
                            ?: stringResource(R.string.monitor_waiting_battery), style = MaterialTheme.typography.bodySmall)
                        Text((if (observing || reading.sample?.status == 2) TimeEstimator.etaString(context, reading.sample) else null) ?: stringResource(R.string.monitor_eta_unavailable))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onToggleMonitoring) { Text(stringResource(if (observing) R.string.monitor_stop else R.string.start_monitoring)) }
                            TextButton(onClick = onRefresh) { Text(stringResource(R.string.diagnostic_refresh)) }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(when {
                            running && granted -> R.string.monitor_shizuku_ready
                            running -> R.string.monitor_shizuku_permission
                            access == ShellRunner.Mode.ROOT -> R.string.diag_access_root
                            access == ShellRunner.Mode.ADB -> R.string.diag_access_adb
                            else -> R.string.monitor_standard
                        }), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.monitor_access_help), style = MaterialTheme.typography.bodySmall)
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (running && !granted) TextButton(onClick = onRequestShizukuPermission) { Text(stringResource(R.string.adv_authorize)) }
                            TextButton(onClick = onOpenDetailedStats) { Text(stringResource(R.string.adv_title)) }
                            TextButton(onClick = onOpenDiagnostics) { Text(stringResource(R.string.diagnostics_title)) }
                        }
                    }
                }
            }
            item {
                val current = reading.sample?.currentNowUa?.let { formatDrainRate(it / 1000.0) } ?: "—"
                val temp = reading.temperatureC?.let {
                    if (settings.temperatureUnitIndex == 1) String.format(Locale.getDefault(), "%.1f °F", it * 1.8 + 32)
                    else String.format(Locale.getDefault(), "%.1f °C", it)
                } ?: "—"
                val values = listOf(
                    stringResource(R.string.session_net_current) to current,
                    stringResource(R.string.session_voltage) to (reading.voltageMv?.let { "$it mV" } ?: "—"),
                    stringResource(R.string.monitor_derived_power) to (reading.powerMw?.let {
                        String.format(Locale.getDefault(), if (kotlin.math.abs(it) in 0.0..<1.0 && it != 0.0) "%.2g mW" else "%.0f mW", it)
                    } ?: "—"),
                    stringResource(R.string.session_temperature) to temp
                )
                val config = LocalConfiguration.current
                val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    values.chunked(if (config.fontScale > 1.3f || windowWidth < 360.dp) 1 else 2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { (label, value) -> ReadingTile(label, value, Modifier.weight(1f)) }
                        }
                    }
                }
                Text(stringResource(R.string.monitor_direction_help), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                if (app.batstats.battery.measurement.BatteryReading.directionConflicts(reading.sample?.currentNowUa, reading.powerState)) {
                    Text(stringResource(R.string.monitor_direction_conflict), color = MaterialTheme.colorScheme.error)
                }
            }
            item {
                Text(text.since(summary), style = MaterialTheme.typography.titleMedium)
                ObservationCards(summary)
                TextButton(onClick = onOpenDrainStats) { Text(stringResource(R.string.monitor_details)) }
            }
            item {
                TelemetryChart(stringResource(R.string.session_net_current), "mA", samples.map { ChartPoint(it.timestamp, it.currentNowUa?.div(1000.0), it.observationId, it.boundaryReason != null) })
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onOpenHistory) { Text(stringResource(R.string.history)) }
                    OutlinedButton(onClick = onOpenData) { Text(stringResource(R.string.data_export_import)) }
                    OutlinedButton(onClick = onOpenAlarms) { Text(stringResource(R.string.settings_notifications)) }
                }
            }
        }
    }
}

@Composable
private fun ReadingTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.headlineSmall)
    } }
}
