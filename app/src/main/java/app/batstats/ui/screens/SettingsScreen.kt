package app.batstats.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batstats.R
import app.batstats.battery.measurement.CalibrationState
import app.batstats.battery.measurement.CurrentSign
import app.batstats.battery.measurement.CurrentUnit
import app.batstats.battery.util.Notifier
import app.batstats.settings.AppSettings
import app.batstats.settings.CurrentSignOverride
import app.batstats.settings.CurrentUnitOverride
import app.batstats.settings.DesignCapacity
import app.batstats.settings.useFahrenheit
import app.batstats.ui.components.InfoSheet
import app.batstats.ui.components.Panel
import app.batstats.ui.components.QuietText
import app.batstats.ui.components.SegmentedTabs
import app.batstats.ui.components.StatCell
import app.batstats.ui.format.currentLocale
import app.batstats.ui.theme.numericBody
import app.batstats.ui.theme.numericHeadline
import app.batstats.ui.theme.spacing
import app.batstats.viewmodel.SettingsChoice
import app.batstats.viewmodel.SettingsError
import app.batstats.viewmodel.SettingsEvent
import app.batstats.viewmodel.SettingsSwitch
import app.batstats.viewmodel.SettingsThreshold
import app.batstats.viewmodel.SettingsUiState
import app.batstats.viewmodel.SettingsViewModel
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/** Two columns from this window width (the Material "expanded" breakpoint), as on Now. */
private const val TWO_COLUMN_MIN_WIDTH_DP = 840

/** Longest design capacity the field accepts (30000). */
private const val DESIGN_CAPACITY_MAX_DIGITS = 5

/** M3's content alpha for a disabled control. */
private const val DISABLED_ALPHA = 0.38f

/**
 * Settings, wired: the Koin [SettingsViewModel], the links to Data and Status, and Android's settings for the alert
 * channel (created first: Android makes a channel only when it is first used). Everything else goes to the ViewModel.
 */
