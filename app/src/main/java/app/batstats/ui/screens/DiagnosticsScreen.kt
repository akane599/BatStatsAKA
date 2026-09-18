package app.batstats.ui.screens

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batstats.BuildConfig
import app.batstats.R
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.diagnostics.*
import app.batstats.battery.shizuku.ShizukuBridge
import app.batstats.battery.util.DetailedStatsCollector
import app.batstats.battery.util.ShellRunner
import org.koin.compose.koinInject
import java.time.Instant

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val repository: BatteryRepository = koinInject()
    val store: DiagnosticStore = koinInject()
    val collector: DetailedStatsCollector = koinInject()
    val shell: ShellRunner = koinInject()
    val shizuku: ShizukuBridge = koinInject()
    val reading by repository.realtimeFlow.collectAsStateWithLifecycle()
    val observation by repository.observation.collectAsStateWithLifecycle()
    val monitoring by repository.isMonitoringFlow.collectAsStateWithLifecycle()
    val events by store.events.collectAsStateWithLifecycle()
    val storageFailed by store.storageUnavailable.collectAsStateWithLifecycle()
    val access by shell.access.collectAsStateWithLifecycle()
    val running by shizuku.running.collectAsStateWithLifecycle()
    val authorized by shizuku.granted.collectAsStateWithLifecycle()
    val advanced by collector.snapshot.collectAsStateWithLifecycle()
    val advancedError by collector.error.collectAsStateWithLifecycle()
    val ordinaryError by repository.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val reportTitle = stringResource(R.string.diagnostics_title)
    val shareTitle = stringResource(R.string.diagnostic_share)
    var shareFailed by remember { mutableStateOf(false) }
    val readingText = remember(reading) { DiagnosticReport.reading(reading.sample) }
    val observationText = remember(observation, monitoring) { DiagnosticReport.observation(observation, monitoring) }
    val accessText = stringResource(R.string.diagnostic_access_values, access.name, running.toString(), authorized.toString())
    val advancedText = advanced?.let {
        stringResource(R.string.diagnostic_advanced_values, Instant.ofEpochMilli(it.capturedAt).toString(),
            it.startedAt?.let { time -> Instant.ofEpochMilli(time).toString() } ?: "—", it.startCount?.toString() ?: "—",
            it.batteryRealtimeMs?.toString() ?: "—", it.rejectedRecords)
    } ?: stringResource(R.string.diagnostic_no_advanced)
    val sourceHelp = stringResource(R.string.diagnostic_source_help)
    val periodHelp = stringResource(R.string.diagnostic_period_help)
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.diagnostics_title)) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(R.string.diagnostic_privacy), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { repository.refreshNow() }) { Text(stringResource(R.string.diagnostic_refresh)) }
                    Button(onClick = {
                        val report = buildString {
                            appendLine("BatStats ${BuildConfig.VERSION_NAME} · Android API ${Build.VERSION.SDK_INT}")
                            appendLine("Report generated: ${Instant.now()} (UTC)")
                            appendLine(readingText); appendLine(); appendLine(sourceHelp)
                            appendLine(observationText); appendLine(); appendLine(periodHelp)
                            appendLine(accessText); appendLine(advancedText)
                            appendLine("Current collection failure: battery=${ordinaryError != null}, advanced=${advancedError != null}")
                            appendLine("Diagnostic persistence unavailable: $storageFailed")
                            appendLine(); append(DiagnosticReport.events(events))
                        }
                        try {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report)
                                putExtra(Intent.EXTRA_SUBJECT, reportTitle)
                            }, shareTitle))
                            shareFailed = false
                        } catch (_: android.content.ActivityNotFoundException) { shareFailed = true }
                    }) { Text(stringResource(R.string.diagnostic_share)) }
                }
                if (shareFailed) Text(stringResource(R.string.diagnostic_share_failed), color = MaterialTheme.colorScheme.error)
            }
            item { DiagnosticCard(stringResource(R.string.diagnostic_readings), readingText, sourceHelp) }
            item { DiagnosticCard(stringResource(R.string.diagnostic_observation), observationText, periodHelp) }
            item {
                DiagnosticCard(stringResource(R.string.diagnostic_access), "$accessText\n$advancedText",
                    stringResource(R.string.diagnostic_access_help))
                ordinaryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                advancedError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            item {
                Text(stringResource(R.string.diagnostic_events), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.diagnostic_log_help), style = MaterialTheme.typography.bodySmall)
                if (storageFailed) Text(stringResource(R.string.diagnostic_storage_failed), color = MaterialTheme.colorScheme.error)
                if (events.isEmpty()) Text(stringResource(R.string.diagnostic_empty))
            }
            items(events.asReversed()) { event ->
                DiagnosticCard(stringResource(event.code.labelResource()),
                    "${event.code.name}\n${Instant.ofEpochMilli(event.firstAt)} → ${Instant.ofEpochMilli(event.lastAt)}\n×${event.count}")
            }
        }
    }
}

@Composable
private fun DiagnosticCard(title: String, body: String, help: String? = null) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            SelectionContainer { Text(body, style = MaterialTheme.typography.bodyMedium) }
            help?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun DiagnosticCode.labelResource(): Int = when (this) {
    DiagnosticCode.MONITORING_STARTED -> R.string.diag_started
    DiagnosticCode.MONITORING_STOPPED -> R.string.diag_stopped
    DiagnosticCode.START_FAILED -> R.string.diag_start_failed
    DiagnosticCode.BATTERY_UNAVAILABLE -> R.string.diag_battery_unavailable
    DiagnosticCode.BATTERY_READ_FAILED -> R.string.diag_battery_failed
    DiagnosticCode.STATE_EVENTS_UNAVAILABLE -> R.string.diag_state_unavailable
    DiagnosticCode.HISTORY_WRITE_FAILED -> R.string.diag_history_failed
    DiagnosticCode.OBSERVATION_GAP -> R.string.diag_gap
    DiagnosticCode.CHARGE_UNAVAILABLE -> R.string.diag_charge_unavailable
    DiagnosticCode.ACCESS_NONE -> R.string.diag_access_none
    DiagnosticCode.ACCESS_SHIZUKU -> R.string.diag_access_shizuku
    DiagnosticCode.ACCESS_ROOT -> R.string.diag_access_root
    DiagnosticCode.ACCESS_ADB -> R.string.diag_access_adb
    DiagnosticCode.ADVANCED_READ_FAILED -> R.string.diag_advanced_failed
    DiagnosticCode.ADVANCED_FORMAT_INVALID -> R.string.diag_format_invalid
    DiagnosticCode.ADVANCED_INTERRUPTED -> R.string.diag_interrupted
    DiagnosticCode.ADVANCED_RECOVERED -> R.string.diag_recovered
    DiagnosticCode.SYSTEM_STATS_RESET -> R.string.diag_reset
    DiagnosticCode.ALERT_FAILED -> R.string.diag_alert_failed
    DiagnosticCode.NOTIFICATION_FAILED -> R.string.diag_notification_failed
    DiagnosticCode.LOG_READ_FAILED -> R.string.diag_log_failed
}
