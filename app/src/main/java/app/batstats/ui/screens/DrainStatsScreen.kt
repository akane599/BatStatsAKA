package app.batstats.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batstats.battery.drain.*
import app.batstats.battery.measurement.ObservationSummary
import app.batstats.battery.measurement.ObservedBucket
import app.batstats.viewmodel.DrainStatsViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun DrainStatsScreen(onBack: () -> Unit, vm: DrainStatsViewModel = koinViewModel()) {
    val state by vm.drainState.collectAsStateWithLifecycle()
    val running by vm.isTracking.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Observed drain") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(if (running) "Monitoring" else "Monitoring stopped", style = MaterialTheme.typography.headlineSmall)
                Text(MonitoringText.since(state), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { if (running) vm.stopTracking() else vm.startTracking() }) {
                        Text(if (running) "Stop monitoring" else "Start monitoring")
                    }
                    OutlinedButton(onClick = { confirmReset = true }, enabled = state.startedAt != null) { Text("Reset observation") }
                }
            }
            item { ObservationCards(state) }
            item { Text("Screen off means noninteractive, including Always On Display. Locking alone does not imply CPU sleep. CPU suspend comes from elapsed time minus uptime; Android Doze is a separate observed state.", style = MaterialTheme.typography.bodyMedium) }
            item { Text("Charge is the change in Android's reported charge counter. Rates use only covered discharging intervals and need at least one minute. Blank values mean insufficient or unavailable data. System estimates and counter calibration can vary by device.", style = MaterialTheme.typography.bodyMedium) }
        }
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text("Start a new observation?") },
        text = { Text("This closes the current history period and resets the live screen/notification totals. Saved history and Android battery statistics are retained.") },
        confirmButton = { TextButton(onClick = { vm.resetSession(); confirmReset = false }) { Text("Reset observation") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } })
}

@Composable
fun ObservationCards(state: ObservationSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ObservationBucketCard("Screen on · discharging", state.screenOn)
        ObservationBucketCard("Screen off · discharging", state.screenOff)
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Observation total", style = MaterialTheme.typography.titleMedium)
                Text(MonitoringText.bucket(state.discharge), style = MaterialTheme.typography.titleLarge)
                Text(MonitoringText.coverage(state.discharge), style = MaterialTheme.typography.bodyMedium)
                Text("Charging: ${formatDuration(state.chargingMs)} · gained ${formatCharge(state.charging.chargeMah)}")
                Text("Plugged in without charging: ${formatDuration(state.pluggedMs)}")
                Text("CPU suspend: ${if (state.cpuObservedMs > 0) formatDuration(state.cpuSuspendMs) else "—"} in ${formatDuration(state.cpuObservedMs)} observed")
                Text("Android Doze: ${if (state.cpuObservedMs > 0) formatDuration(state.dozeMs) else "—"}")
                if (state.gaps > 0 || state.counterGaps > 0) {
                    Text("${state.gaps} observation gaps · ${state.counterGaps} intervals without valid charge", color = MaterialTheme.colorScheme.error)
                    state.lastIssue?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun ObservationBucketCard(title: String, bucket: ObservedBucket) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(MonitoringText.bucket(bucket), style = MaterialTheme.typography.headlineSmall)
            Text(MonitoringText.coverage(bucket), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