@Composable
fun SettingsScreen(
    onOpenData: () -> Unit,
    onOpenStatus: () -> Unit,
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val unavailable = stringResource(R.string.alert_settings_unavailable)
    SettingsContent(
        state = state,
        onEvent = { event ->
            when (event) {
                SettingsEvent.OpenData -> onOpenData()
                SettingsEvent.OpenStatus -> onOpenStatus()
                SettingsEvent.OpenAlertSound -> {
                    if (!openAlertChannelSettings(context)) scope.launch { snackbarHostState.showSnackbar(unavailable) }
                }
                else -> vm.onEvent(event)
            }
        },
        dynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}

private fun openAlertChannelSettings(context: Context): Boolean {
    Notifier.ensureAlertChannel(context)
    val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, Notifier.ALERT_CHANNEL_ID)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/**
 * Settings, stateless: [state] in, [onEvent] out. One scrolling page of tonal panels under the status bar — Monitoring,
 * Alerts, Measurement (the detected calibration and its overrides), Appearance, Data — in two columns from 840 dp.
 * Prose lives in each panel's ⓘ sheet. Pickers and confirmations are local dialogs; a failed write shows inline at
 * the top. [dynamicColorAvailable] (API 31+) shows the Material You row.
 */
@Composable
fun SettingsContent(
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    dynamicColorAvailable: Boolean,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    scrollState: ScrollState = rememberScrollState(),
) {
    val spacing = MaterialTheme.spacing
    val scope = rememberCoroutineScope()
    val twoColumns = LocalWindowInfo.current.containerSize.width / LocalDensity.current.density >= TWO_COLUMN_MIN_WIDTH_DP
    val settings = state.settings

    var choice by rememberSaveable { mutableStateOf<SettingsChoice?>(null) }
    var threshold by rememberSaveable { mutableStateOf<SettingsThreshold?>(null) }
    var editDesignCapacity by rememberSaveable { mutableStateOf(false) }
    var confirmCalibrationReset by rememberSaveable { mutableStateOf(false) }

    val monitoring: @Composable () -> Unit = {
        MonitoringPanel(settings, onEvent, onChoose = { choice = it }, Modifier.fillMaxWidth())
    }
    val alerts: @Composable () -> Unit = {
        AlertsPanel(settings, onEvent, onEditThreshold = { threshold = it }, Modifier.fillMaxWidth())
    }
    val measurement: @Composable () -> Unit = {
        MeasurementPanel(
            settings,
            state.calibration,
            onEvent,
            onEditDesignCapacity = { editDesignCapacity = true },
            onResetCalibration = { confirmCalibrationReset = true },
            Modifier.fillMaxWidth(),
        )
    }
    val appearance: @Composable () -> Unit = {
        AppearancePanel(settings, dynamicColorAvailable, onEvent, Modifier.fillMaxWidth())
    }
    val data: @Composable () -> Unit = {
        DataPanel(settings, onEvent, onChoose = { choice = it }, Modifier.fillMaxWidth())
    }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .verticalScroll(scrollState)
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                stringResource(R.string.settings),
                modifier = Modifier.padding(vertical = spacing.xs).semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            state.error?.let { error ->
                ErrorBanner(error, onDismiss = { onEvent(SettingsEvent.DismissError) }, Modifier.fillMaxWidth())
            }
            if (twoColumns) {
                // Data sits under Alerts here so the two columns end close together.
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        monitoring()
                        alerts()
                        data()
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        measurement()
                        appearance()
                    }
                }
            } else {
                monitoring()
                alerts()
                measurement()
                appearance()
                data()
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(spacing.md))
    }

    choice?.let { setting ->
        ChoiceDialog(
            title = stringResource(setting.titleRes()),
            options = setting.optionLabels(),
            selectedIndex = setting.selectedIndex(settings),
            onSelect = { index ->
                choice = null
                onEvent(SettingsEvent.SetChoice(setting, index))
            },
            onDismiss = { choice = null },
        )
    }
    threshold?.let { setting ->
        ThresholdDialog(
            setting,
            initial = setting.value(settings),
            fahrenheit = settings.useFahrenheit,
            onSave = { value ->
                threshold = null
                onEvent(SettingsEvent.SetThreshold(setting, value))
            },
            onDismiss = { threshold = null },
        )
    }
    if (editDesignCapacity) {
        DesignCapacityDialog(
            current = settings.designCapacityMah,
            onSave = { mAh ->
                editDesignCapacity = false
                onEvent(SettingsEvent.SetDesignCapacity(mAh))
            },
            onDismiss = { editDesignCapacity = false },
        )
    }
    if (confirmCalibrationReset) {
        val done = stringResource(R.string.settings_calibration_reset_done)
        AlertDialog(
            onDismissRequest = { confirmCalibrationReset = false },
            title = { Text(stringResource(R.string.settings_reset_calibration_title)) },
            text = { Text(stringResource(R.string.settings_reset_calibration_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCalibrationReset = false
                    onEvent(SettingsEvent.ResetCalibration)
                    scope.launch { snackbarHostState.showSnackbar(done) }
                }) { Text(stringResource(R.string.settings_reset_calibration_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCalibrationReset = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}

@Composable
private fun MonitoringPanel(
    settings: AppSettings,
    onEvent: (SettingsEvent) -> Unit,
    onChoose: (SettingsChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPanel(
        stringResource(R.string.settings_monitoring_title),
        info = stringResource(R.string.settings_monitoring_info_title) to stringResource(R.string.settings_monitoring_info_body),
        modifier = modifier,
    ) {
        Rows {
            SwitchRow(
                stringResource(R.string.settings_auto_start),
                checked = settings.autoStartOnBoot,
                onCheckedChange = { onEvent(SettingsEvent.SetSwitch(SettingsSwitch.AUTO_START, it)) },
            )
            ChoiceRow(SettingsChoice.STATUS_ICON, settings, onClick = { onChoose(SettingsChoice.STATUS_ICON) })
            LinkRow(
                stringResource(R.string.settings_status_link),
                summary = stringResource(R.string.settings_status_link_summary),
                icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                onClick = { onEvent(SettingsEvent.OpenStatus) },
            )
        }
    }
}

/** The five alerts; the four with a threshold carry it as a value button (dimmed while the alert is off). */
@Composable
private fun AlertsPanel(
    settings: AppSettings,
    onEvent: (SettingsEvent) -> Unit,
    onEditThreshold: (SettingsThreshold) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPanel(
        stringResource(R.string.settings_alerts_title),
        info = stringResource(R.string.settings_alerts_info_title) to stringResource(R.string.settings_alerts_info_body),
        modifier = modifier,
    ) {
        Rows {
            SettingsThreshold.entries.forEach { setting ->
                val title = stringResource(setting.titleRes())
                val on = setting.alert.isOn(settings)
                val value = thresholdText(setting, setting.value(settings), settings.useFahrenheit)
                SwitchRow(
                    title,
                    checked = on,
                    onCheckedChange = { onEvent(SettingsEvent.SetSwitch(setting.alert, it)) },
                ) {
                    ValuePill(
                        value,
                        enabled = on,
                        onClick = { onEditThreshold(setting) },
                        description = stringResource(R.string.settings_threshold_description, title, value),
                    )
                }
            }
            SwitchRow(
                stringResource(R.string.settings_alert_full),
                checked = settings.chargingCompleteAlert,
                onCheckedChange = { onEvent(SettingsEvent.SetSwitch(SettingsSwitch.FULL_CHARGE_ALERT, it)) },
            )
            LinkRow(
                stringResource(R.string.settings_alert_sound),
                summary = stringResource(R.string.settings_alert_sound_summary),
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                onClick = { onEvent(SettingsEvent.OpenAlertSound) },
            )
        }
    }
}

/**
 * The detected calibration (what the charge counter says `CURRENT_NOW` means), how it was found and Reset; then the
 * unit and sign overrides, and the design capacity used for health.
 */
@Composable
private fun MeasurementPanel(
    settings: AppSettings,
    calibration: CalibrationState,
    onEvent: (SettingsEvent) -> Unit,
    onEditDesignCapacity: () -> Unit,
    onResetCalibration: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val noValue = stringResource(R.string.component_no_value)
    val detected = calibration.detected
    SettingsPanel(
        stringResource(R.string.settings_measurement_title),
        info = stringResource(R.string.settings_measurement_info_title) to stringResource(R.string.settings_measurement_info_body),
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                StatCell(
                    stringResource(R.string.settings_detected_unit),
                    detected?.let { stringResource(unitLabel(it.unit)) } ?: noValue,
                    Modifier.weight(1f),
                )
                StatCell(
                    stringResource(R.string.settings_detected_sign),
                    detected?.let { stringResource(signLabel(it.sign)) } ?: noValue,
                    Modifier.weight(1f),
                )
            }
            QuietText(
                when {
                    detected == null -> stringResource(R.string.settings_evidence_none)
                    calibration.agreeingWindows > 0 -> stringResource(R.string.settings_evidence_windows, calibration.agreeingWindows)
                    else -> stringResource(R.string.settings_evidence_sign_only)
                },
            )
            if (settings.currentUnitOverride != CurrentUnitOverride.AUTO || settings.currentSignOverride != CurrentSignOverride.AUTO) {
                QuietText(stringResource(R.string.settings_override_note))
            }
        }
        // Under the evidence it acts on; the xxs inset puts the button's label on the md gutter.
        TextButton(onClick = onResetCalibration, modifier = Modifier.padding(horizontal = spacing.xxs)) {
            Text(stringResource(R.string.settings_reset_calibration))
        }
        Rows {
            SegmentedRow(SettingsChoice.CURRENT_UNIT, settings, onEvent)
            SegmentedRow(SettingsChoice.CURRENT_SIGN, settings, onEvent)
            val capacity = settings.designCapacityMah
            SettingRow(
                stringResource(R.string.settings_design_capacity),
                Modifier.clickable(role = Role.Button, onClick = onEditDesignCapacity),
            ) {
                ValuePill(
                    if (capacity == DesignCapacity.AUTO) {
                        stringResource(R.string.option_auto)
                    } else {
                        stringResource(R.string.settings_value_mah, formatWhole(capacity.toFloat(), currentLocale()))
                    },
                )
            }
        }
    }
}

@Composable
private fun AppearancePanel(
    settings: AppSettings,
    dynamicColorAvailable: Boolean,
    onEvent: (SettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPanel(stringResource(R.string.settings_appearance_title), modifier = modifier) {
        Rows {
            SwitchRow(
                stringResource(R.string.settings_pure_black),
                checked = settings.oledBlack,
                onCheckedChange = { onEvent(SettingsEvent.SetSwitch(SettingsSwitch.OLED_BLACK, it)) },
                summary = stringResource(R.string.settings_pure_black_summary),
            )
            if (dynamicColorAvailable) {
                SwitchRow(
                    stringResource(R.string.settings_material_you),
                    checked = settings.dynamicColors,
                    onCheckedChange = { onEvent(SettingsEvent.SetSwitch(SettingsSwitch.DYNAMIC_COLORS, it)) },
                    summary = stringResource(R.string.settings_material_you_summary),
                )
            }
            SegmentedRow(SettingsChoice.TEMPERATURE_UNIT, settings, onEvent)
        }
    }
}

@Composable
private fun DataPanel(
    settings: AppSettings,
    onEvent: (SettingsEvent) -> Unit,
    onChoose: (SettingsChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPanel(
        stringResource(R.string.settings_data_title),
        info = stringResource(R.string.settings_data_info_title) to stringResource(R.string.settings_data_info_body),
        modifier = modifier,
    ) {
        Rows {
            ChoiceRow(SettingsChoice.RETENTION, settings, onClick = { onChoose(SettingsChoice.RETENTION) })
            LinkRow(
                stringResource(R.string.settings_data_link),
                icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                onClick = { onEvent(SettingsEvent.OpenData) },
            )
        }
    }
}

/** A failed write, in the heat family, until dismissed or the next write succeeds; announced politely. */
@Composable
private fun ErrorBanner(error: SettingsError, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.spacing
    val content = MaterialTheme.colorScheme.onErrorContainer
    val message = when (error) {
        SettingsError.WRITE_FAILED -> R.string.settings_write_failed
        SettingsError.INVALID_DESIGN_CAPACITY -> R.string.settings_design_capacity_invalid
    }
    Panel(
        modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.errorContainer,
        contentPadding = PaddingValues(start = spacing.md, top = spacing.xs, end = spacing.xxs, bottom = spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = content)
            Text(stringResource(message), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = content)
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.settings_dismiss), tint = content)
            }
        }
    }
}

// Building blocks

/** A titled panel whose rows run edge to edge (their ripple spans the panel); [info] = ⓘ sheet title to body. */
@Composable
private fun SettingsPanel(
    title: String,
    modifier: Modifier = Modifier,
    info: Pair<String, String>? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Panel(
        modifier,
        title = title,
        trailing = if (info != null) {
            { InfoSheet(info.first, info.second) }
        } else {
            null
        },
        contentPadding = PaddingValues(top = spacing.md, bottom = spacing.xs),
        content = content,
    )
}

/** Rows stacked without gaps (a [Panel] spaces its children). */
@Composable
private fun Rows(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), content = content)
}

