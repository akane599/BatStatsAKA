package app.batstats.ui.screens

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batstats.R
import app.batstats.battery.data.HistoryLimits
import app.batstats.ui.components.InfoSheet
import app.batstats.ui.components.Panel
import app.batstats.ui.components.SegmentedTabs
import app.batstats.ui.components.StatCell
import app.batstats.ui.theme.spacing
import app.batstats.viewmodel.ClearStep
import app.batstats.viewmodel.DataEvent
import app.batstats.viewmodel.DataFailure
import app.batstats.viewmodel.DataOutcome
import app.batstats.viewmodel.DataTask
import app.batstats.viewmodel.DataUiState
import app.batstats.viewmodel.DataViewModel
import app.batstats.viewmodel.HistoryRange
import org.koin.androidx.compose.koinViewModel
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Two columns from this window width, as on Now: history on the start side, settings and clearing on the end. */
private const val TWO_COLUMN_MIN_WIDTH_DP = 840

private const val JSON_MIME = "application/json"
private val JSON_TYPES = arrayOf(JSON_MIME, "application/octet-stream", "text/plain")
private val CSV_TYPES = arrayOf("text/csv", "text/comma-separated-values", "text/*", "application/octet-stream")

/**
 * Settings › Data, wired: the Koin [DataViewModel] and the Storage Access Framework pickers. [DataEvent.Pick]
 * opens the picker for its task; the chosen document goes to [DataViewModel.onFileChosen] (a cancelled picker
 * does nothing). Every other event goes to the ViewModel.
 */
@Composable
fun DataScreen(onBack: () -> Unit, modifier: Modifier = Modifier, vm: DataViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    fun chosen(task: DataTask): (android.net.Uri?) -> Unit = { uri -> uri?.let { vm.onFileChosen(task, it.toString()) } }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(JSON_MIME), chosen(DataTask.EXPORT_JSON))
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), chosen(DataTask.EXPORT_CSV))
    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), chosen(DataTask.IMPORT_JSON))
    val importCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), chosen(DataTask.IMPORT_CSV))
    val saveSettings = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(JSON_MIME), chosen(DataTask.SAVE_SETTINGS))
    val restoreSettings = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), chosen(DataTask.RESTORE_SETTINGS))
    DataContent(
        state = state,
        onEvent = { event ->
            if (event is DataEvent.Pick) {
                try {
                    when (event.task) {
                        DataTask.EXPORT_JSON -> exportJson.launch(documentName("history", "json"))
                        DataTask.EXPORT_CSV -> exportCsv.launch(null)
                        DataTask.IMPORT_JSON -> importJson.launch(JSON_TYPES)
                        DataTask.IMPORT_CSV -> importCsv.launch(CSV_TYPES)
                        DataTask.SAVE_SETTINGS -> saveSettings.launch(documentName("settings", "json"))
                        DataTask.RESTORE_SETTINGS -> restoreSettings.launch(JSON_TYPES)
                        DataTask.CLEAR -> Unit
                    }
                } catch (_: ActivityNotFoundException) {
                    vm.onPickerUnavailable(event.task)
                }
            } else {
                vm.onEvent(event)
            }
        },
        onBack = onBack,
        modifier = modifier,
    )
}

private fun documentName(kind: String, extension: String) =
    "BatStats-$kind-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())}.$extension"

/**
 * Settings › Data, stateless: what's stored, history export and import, the settings backup, and "Clear all data"
 * behind a confirmation (the dialog's step is [DataUiState.clear]). Each task's progress and result show in the
 * panel that started it; explanations live in the panels' ⓘ sheets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataContent(
    state: DataUiState,
    onEvent: (DataEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val twoColumns = LocalWindowInfo.current.containerSize.width / LocalDensity.current.density >= TWO_COLUMN_MIN_WIDTH_DP
    val column = Arrangement.spacedBy(spacing.sm)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.data_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.data_back))
                    }
                },
            )
        },
    ) { padding ->
        val history: @Composable () -> Unit = {
            StoredPanel(state, Modifier.fillMaxWidth())
            ExportPanel(state, onEvent, Modifier.fillMaxWidth())
            ImportPanel(state, onEvent, Modifier.fillMaxWidth())
        }
        val other: @Composable () -> Unit = {
            SettingsBackupPanel(state, onEvent, Modifier.fillMaxWidth())
            ClearPanel(state, onEvent, Modifier.fillMaxWidth())
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.md)
                .padding(bottom = spacing.md),
            verticalArrangement = column,
        ) {
            if (twoColumns) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Column(Modifier.weight(1f), verticalArrangement = column) { history() }
                    Column(Modifier.weight(1f), verticalArrangement = column) { other() }
                }
            } else {
                history()
                other()
            }
        }
    }
    if (state.clear != ClearStep.HIDDEN) ClearDialog(state.clear, onEvent)
}

/** Readings and sessions on the phone, against the storage limits. */
@Composable
private fun StoredPanel(state: DataUiState, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val numbers = NumberFormat.getIntegerInstance(locale)
    val noValue = stringResource(R.string.component_no_value)
    Panel(
        modifier,
        title = stringResource(R.string.data_stored_title),
        trailing = { InfoSheet(stringResource(R.string.data_stored_info_title), stringResource(R.string.data_stored_info_body)) },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
            StatCell(
                stringResource(R.string.data_stored_readings),
                state.stored?.let { numbers.format(it.samples) } ?: noValue,
                Modifier.weight(1f),
                supporting = stringResource(R.string.data_stored_limit, numbers.format(HistoryLimits.MAX_SAMPLES)),
            )
            StatCell(
                stringResource(R.string.data_stored_sessions),
                state.stored?.let { numbers.format(it.sessions) } ?: noValue,
                Modifier.weight(1f),
                supporting = stringResource(R.string.data_stored_limit, numbers.format(HistoryLimits.MAX_SESSIONS)),
            )
        }
    }
}

