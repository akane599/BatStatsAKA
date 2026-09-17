package app.batstats.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batstats.battery.drain.MonitoringText
import app.batstats.battery.shizuku.ShizukuBridge
import app.batstats.battery.util.ShellRunner
import app.batstats.battery.util.TimeEstimator
import app.batstats.ui.components.TelemetryChart
import app.batstats.ui.components.ChartPoint
import app.batstats.viewmodel.DashboardViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(
    onOpenHistory: () -> Unit, onOpenAlarms: () -> Unit, onOpenSettings: () -> Unit,
    onOpenData: () -> Unit, onOpenDetailedStats: () -> Unit, onOpenDrainStats: () -> Unit,
    vm: DashboardViewModel = koinViewModel()
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
    var showSources by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose {} }
    LaunchedEffect(running, granted) { shell.detectMode(forceRefresh = true) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("BatStats") }, actions = {
            IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "Settings") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (observing) "Monitoring active" else "Monitoring stopped", style = MaterialTheme.typography.labelLarge)
                        Text(reading.level?.let { "$it%" } ?: "—", style = MaterialTheme.typography.displayLarge)
                        Text(MonitoringText.state(reading.powerState), style = MaterialTheme.typography.titleLarge)
                        reading.level?.let { level -> LinearProgressIndicator(progress = { level / 100f }, modifier = Modifier.fillMaxWidth()) }
                        Text(reading.sample?.timestamp?.let { "Reading ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(it))}" }
                            ?: "Waiting for Android battery information", style = MaterialTheme.typography.bodySmall)
                        Text(TimeEstimator.etaString(reading.sample) ?: "Remaining time: insufficient data")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = vm::toggleMonitoring) { Text(if (observing) "Stop monitoring" else "Start monitoring") }
                            TextButton(onClick = vm::refresh) { Text("Refresh reading") }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(when {
                            running && granted -> "Shizuku connected and authorized"
                            running -> "Shizuku needs authorization"
                            access == ShellRunner.Mode.ROOT -> "Root access selected"
                            access == ShellRunner.Mode.ADB -> "ADB-granted access selected"
                            else -> "Standard battery information available"
                        }, style = MaterialTheme.typography.titleMedium)
                        Text("App estimates, wakelocks and system activity require advanced access.", style = MaterialTheme.typography.bodySmall)
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (running && !granted) TextButton(onClick = { shizuku.requestPermission() }) { Text("Authorize Shizuku") }
                            TextButton(onClick = onOpenDetailedStats) { Text("Advanced statistics") }
                            TextButton(onClick = { showSources = true }) { Text("Reading sources") }
                        }
                    }
                }
            }
            item {
                val current = reading.sample?.currentNowUa?.let {
                    if (settings.showCurrentInMa) String.format(Locale.getDefault(), "%.0f mA", it / 1000.0) else "$it µA"
                } ?: "—"
                val temp = reading.temperatureC?.let {
                    if (settings.temperatureUnitIndex == 1) String.format(Locale.getDefault(), "%.1f °F", it * 1.8 + 32)
                    else String.format(Locale.getDefault(), "%.1f °C", it)
                } ?: "—"
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ReadingTile("Net current", current, Modifier.weight(1f))
                        ReadingTile("Voltage", reading.voltageMv?.let { "$it mV" } ?: "—", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ReadingTile("Derived net power", reading.powerMw?.let { String.format(Locale.getDefault(), "%.0f mW", it) } ?: "—", Modifier.weight(1f))
                        ReadingTile("Temperature", temp, Modifier.weight(1f))
                    }
                }
                Text("Positive current/power flows into the battery; negative flows out. Missing readings stay blank.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            item {
                Text(MonitoringText.since(summary), style = MaterialTheme.typography.titleMedium)
                ObservationCards(summary)
                TextButton(onClick = onOpenDrainStats) { Text("Observation details and reset") }
            }
            item {
                TelemetryChart("Net current", "mA", samples.map { ChartPoint(it.timestamp, it.currentNowUa?.div(1000.0), it.observationId, it.boundaryReason != null) })
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onOpenHistory) { Text("History") }
                    OutlinedButton(onClick = onOpenData) { Text("Export / import") }
                    OutlinedButton(onClick = onOpenAlarms) { Text("Battery alerts") }
                }
            }
        }
    }
    if (showSources) AlertDialog(onDismissRequest = { showSources = false }, title = { Text("Reading sources and limits") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("BatteryManager and Android's battery broadcast provide level, status, voltage, temperature and supported counters. No privileged access is needed.")
            Text("Charge counter: ${reading.sample?.chargeCounterUah?.let { "$it µAh" } ?: "Unavailable"}")
            Text("Hardware average current: ${reading.sample?.currentAverageUa?.let { "$it µA" } ?: "Unavailable"}")
            Text("Remaining energy: ${reading.sample?.energyNwh?.let { String.format(Locale.getDefault(), "%.1f mWh", it / 1_000_000.0) } ?: "Unavailable"}")
            Text("Cycle count: ${reading.sample?.cycleCount ?: "Unavailable"}")
            Text("${reading.sample?.etaBasis ?: "No stable remaining-time estimate"}. Discharge estimates need at least 10 minutes of consistent counter data. These readings do not measure battery-health percentage.")
        } }, confirmButton = { TextButton(onClick = { showSources = false }) { Text("Close") } })
}

@Composable
private fun ReadingTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.headlineSmall)
    } }
}