/** One settings row: title (and optional summary) in the gutter, [trailing] at the end; 56 dp minimum. */
@Composable
private fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = spacing.xxl + spacing.xs)
            .padding(horizontal = spacing.md, vertical = spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            summary?.let { QuietText(it) }
        }
        trailing()
    }
}

/** The whole row toggles (one TalkBack switch); [value] (a threshold) sits before the switch. */
@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    value: (@Composable () -> Unit)? = null,
) {
    SettingRow(
        title,
        modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        summary,
    ) {
        value?.invoke()
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A setting with several options: its current option under the title; opens the picker. */
@Composable
private fun ChoiceRow(setting: SettingsChoice, settings: AppSettings, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val options = setting.optionLabels()
    val noValue = stringResource(R.string.component_no_value)
    SettingRow(
        stringResource(setting.titleRes()),
        modifier.clickable(role = Role.Button, onClick = onClick),
        summary = options.getOrElse(setting.selectedIndex(settings)) { noValue },
    )
}

/** Two or three short options, chosen in place. */
@Composable
private fun SegmentedRow(setting: SettingsChoice, settings: AppSettings, onEvent: (SettingsEvent) -> Unit) {
    val spacing = MaterialTheme.spacing
    val title = stringResource(setting.titleRes())
    Column(Modifier.fillMaxWidth().padding(horizontal = spacing.md, vertical = spacing.xxs)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        SegmentedTabs(
            labels = setting.optionLabels(),
            selectedIndex = setting.selectedIndex(settings),
            onSelect = { onEvent(SettingsEvent.SetChoice(setting, it)) },
            contentDescription = title,
        )
    }
}

/** Leaves the screen: a chevron for Data and Status, "open in new" for Android's own settings. */
@Composable
private fun LinkRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
) {
    SettingRow(title, modifier.clickable(role = Role.Button, onClick = onClick), summary) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A number in a small tonal pill (tabular Space Grotesk). With [onClick] it is its own 48 dp button inside a row;
 * without, it only shows the row's value.
 */
@Composable
private fun ValuePill(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    description: String? = null,
) {
    val spacing = MaterialTheme.spacing
    val onSurface = MaterialTheme.colorScheme.onSurface
    val label: @Composable () -> Unit = {
        Text(
            text,
            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xxs),
            style = MaterialTheme.typography.numericBody,
            color = if (enabled) onSurface else onSurface.copy(alpha = DISABLED_ALPHA),
            maxLines = 1,
        )
    }
    val color = MaterialTheme.colorScheme.surfaceContainerHighest
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.semantics { description?.let { contentDescription = it } },
            enabled = enabled,
            shape = MaterialTheme.shapes.small,
            color = color,
            content = label,
        )
    } else {
        Surface(modifier, shape = MaterialTheme.shapes.small, color = color, content = label)
    }
}


