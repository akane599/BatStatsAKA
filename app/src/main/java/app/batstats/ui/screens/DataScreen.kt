package app.batstats.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batstats.R
import app.batstats.viewmodel.DataViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun DataScreen(onBack: () -> Unit, vm: DataViewModel = koinViewModel()) {
    val isBusy by vm.isBusy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    var days by rememberSaveable { mutableIntStateOf(0) }
    var includeSamples by rememberSaveable { mutableStateOf(true) }
    var includeSessions by rememberSaveable { mutableStateOf(true) }
    fun fromNow() = if (days == 0) 0L else System.currentTimeMillis() - days * 86_400_000L
    LaunchedEffect(message) {
        message?.let { snackbarHost.showSnackbar(it); vm.clearMessage() }
    }
    val createJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportJson(uri, fromNow(), 0, includeSamples, includeSessions)
    }
    val folderCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.exportCsv(uri, fromNow(), 0, includeSamples, includeSessions)
    }
    val openJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importJson(uri)
    }
    val openCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importCsv(uri)
    }
    DataContent(
        isBusy = isBusy,
        days = days,
        includeSamples = includeSamples,
        includeSessions = includeSessions,
        snackbarHostState = snackbarHost,
        onBack = onBack,
        onDaysChange = { days = it },
        onToggleSamples = { includeSamples = !includeSamples },
        onToggleSessions = { includeSessions = !includeSessions },
        onExportJson = { createJson.launch("BatStats-${System.currentTimeMillis()}.json") },
        onExportCsv = { folderCsv.launch(null) },
        onImportJson = { openJson.launch(arrayOf("application/json", "application/octet-stream")) },
        onImportCsv = { openCsv.launch(arrayOf("text/*", "application/octet-stream")) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataContent(
    isBusy: Boolean,
    days: Int,
    includeSamples: Boolean,
    includeSessions: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onDaysChange: (Int) -> Unit,
    onToggleSamples: () -> Unit,
    onToggleSessions: () -> Unit,
    onExportJson: () -> Unit,
    onExportCsv: () -> Unit,
    onImportJson: () -> Unit,
    onImportCsv: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canExport = !isBusy && (includeSamples || includeSessions)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.data_export_import)) }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.date_range), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to R.string.all, 7 to R.string.last_7_days, 30 to R.string.last_30_days).forEach { (value, label) ->
                            FilterChip(selected = days == value, enabled = !isBusy, onClick = { onDaysChange(value) }, label = { Text(stringResource(label)) })
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = includeSamples, enabled = !isBusy, onClick = onToggleSamples, label = { Text(stringResource(R.string.samples)) })
                        FilterChip(selected = includeSessions, enabled = !isBusy, onClick = onToggleSessions, label = { Text(stringResource(R.string.sessions)) })
                    }
                    Text(stringResource(R.string.history_range_help), style = MaterialTheme.typography.bodyMedium)
                }
            }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.export), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.history_export_help))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onExportJson, enabled = canExport) {
                            Text(stringResource(R.string.export_json))
                        }
                        OutlinedButton(onClick = onExportCsv, enabled = canExport) { Text(stringResource(R.string.export_csv_folder)) }
                    }
                }
            }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.import_string), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.history_import_help))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onImportJson, enabled = !isBusy) {
                            Text(stringResource(R.string.import_json))
                        }
                        OutlinedButton(onClick = onImportCsv, enabled = !isBusy) { Text(stringResource(R.string.import_csv)) }
                    }
                }
            }
            if (isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.history_limits), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}