@Composable
private fun ExportPanel(state: DataUiState, onEvent: (DataEvent) -> Unit, modifier: Modifier = Modifier) {
    val period = stringResource(R.string.data_export_period)
    Panel(
        modifier,
        title = stringResource(R.string.data_export_title),
        trailing = { InfoSheet(stringResource(R.string.data_export_info_title), stringResource(R.string.data_export_info_body)) },
    ) {
        Field(period) {
            SegmentedTabs(
                labels = listOf(
                    stringResource(R.string.data_range_all),
                    stringResource(R.string.data_range_week),
                    stringResource(R.string.data_range_month),
                ),
                selectedIndex = state.range.ordinal,
                onSelect = { onEvent(DataEvent.SelectRange(HistoryRange.entries[it])) },
                contentDescription = period,
            )
        }
        Field(stringResource(R.string.data_export_include)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
                IncludeChip(stringResource(R.string.data_include_readings), state.includeSamples, state.idle) {
                    onEvent(DataEvent.ToggleSamples)
                }
                IncludeChip(stringResource(R.string.data_include_sessions), state.includeSessions, state.idle) {
                    onEvent(DataEvent.ToggleSessions)
                }
            }
            if (!state.includeSamples && !state.includeSessions) QuietText(stringResource(R.string.data_export_nothing))
        }
        Actions(
            primary = stringResource(R.string.data_export_json),
            onPrimary = { onEvent(DataEvent.Pick(DataTask.EXPORT_JSON)) },
            secondary = stringResource(R.string.data_export_csv),
            onSecondary = { onEvent(DataEvent.Pick(DataTask.EXPORT_CSV)) },
            enabled = state.canExport,
        )
        TaskStatus(state, DataTask.EXPORT_JSON, DataTask.EXPORT_CSV)
    }
}

@Composable
private fun ImportPanel(state: DataUiState, onEvent: (DataEvent) -> Unit, modifier: Modifier = Modifier) {
    Panel(
        modifier,
        title = stringResource(R.string.data_import_title),
        trailing = { InfoSheet(stringResource(R.string.data_import_info_title), stringResource(R.string.data_import_info_body)) },
    ) {
        Actions(
            primary = stringResource(R.string.data_import_json),
            onPrimary = { onEvent(DataEvent.Pick(DataTask.IMPORT_JSON)) },
            secondary = stringResource(R.string.data_import_csv),
            onSecondary = { onEvent(DataEvent.Pick(DataTask.IMPORT_CSV)) },
            enabled = state.idle,
        )
        TaskStatus(state, DataTask.IMPORT_JSON, DataTask.IMPORT_CSV)
    }
}

@Composable
private fun SettingsBackupPanel(state: DataUiState, onEvent: (DataEvent) -> Unit, modifier: Modifier = Modifier) {
    Panel(
        modifier,
        title = stringResource(R.string.data_settings_title),
        trailing = { InfoSheet(stringResource(R.string.data_settings_info_title), stringResource(R.string.data_settings_info_body)) },
    ) {
        Actions(
            primary = stringResource(R.string.data_settings_save),
            onPrimary = { onEvent(DataEvent.Pick(DataTask.SAVE_SETTINGS)) },
            secondary = stringResource(R.string.data_settings_restore),
            onSecondary = { onEvent(DataEvent.Pick(DataTask.RESTORE_SETTINGS)) },
            enabled = state.idle,
        )
        TaskStatus(state, DataTask.SAVE_SETTINGS, DataTask.RESTORE_SETTINGS)
    }
}

/** The destructive row: one tonal tap target that opens the confirmation; the result shows under it. */
@Composable
private fun ClearPanel(state: DataUiState, onEvent: (DataEvent) -> Unit, modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.spacing
    val enabled = state.idle
    val tint = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = { onEvent(DataEvent.RequestClear) },
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Icon(Icons.Rounded.DeleteForever, contentDescription = null, tint = tint)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                    Text(stringResource(R.string.data_clear_action), style = MaterialTheme.typography.titleMedium, color = tint)
                    QuietText(stringResource(R.string.data_clear_hint))
                }
            }
            val outcome = state.outcome
            if (outcome != null && outcome.task == DataTask.CLEAR) OutcomeLine(outcome)
        }
    }
}