// Dialogs

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                options.forEachIndexed { index, option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = MaterialTheme.spacing.xxl)
                            .selectable(selected = index == selectedIndex, role = Role.RadioButton, onClick = { onSelect(index) }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                    ) {
                        RadioButton(selected = index == selectedIndex, onClick = null)
                        Text(option, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

/** The threshold as a large number over a stepped slider (range and step from the schema). */
@Composable
private fun ThresholdDialog(
    setting: SettingsThreshold,
    initial: Float,
    fahrenheit: Boolean,
    onSave: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable(setting) { mutableFloatStateOf(initial.coerceIn(setting.range)) }
    val display = thresholdText(setting, value, fahrenheit)
    val range = setting.range
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(setting.titleRes())) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
                Text(display, style = MaterialTheme.typography.numericHeadline, color = MaterialTheme.colorScheme.onSurface)
                QuietText(stringResource(setting.hintRes()))
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = range,
                    steps = ((range.endInclusive - range.start) / setting.step).roundToInt() - 1,
                    modifier = Modifier.semantics { stateDescription = display },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text(stringResource(R.string.settings_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

/** mAh as digits; empty means automatic. Save stays off until the value is 0, empty or in range. */
@Composable
private fun DesignCapacityDialog(current: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable {
        mutableStateOf(if (DesignCapacity.isValid(current) && current != DesignCapacity.AUTO) current.toString() else "")
    }
    val mAh = if (text.isEmpty()) DesignCapacity.AUTO else text.toIntOrNull()
    val valid = mAh != null && DesignCapacity.isValid(mAh)
    val locale = currentLocale()
    val low = formatWhole(DesignCapacity.RANGE_MAH.first.toFloat(), locale)
    val high = formatWhole(DesignCapacity.RANGE_MAH.last.toFloat(), locale)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_design_capacity)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { input -> text = input.filter(Char::isDigit).take(DESIGN_CAPACITY_MAX_DIGITS) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.numericBody,
                placeholder = { Text(stringResource(R.string.option_auto)) },
                suffix = { Text(stringResource(R.string.settings_unit_mah)) },
                supportingText = {
                    Text(stringResource(if (valid) R.string.settings_design_capacity_hint else R.string.settings_design_capacity_error, low, high))
                },
                isError = !valid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = MaterialTheme.shapes.small,
            )
        },
        confirmButton = {
            TextButton(onClick = { if (mAh != null && valid) onSave(mAh) }, enabled = valid) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

// Labels and formatting

private fun SettingsChoice.titleRes(): Int = when (this) {
    SettingsChoice.STATUS_ICON -> R.string.settings_status_icon
    SettingsChoice.CURRENT_UNIT -> R.string.settings_current_unit
    SettingsChoice.CURRENT_SIGN -> R.string.settings_current_sign
    SettingsChoice.TEMPERATURE_UNIT -> R.string.settings_temperature_unit
    SettingsChoice.RETENTION -> R.string.settings_retention
}

/** Option labels in the schema's option order (enum order for enum settings). */
@Composable
private fun SettingsChoice.optionLabels(): List<String> = when (this) {
    SettingsChoice.STATUS_ICON -> listOf(
        R.string.option_status_level,
        R.string.option_status_current,
        R.string.option_status_power,
        R.string.option_status_temperature,
        R.string.option_status_static,
    )
    SettingsChoice.CURRENT_UNIT -> listOf(R.string.option_auto, R.string.option_microamps, R.string.option_milliamps)
    SettingsChoice.CURRENT_SIGN -> listOf(R.string.option_auto, R.string.option_sign_normal, R.string.option_sign_inverted)
    SettingsChoice.TEMPERATURE_UNIT -> listOf(R.string.option_celsius, R.string.option_fahrenheit)
    SettingsChoice.RETENTION -> listOf(
        R.string.option_1_week,
        R.string.option_1_month,
        R.string.option_3_months,
        R.string.option_6_months,
        R.string.option_1_year,
        R.string.option_forever,
    )
}.map { stringResource(it) }

private fun SettingsThreshold.titleRes(): Int = when (this) {
    SettingsThreshold.LOW_BATTERY -> R.string.settings_alert_low
    SettingsThreshold.HIGH_BATTERY -> R.string.settings_alert_high
    SettingsThreshold.TEMPERATURE -> R.string.settings_alert_temperature
    SettingsThreshold.DISCHARGE_CURRENT -> R.string.settings_alert_discharge
}

private fun SettingsThreshold.hintRes(): Int = when (this) {
    SettingsThreshold.LOW_BATTERY -> R.string.settings_threshold_low_hint
    SettingsThreshold.HIGH_BATTERY -> R.string.settings_threshold_high_hint
    SettingsThreshold.TEMPERATURE -> R.string.settings_threshold_temperature_hint
    SettingsThreshold.DISCHARGE_CURRENT -> R.string.settings_threshold_discharge_hint
}

private fun unitLabel(unit: CurrentUnit): Int = when (unit) {
    CurrentUnit.MICROAMPS -> R.string.option_microamps
    CurrentUnit.MILLIAMPS -> R.string.option_milliamps
}

private fun signLabel(sign: CurrentSign): Int = when (sign) {
    CurrentSign.NORMAL -> R.string.option_sign_normal
    CurrentSign.INVERTED -> R.string.option_sign_inverted
}

/** The threshold with its unit; the temperature (stored in °C) in the display unit. */
@Composable
private fun thresholdText(setting: SettingsThreshold, value: Float, fahrenheit: Boolean): String {
    val locale = currentLocale()
    return when (setting) {
        SettingsThreshold.LOW_BATTERY, SettingsThreshold.HIGH_BATTERY ->
            stringResource(R.string.settings_value_percent, formatWhole(value, locale))
        SettingsThreshold.TEMPERATURE ->
            if (fahrenheit) {
                stringResource(R.string.settings_value_fahrenheit, formatWhole(value * 9 / 5 + 32, locale))
            } else {
                stringResource(R.string.settings_value_celsius, formatWhole(value, locale))
            }
        SettingsThreshold.DISCHARGE_CURRENT -> stringResource(R.string.settings_value_ma, formatWhole(value, locale))
    }
}

private fun formatWhole(value: Float, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(value.roundToLong())
