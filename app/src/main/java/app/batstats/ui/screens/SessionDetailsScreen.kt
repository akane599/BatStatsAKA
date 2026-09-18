package app.batstats.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batstats.R
import app.batstats.battery.data.SessionEvidence
import app.batstats.battery.drain.formatDrainRate
import app.batstats.battery.drain.formatCharge
import app.batstats.battery.drain.formatDuration
import app.batstats.ui.components.ChartPoint
import app.batstats.ui.components.TelemetryChart
import app.batstats.ui.components.sessionTypeLabel
import app.batstats.viewmodel.SessionDetailsViewModel
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

@Composable
fun SessionDetailsScreen(onBack: () -> Unit, vm: SessionDetailsViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val recording by vm.recordingObservation.collectAsStateWithLifecycle()
    val session = ui.session
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.session_details)) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
    }, actions = { IconButton(onClick = vm::refresh, enabled = !ui.loading) { Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh)) } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when {
                ui.loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.history_loading)) }
                ui.failed -> item {
                    Text(stringResource(R.string.history_load_failed), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = vm::refresh) { Text(stringResource(R.string.retry)) }
                }
                session == null -> item { Text(stringResource(R.string.session_missing)) }
                else -> {
                    item {
                        val coverageKnown = SessionEvidence.hasCoverage(session)
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(sessionTypeLabel(session.type), style = MaterialTheme.typography.titleLarge)
                            Text("${session.startLevel?.let { "$it%" } ?: "—"} → ${session.endLevel?.let { "$it%" } ?: "—"}")
                            Text(stringResource(R.string.session_start, format.format(Date(session.startTime))))
                            Text(stringResource(R.string.session_last_evidence, format.format(Date(SessionEvidence.lastEvidence(session)))))
                            if (SessionEvidence.isRecording(session, recording)) Text(stringResource(R.string.session_recording))
                            else session.endTime?.let { Text(stringResource(R.string.session_end, format.format(Date(it)))) }
                                ?: Text(stringResource(R.string.session_interrupted))
                            Text(stringResource(if (session.source.startsWith("import:")) R.string.session_imported_source else R.string.session_source, session.source))
                            if (!coverageKnown) Text(stringResource(R.string.session_legacy_help))
                            else {
                                Text(stringResource(R.string.session_observed, formatDuration(session.observedMs)))
                                Text(stringResource(R.string.session_counter_coverage, formatDuration(session.counterCoveredMs), formatDuration(session.observedMs)))
                                Text(stringResource(R.string.session_charge, formatCharge(session.deltaUah?.takeIf { session.counterCoveredMs > 0 }?.div(1000.0))))
                                Text(stringResource(R.string.session_average, formatDrainRate(session.avgCurrentUa?.takeIf { session.counterCoveredMs >= 60_000 }?.let { abs(it / 1000.0) })))
                                Text(stringResource(R.string.session_screen_times, formatDuration(session.screenOnMs), formatDuration(session.screenOffMs)))
                            }
                            // Preserve old capacity fields for inspection without endorsing their undocumented algorithm.
                            session.estCapacityMah?.let { Text(stringResource(R.string.session_legacy_capacity, it), style = MaterialTheme.typography.bodySmall) }
                            session.closeReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        } }
                    }
                    if (ui.points.isEmpty()) item { Text(stringResource(R.string.session_no_linked_samples)) }
                    else {
                        item { Text(stringResource(R.string.session_chart_sources, ui.points.map { it.source }.distinct().joinToString()), style = MaterialTheme.typography.bodySmall) }
                        item { TelemetryChart(stringResource(R.string.session_net_current), "mA", ui.points.map { ChartPoint(it.timestamp, it.currentNowUa?.div(1000.0), it.observationId, it.discontinuity) }) }
                        item { TelemetryChart(stringResource(R.string.session_voltage), "mV", ui.points.map { ChartPoint(it.timestamp, it.voltageMv?.toDouble(), it.observationId, it.discontinuity) }) }
                        item { TelemetryChart(stringResource(R.string.session_temperature), "°C", ui.points.map { ChartPoint(it.timestamp, it.temperatureDeciC?.div(10.0), it.observationId, it.discontinuity) }) }
                    }
                }
            }
        }
    }
}
