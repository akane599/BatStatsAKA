package app.batstats.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batstats.battery.drain.formatDuration
import app.batstats.ui.components.ChartPoint
import app.batstats.ui.components.TelemetryChart
import app.batstats.viewmodel.SessionDetailsViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun SessionDetailsScreen(sessionId: String, onBack: () -> Unit, vm: SessionDetailsViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Observation details") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("Back") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${ui.type} · ${ui.levelRange}", style = MaterialTheme.typography.titleLarge)
                    if (ui.start > 0) Text("Start: ${DateFormat.getDateTimeInstance().format(Date(ui.start))}")
                    Text(ui.end?.let { "End: ${DateFormat.getDateTimeInstance().format(Date(it))}" } ?: "Current observation")
                    Text("Source: ${ui.source}")
                    if (ui.source == "legacy") Text("Legacy record: sampling continuity, screen accounting and calculation quality were not recorded.")
                    else {
                        Text("Observed: ${formatDuration(ui.observedMs)}")
                        Text("Counter coverage: ${formatDuration(ui.counterCoveredMs)}")
                        Text("Average counter rate: ${ui.avgCurrent?.let { "${it / 1000} mA" } ?: "Unavailable"}")
                    }
                    ui.closeReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                } }
            }
            item { TelemetryChart("Net current", "mA", ui.points.map { ChartPoint(it.timestamp, it.currentMa?.toDouble(), it.observationId, it.gap) }) }
            item { TelemetryChart("Voltage", "mV", ui.points.map { ChartPoint(it.timestamp, it.voltageMv?.toDouble(), it.observationId, it.gap) }) }
            item { TelemetryChart("Temperature", "°C", ui.points.map { ChartPoint(it.timestamp, it.tempC, it.observationId, it.gap) }) }
        }
    }
}
