package app.batstats.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.batstats.R
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
    val context = LocalContext.current
    val text = remember(context) { MonitoringText(context) }
    val state by vm.drainState.collectAsStateWithLifecycle()
    val running by vm.isTracking.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.monitor_drain_title)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(stringResource(if (running) R.string.monitor_active else R.string.monitor_stopped), style = MaterialTheme.typography.headlineSmall)
                Text(text.since(state), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { if (running) vm.stopTracking() else vm.startTracking() }) {
                        Text(stringResource(if (running) R.string.monitor_stop else R.string.start_monitoring))
                    }
                    OutlinedButton(onClick = { confirmReset = true }, enabled = state.startedAt != null) { Text(stringResource(R.string.monitor_reset)) }
                }
            }
            item { ObservationCards(state) }
            item { Text(stringResource(R.string.monitor_screen_help), style = MaterialTheme.typography.bodyMedium) }
            item { Text(stringResource(R.string.monitor_counter_help), style = MaterialTheme.typography.bodyMedium) }
        }
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text(stringResource(R.string.monitor_reset_title)) },
        text = { Text(stringResource(R.string.monitor_reset_help)) },
        confirmButton = { TextButton(onClick = { vm.resetSession(); confirmReset = false }) { Text(stringResource(R.string.monitor_reset)) } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
fun ObservationCards(state: ObservationSummary) {
    val context = LocalContext.current
    val text = remember(context) { MonitoringText(context) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ObservationBucketCard(stringResource(R.string.monitor_on_title), state.screenOn)
        ObservationBucketCard(stringResource(R.string.monitor_off_title), state.screenOff)
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.monitor_total), style = MaterialTheme.typography.titleMedium)
                Text(text.bucket(state.discharge), style = MaterialTheme.typography.titleLarge)
                Text(text.coverage(state.discharge), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.monitor_charging, formatDuration(state.chargingMs), formatCharge(state.charging.chargeMah)))
                Text(stringResource(R.string.monitor_plugged, formatDuration(state.pluggedMs)))
                Text(stringResource(R.string.monitor_cpu, text.cpuSuspend(state)))
                Text(stringResource(R.string.monitor_doze, text.doze(state)))
                if (state.gaps > 0 || state.counterGaps > 0) {
                    Text(stringResource(R.string.monitor_gaps, state.gaps, state.counterGaps), color = MaterialTheme.colorScheme.error)
                    state.lastIssue?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun ObservationBucketCard(title: String, bucket: ObservedBucket) {
    val context = LocalContext.current
    val text = remember(context) { MonitoringText(context) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text.bucket(bucket), style = MaterialTheme.typography.headlineSmall)
            Text(text.coverage(bucket), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
