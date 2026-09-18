package app.batstats.ui.screens

import app.batstats.settings.SettingsImportPolicy
import app.batstats.settings.SettingsText
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.MoreVert
import android.net.Uri
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batstats.R
import app.batstats.settings.AppSettings
import app.batstats.settings.AppSettingsSchema
import app.batstats.settings.Data
import app.batstats.settings.Display
import app.batstats.settings.General
import app.batstats.settings.Notifications
import app.batstats.viewmodel.SettingsViewModel
import io.github.mlmgames.settings.core.SettingField
import io.github.mlmgames.settings.core.SettingMeta
import io.github.mlmgames.settings.core.backup.ImportResult
import io.github.mlmgames.settings.core.resources.StringResourceProvider
import io.github.mlmgames.settings.core.types.Dropdown
import io.github.mlmgames.settings.core.types.Slider
import io.github.mlmgames.settings.core.types.Toggle
import io.github.mlmgames.settings.ui.ProvideStringResources
import io.github.mlmgames.settings.ui.components.SettingsAction
import io.github.mlmgames.settings.ui.components.SettingsItem
import io.github.mlmgames.settings.ui.components.SettingsSection
import io.github.mlmgames.settings.ui.components.SettingsToggle
import io.github.mlmgames.settings.ui.dialogs.DropdownSettingDialog
import io.github.mlmgames.settings.ui.dialogs.SliderSettingDialog
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatterySettingsScreen(
    onBack: () -> Unit,
    onExportData: () -> Unit,
    initialCategory: String? = null,
    vm: SettingsViewModel = koinViewModel(),
    stringProvider: StringResourceProvider = koinInject()
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val alertSettingsUnavailable = stringResource(R.string.alert_settings_unavailable)
    val settings by vm.settings.collectAsStateWithLifecycle()
    val settingsError by vm.error.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(settingsError) { settingsError?.let { snackbarHost.showSnackbar(it) } }

    var showActions by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var resettingSettings by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var clearingHistory by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }

    var showDropdown by remember { mutableStateOf(false) }
    var showSlider by remember { mutableStateOf(false) }
    var currentField by remember { mutableStateOf<SettingField<AppSettings, *>?>(null) }

    val schema = AppSettingsSchema
    val grouped = remember { schema.groupedByCategory() }

    // Launcher for exporting settings (backup)
    val createSettingsBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val message = vm.exportToFile(uri)
                snackbarHost.showSnackbar(message)
            }
        }
    }

    val categoryTitles = mapOf("General" to stringResource(R.string.settings_general),
        "Notifications" to stringResource(R.string.settings_notifications), "Display" to stringResource(R.string.settings_display),
        "Data & Export" to stringResource(R.string.settings_data))
    val categoryOrder = listOf(
        General::class to "General",
        Notifications::class to "Notifications",
        Display::class to "Display",
        Data::class to "Data & Export"
    )

    val listState = rememberLazyListState()

    LaunchedEffect(initialCategory) {
        if (initialCategory != null) {
            val idx = categoryOrder.indexOfFirst { it.second == initialCategory }
            if (idx >= 0) {
                val target = idx * 2
                // delay to allow LazyColumn to be composed
                kotlinx.coroutines.delay(100)
                listState.animateScrollToItem(target)
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showActions = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.settings_more)) }
                    DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.export_settings)) }, onClick = {
                            showActions = false; createSettingsBackup.launch("BatStats_Settings_Backup.json")
                        }, leadingIcon = { Icon(Icons.Outlined.Backup, null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.import_settings_desc)) }, onClick = {
                            showActions = false; showImportDialog = true
                        }, leadingIcon = { Icon(Icons.Outlined.Restore, null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.reset_settings_desc)) }, onClick = {
                            showActions = false; showResetDialog = true
                        }, leadingIcon = { Icon(Icons.Outlined.RestartAlt, null) })
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        ProvideStringResources(stringProvider) {
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categoryOrder.forEach { (categoryClass, categoryTitle) ->
                    val fields = grouped[categoryClass].orEmpty()
                    if (fields.isEmpty()) return@forEach

                    item(key = "header_$categoryTitle") {
                        Text(
                            text = categoryTitles.getValue(categoryTitle),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    item(key = "section_$categoryTitle") {
                        SettingsSection(title = "") {
                            fields.forEach { field ->
                                val meta = field.meta?.let { SettingsText.resolve(context, field.name, it) } ?: return@forEach
                                val enabled = schema.isEnabled(settings, field)

                                RenderSettingField(
                                    field = field,
                                    meta = meta,
                                    settings = settings,
                                    enabled = enabled,
                                    onToggle = { value ->
                                        vm.updateSetting(field.name, value)
                                    },
                                    onOpenDropdown = {
                                        currentField = field
                                        showDropdown = true
                                    },
                                    onOpenSlider = {
                                        currentField = field
                                        showSlider = true
                                    }
                                )
                            }

                            if (categoryClass == General::class) {
                                settingsError?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                                Text(stringResource(R.string.monitoring_settings_help), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                            if (categoryClass == Notifications::class) {
                                SettingsAction(
                                    title = stringResource(R.string.alert_settings_open),
                                    description = stringResource(R.string.alert_settings_description),
                                    onClick = {
                                        try {
                                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                                        } catch (_: android.content.ActivityNotFoundException) {
                                            scope.launch { snackbarHost.showSnackbar(alertSettingsUnavailable) }
                                        }
                                    }
                                )
                            }
                            if (categoryClass == Data::class) {
                                SettingsAction(
                                    title = "Export Battery Data",
                                    description = "Export battery history to file",
//                                    buttonText = "Open",
                                    onClick = onExportData
                                )

                                SettingsAction(
                                    title = "Clear All Data",
                                    description = "Delete all stored battery data",
//                                    buttonText = "Clear",
                                    onClick = { showClearDataDialog = true }
                                )
                            }
                        }
                    }
                }

            }
        }
    }

    // Dropdown Dialog
    val cf = currentField
    if (showDropdown && cf?.meta != null) {
        val meta = SettingsText.resolve(context, cf.name, cf.meta!!)
        @Suppress("UNCHECKED_CAST")
        val anyField = cf as SettingField<AppSettings, Any?>
        val index = when (val value = anyField.get(settings)) {
            is Int -> value
            is Enum<*> -> value.ordinal
            else -> 0
        }

        if (meta.options.isNotEmpty()) {
            DropdownSettingDialog(
                title = meta.title,
                options = meta.options,
                selectedIndex = index,
                onDismiss = { showDropdown = false },
                onOptionSelected = { idx ->
                    vm.updateSetting(cf.name, idx)
                    showDropdown = false
                }
            )
        } else {
            showDropdown = false
        }
    }

    // Slider Dialog
    if (showSlider && cf?.meta != null) {
        val meta = SettingsText.resolve(context, cf.name, cf.meta!!)
        @Suppress("UNCHECKED_CAST")
        val anyField = cf as SettingField<AppSettings, Any?>
        val value = anyField.get(settings)

        val currentVal = when (value) {
            is Float -> value
            is Int -> value.toFloat()
            is Long -> value.toFloat()
            is Double -> value.toFloat()
            else -> 0f
        }

        SliderSettingDialog(
            title = meta.title,
            currentValue = currentVal.takeIf(Float::isFinite)?.coerceIn(meta.min, meta.max) ?: meta.min,
            min = meta.min,
            max = meta.max,
            step = meta.step,
            onDismiss = { showSlider = false },
            onValueSelected = { v ->
                when (value) {
                    is Float -> vm.updateSetting(cf.name, v)
                    is Int -> vm.updateSetting(cf.name, v.toInt())
                    is Long -> vm.updateSetting(cf.name, v.toLong())
                    is Double -> vm.updateSetting(cf.name, v.toDouble())
                }
                showSlider = false
            }
        )
    }

    // Reset Dialog
    if (showResetDialog) {
        val uiSettingsResetMsg = stringResource(R.string.ui_settings_reset)
        val allSettingsResetMsg = stringResource(R.string.all_settings_reset)
        AlertDialog(
            onDismissRequest = { if (!resettingSettings) showResetDialog = false },
            title = { Text(stringResource(R.string.reset_settings)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.choose_reset))
                    settingsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.reset_ui_settings), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.reset_all_settings), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(modifier = Modifier.fillMaxWidth(), enabled = !resettingSettings, onClick = {
                        resettingSettings = true
                        scope.launch {
                            try { if (vm.resetUISettings()) {
                                showResetDialog = false
                                snackbarHost.showSnackbar(uiSettingsResetMsg)
                            } } finally { resettingSettings = false }
                        }
                    }) { Text(stringResource(R.string.reset_ui)) }
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !resettingSettings,
                        onClick = {
                            resettingSettings = true
                            scope.launch {
                                try { if (vm.resetAll()) {
                                    showResetDialog = false
                                    snackbarHost.showSnackbar(allSettingsResetMsg)
                                } } finally { resettingSettings = false }
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text(stringResource(R.string.reset_all)) }
                    TextButton(modifier = Modifier.fillMaxWidth(), enabled = !resettingSettings,
                        onClick = { showResetDialog = false }) { Text(stringResource(R.string.cancel)) }
                }
            }
        )
    }

    // Import Dialog
    if (showImportDialog) {
        var jsonInput by remember { mutableStateOf("") }
        var inputTooLong by remember { mutableStateOf(false) }
        var importing by remember { mutableStateOf(false) }
        var importError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!importing) showImportDialog = false },
            title = { Text(stringResource(R.string.import_settings)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.paste_json))
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = jsonInput,
                        onValueChange = {
                            importError = null
                            inputTooLong = it.length > SettingsImportPolicy.MAX_BYTES
                            if (!inputTooLong) jsonInput = it
                        },
                        enabled = !importing,
                        isError = inputTooLong,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 200.dp),
                        placeholder = { Text(stringResource(R.string.paste_json_hint)) },
                        supportingText = { Text(stringResource(R.string.settings_import_limit)) }
                    )
                    importError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        importing = true
                        importError = null
                        scope.launch {
                            try {
                                when (val result = vm.import(jsonInput)) {
                                    is ImportResult.Success -> {
                                        showImportDialog = false
                                        snackbarHost.showSnackbar(resources.getString(R.string.settings_import_result,
                                            result.appliedCount, result.skippedCount, result.errors.size))
                                    }
                                    is ImportResult.Error -> importError = resources.getString(R.string.settings_import_rejected, result.error.name)
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (_: IllegalArgumentException) { importError = resources.getString(R.string.settings_invalid_import) }
                            catch (_: Exception) { importError = resources.getString(R.string.settings_import_failed) }
                            finally { importing = false }
                        }
                    },
                    enabled = jsonInput.isNotBlank() && !inputTooLong && !importing
                ) { Text(stringResource(R.string.import_action)) }
            },
            dismissButton = { TextButton(enabled = !importing, onClick = { showImportDialog = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    // Clear Data Dialog
    if (showClearDataDialog) {
        val dataClearedMsg = stringResource(R.string.data_cleared)
        val clearFailedMsg = stringResource(R.string.history_clear_failed)
        AlertDialog(
            onDismissRequest = { if (!clearingHistory) showClearDataDialog = false },
            icon = { Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.clear_all_data)) },
            text = { Text(stringResource(R.string.history_clear_warning)) },
            confirmButton = {
                TextButton(
                    enabled = !clearingHistory,
                    onClick = {
                        clearingHistory = true
                        scope.launch {
                            try {
                                vm.clearHistory()
                                showClearDataDialog = false
                                snackbarHost.showSnackbar(dataClearedMsg)
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (_: Exception) { snackbarHost.showSnackbar(clearFailedMsg) }
                            finally { clearingHistory = false }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.delete_all)) }
            },
            dismissButton = { TextButton(enabled = !clearingHistory, onClick = { showClearDataDialog = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun RenderSettingField(
    field: SettingField<AppSettings, *>,
    meta: SettingMeta,
    settings: AppSettings,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenDropdown: () -> Unit,
    onOpenSlider: () -> Unit
) {
    when (meta.type) {
        Toggle::class -> {
            @Suppress("UNCHECKED_CAST")
            val boolField = field as? SettingField<AppSettings, Boolean>
            if (boolField != null) {
                SettingsToggle(
                    title = meta.title,
                    description = meta.description.takeIf { it.isNotBlank() },
                    checked = boolField.get(settings),
                    enabled = enabled,
                    onCheckedChange = onToggle
                )
            }
        }
        Dropdown::class -> {
            @Suppress("UNCHECKED_CAST")
            val anyField = field as SettingField<AppSettings, Any?>
            val index = when (val value = anyField.get(settings)) { is Int -> value; is Enum<*> -> value.ordinal; else -> 0 }
            if (meta.options.isNotEmpty()) {
                SettingsItem(
                    title = meta.title,
                    subtitle = meta.options.getOrNull(index) ?: stringResource(R.string.settings_unknown_option),
                    description = meta.description.takeIf { it.isNotBlank() },
                    enabled = enabled,
                    onClick = onOpenDropdown
                )
            }
        }
        Slider::class -> {
            val currentLocale = LocalConfiguration.current.locales[0]
            @Suppress("UNCHECKED_CAST")
            val anyField = field as SettingField<AppSettings, Any?>
            val subtitle = when (val value = anyField.get(settings)) {
                is Float, is Double -> String.format(currentLocale, "%.1f", (value as Number).toDouble())
                is Int, is Long -> value.toString()
                else -> ""
            }
            SettingsItem(
                title = meta.title,
                subtitle = subtitle + when (field.name) {
                    "lowBatteryThreshold", "highBatteryThreshold" -> "%"
                    "temperatureThreshold" -> " °C"
                    "dischargeCurrentThreshold" -> " mA"
                    else -> ""
                },
                description = meta.description.takeIf { it.isNotBlank() },
                enabled = enabled,
                onClick = onOpenSlider
            )
        }
    }
}