@Composable
private fun ClearDialog(step: ClearStep, onEvent: (DataEvent) -> Unit) {
    val running = step == ClearStep.RUNNING
    AlertDialog(
        onDismissRequest = { onEvent(DataEvent.DismissClear) },
        icon = { Icon(Icons.Rounded.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.data_clear_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                Text(stringResource(R.string.data_clear_body))
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (step == ClearStep.FAILED) {
                    Text(
                        stringResource(R.string.data_clear_failed),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onEvent(DataEvent.ConfirmClear) },
                enabled = !running,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.data_clear_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(DataEvent.DismissClear) }, enabled = !running) {
                Text(stringResource(R.string.data_clear_cancel))
            }
        },
        properties = DialogProperties(dismissOnBackPress = !running, dismissOnClickOutside = !running),
    )
}

/** A panel's two actions: equal alternatives (JSON or CSV, save or restore), so both tonal and the same weight. */
@Composable
private fun Actions(
    primary: String,
    onPrimary: () -> Unit,
    secondary: String,
    onSecondary: () -> Unit,
    enabled: Boolean,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(onClick = onPrimary, enabled = enabled) { Text(primary) }
        FilledTonalButton(onClick = onSecondary, enabled = enabled) { Text(secondary) }
    }
}

/** Tonal, borderless filter chip; a check marks the selected state. */
@Composable
private fun IncludeChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        enabled = enabled,
        leadingIcon = if (selected) {
            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = null,
    )
}

/** Progress while one of [tasks] runs, else the outcome of the last one of them. */
@Composable
private fun TaskStatus(state: DataUiState, vararg tasks: DataTask) {
    val outcome = state.outcome
    when {
        state.running in tasks -> Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            QuietText(stringResource(R.string.data_working))
        }
        outcome != null && outcome.task in tasks -> OutcomeLine(outcome)
    }
}

/** One sentence with a success or problem glyph; announced when it appears. */
@Composable
private fun OutcomeLine(outcome: DataOutcome, modifier: Modifier = Modifier) {
    val failed = outcome is DataOutcome.Failed
    Row(
        modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
    ) {
        Icon(
            if (failed) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Text(
            outcomeText(outcome),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun outcomeText(outcome: DataOutcome): String {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val numbers = NumberFormat.getIntegerInstance(locale)
    return when (outcome) {
        is DataOutcome.Exported -> stringResource(
            if (outcome.task == DataTask.EXPORT_CSV) R.string.data_exported_csv else R.string.data_exported_json,
        )
        is DataOutcome.HistoryImported -> with(outcome.result) {
            stringResource(
                R.string.data_imported,
                numbers.format(samplesAdded),
                numbers.format(sessionsAdded),
                numbers.format(sessionsUpdated),
                numbers.format(unchanged),
            )
        }
        DataOutcome.SettingsSaved -> stringResource(R.string.data_settings_saved)
        is DataOutcome.SettingsRestored -> stringResource(
            R.string.data_settings_restored,
            numbers.format(outcome.applied),
            numbers.format(outcome.skipped),
            numbers.format(outcome.failed),
        )
        DataOutcome.Cleared -> stringResource(R.string.data_clear_done)
        is DataOutcome.Failed -> failureText(outcome)
    }
}

@Composable
private fun failureText(outcome: DataOutcome.Failed): String = when (outcome.reason) {
    DataFailure.REJECTED -> {
        val detail = outcome.detail ?: stringResource(R.string.data_failed_unexpected)
        val exporting = outcome.task == DataTask.EXPORT_JSON || outcome.task == DataTask.EXPORT_CSV
        stringResource(if (exporting) R.string.data_failed_export else R.string.data_failed_import, detail)
    }
    DataFailure.NOT_AN_EXPORT -> stringResource(R.string.data_failed_not_export)
    DataFailure.UNREADABLE -> stringResource(R.string.data_failed_read)
    DataFailure.UNWRITABLE -> stringResource(R.string.data_failed_write)
    DataFailure.SETTINGS_INVALID -> stringResource(R.string.data_failed_settings_invalid)
    DataFailure.SETTINGS_OTHER_APP -> stringResource(R.string.data_failed_settings_other_app)
    DataFailure.SETTINGS_TOO_NEW -> stringResource(R.string.data_failed_settings_too_new)
    DataFailure.SETTINGS_DAMAGED -> stringResource(R.string.data_failed_settings_damaged)
    DataFailure.NO_PICKER -> stringResource(R.string.data_failed_no_picker)
    DataFailure.UNEXPECTED -> stringResource(R.string.data_failed_unexpected)
}

/** A labelled control: the label sits close above it, so label and control read as one group. */
@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun QuietText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
