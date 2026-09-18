package app.batstats.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.content.ClipData
import android.content.ClipboardManager
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batstats.R
import app.batstats.battery.util.BatteryStatsParser
import app.batstats.battery.util.RootStatsCollector
import app.batstats.battery.util.KernelStats
import app.batstats.viewmodel.DetailedStatsViewModel
import org.koin.androidx.compose.koinViewModel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailedStatsScreen(onBack: () -> Unit, vm: DetailedStatsViewModel = koinViewModel()) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val idle by vm.deviceIdle.collectAsStateWithLifecycle()
    val power by vm.powerManager.collectAsStateWithLifecycle()
    val refreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val mode by vm.advMode.collectAsStateWithLifecycle()
    val running by vm.shizukuRunning.collectAsStateWithLifecycle()
    val authorized by vm.hasShizuku.collectAsStateWithLifecycle()
    val root by vm.hasRoot.collectAsStateWithLifecycle()
    val kernel by vm.kernelBattery.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAccess by rememberSaveable { mutableStateOf(false) }
    var showReset by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var appOnly by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<BatteryStatsParser.AppPowerStats?>(null) }
    val tabs = listOf(R.string.adv_overview, R.string.adv_apps, R.string.adv_wakelocks,
        R.string.adv_network, R.string.adv_scheduled, R.string.adv_system, R.string.adv_kernel)
    LifecycleResumeEffect(Unit) { vm.recheck(); onPauseOrDispose {} }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.adv_title)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.adv_back)) }
        }, actions = { IconButton(onClick = { vm.refresh(true) }, enabled = !refreshing) {
            Icon(Icons.Default.Refresh, stringResource(R.string.adv_refresh))
        } })
    }) { padding ->
        Column(Modifier.padding(padding)) {
            if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            SecondaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                tabs.forEachIndexed { index, label -> Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(label)) }) }
            }
            key(tab) {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                    item {
                        DetailCard(stringResource(R.string.adv_window)) {
                            Text(stringResource(R.string.adv_access, mode.name))
                            snapshot?.let { s ->
                                Text(stringResource(R.string.adv_period, date(s.startedAt), date(s.capturedAt)))
                                Text(stringResource(R.string.adv_window_note), style = MaterialTheme.typography.bodySmall)
                                if (s.rejectedRecords > 0) Text(stringResource(R.string.adv_rejected, s.rejectedRecords), color = MaterialTheme.colorScheme.error)
                            } ?: Text(stringResource(R.string.adv_no_snapshot))
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            TextButton(onClick = { showAccess = true }) { Text(stringResource(R.string.adv_access_help)) }
                            if (tab == 0) TextButton(onClick = { showReset = true }, enabled = !refreshing && mode != app.batstats.battery.util.ShellRunner.Mode.NONE) {
                                Text(stringResource(R.string.adv_reset_android))
                            }
                            if (running && !authorized) Button(onClick = vm::requestShizukuPermission) { Text(stringResource(R.string.adv_authorize)) }
                        }
                    }
                    when (tab) {
                        0 -> {
                            item { DetailCard(stringResource(R.string.adv_overview)) {
                                DetailRow(R.string.adv_on_battery, duration(snapshot?.batteryRealtimeMs))
                                DetailRow(R.string.adv_screen_on, duration(snapshot?.screenOnTimeMs))
                                DetailRow(R.string.adv_screen_off, duration(snapshot?.screenOffTimeMs))
                                DetailRow(R.string.adv_aod, duration(snapshot?.screenDozeTimeMs))
                                DetailRow(R.string.adv_capacity, snapshot?.estimatedCapacityMah?.let { app.batstats.battery.drain.formatCharge(it) })
                                DetailRow(R.string.adv_on_drop, snapshot?.screenOnDischargePercent?.let { "$it pp" })
                                DetailRow(R.string.adv_off_drop, snapshot?.screenOffDischargePercent?.let { "$it pp" })
                                Text(stringResource(R.string.adv_capacity_note), style = MaterialTheme.typography.bodySmall)
                            } }
                            item { DetailCard(stringResource(R.string.adv_doze)) {
                                DetailRow(R.string.adv_deep_doze, duration(snapshot?.doze?.deepIdleTimeMs))
                                DetailRow(R.string.adv_light_doze, duration(snapshot?.doze?.lightIdleTimeMs))
                                Text(stringResource(R.string.adv_doze_note), style = MaterialTheme.typography.bodySmall)
                            } }
                            item { DetailCard(stringResource(R.string.adv_signal)) {
                                if (snapshot?.signalStrength.isNullOrEmpty() && snapshot?.wifiSignal.isNullOrEmpty()) MissingRows()
                                snapshot?.signalStrength?.forEach { Text(stringResource(R.string.adv_cell_bin, it.level, duration(it.durationMs) ?: "—")) }
                                snapshot?.wifiSignal?.forEach { Text(stringResource(R.string.adv_wifi_bin, it.level, duration(it.durationMs) ?: "—")) }
                            } }
                            item { DetailCard(stringResource(R.string.adv_bluetooth)) {
                                DetailRow(R.string.adv_controller_idle, duration(snapshot?.bluetooth?.idleTimeMs))
                                DetailRow(R.string.adv_receive, duration(snapshot?.bluetooth?.rxTimeMs))
                                DetailRow(R.string.adv_transmit, duration(snapshot?.bluetooth?.txTimeMs))
                                DetailRow(R.string.adv_charge_estimate, charge(snapshot?.bluetooth?.powerMah))
                            } }
                            item { DetailCard(stringResource(R.string.adv_components)) {
                                Text(stringResource(R.string.adv_estimate_note), style = MaterialTheme.typography.bodySmall)
                                if (snapshot?.componentEstimatesMah.isNullOrEmpty()) MissingRows()
                                snapshot?.componentEstimatesMah?.forEach { (name, value) -> DetailValue(name, charge(value)) }
                            } }
                        }
                        1 -> {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(R.string.adv_attribution_note), style = MaterialTheme.typography.bodySmall)
                                    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text(stringResource(R.string.adv_search)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                    TextButton(onClick = { sort = (sort + 1) % 4 }) {
                                        Text(stringResource(R.string.adv_sort, stringResource(listOf(R.string.adv_charge_estimate, R.string.adv_reported_cpu, R.string.adv_foreground, R.string.adv_background)[sort])))
                                    }
                                    FilterChip(selected = appOnly, onClick = { appOnly = !appOnly }, label = { Text(stringResource(R.string.adv_app_uids)) })
                                }
                            }
                            val apps = snapshot?.apps.orEmpty().filter { (!appOnly || BatteryStatsParser.isUserApp(it.uid, it.packages)) &&
                                (query.isBlank() || it.packageName.contains(query, true) || it.packages.any { p -> p.contains(query, true) } || it.uid.toString().contains(query)) }.let { list -> when (sort) {
                                    1 -> list.sortedByDescending { it.cpuTimeMs }; 2 -> list.sortedByDescending { it.foregroundTimeMs }
                                    3 -> list.sortedByDescending { it.backgroundTimeMs }; else -> list.sortedByDescending { it.powerMah }
                                } }
                            if (apps.isEmpty()) item { MissingRows() }
                            items(apps, key = { it.uid }) { app ->
                                OutlinedCard(onClick = { selected = app }, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(app.packageName, style = MaterialTheme.typography.titleMedium)
                                        Text(stringResource(R.string.adv_uid, app.uid, app.uid / BatteryStatsParser.PER_USER_RANGE))
                                        Text(stringResource(R.string.adv_estimated_charge, charge(app.powerMah) ?: "—"), color = MaterialTheme.colorScheme.primary)
                                        Text(stringResource(R.string.adv_inspect), style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                        2 -> {
                            item { Text(stringResource(R.string.adv_activity_note)) }
                            if (snapshot?.wakelocks.isNullOrEmpty()) item { MissingRows() }
                            items(snapshot?.wakelocks.orEmpty()) { w -> DetailCard(w.tag) {
                                Text("${w.packageName} · UID ${w.uid} · ${w.type}")
                                DetailRow(R.string.adv_pooled_duration, duration(w.totalTimeMs))
                                DetailRow(R.string.adv_count, w.count?.toString())
                                DetailRow(R.string.adv_max_duration, duration(w.maxTimeMs))
                                DetailRow(R.string.adv_background_pooled, duration(w.backgroundTimeMs))
                            } }
                            items(snapshot?.kernelWakelocks.orEmpty()) { w -> DetailCard(w.name) {
                                Text(stringResource(R.string.adv_kernel_timer))
                                DetailRow(R.string.adv_duration, duration(w.totalTimeMs)); DetailRow(R.string.adv_count, w.count?.toString())
                            } }
                        }
                        3 -> {
                            item { Text(stringResource(R.string.adv_network_note)) }
                            if (snapshot?.network.isNullOrEmpty()) item { MissingRows() }
                            items(snapshot?.network.orEmpty(), key = { it.uid }) { n -> DetailCard("${n.packageName} · UID ${n.uid}") {
                                DetailRow(R.string.adv_mobile_rx, bytes(n.mobileRxBytes)); DetailRow(R.string.adv_mobile_tx, bytes(n.mobileTxBytes))
                                DetailRow(R.string.adv_wifi_rx, bytes(n.wifiRxBytes)); DetailRow(R.string.adv_wifi_tx, bytes(n.wifiTxBytes))
                                DetailRow(R.string.adv_bt_rx, bytes(n.btRxBytes)); DetailRow(R.string.adv_bt_tx, bytes(n.btTxBytes))
                                DetailRow(R.string.adv_radio_active, duration(n.mobileActiveTimeMs))
                            } }
                        }
                        4 -> {
                            item { Text(stringResource(R.string.adv_activity_note)) }
                            if (snapshot?.alarms.isNullOrEmpty() && snapshot?.jobs.isNullOrEmpty() && snapshot?.syncs.isNullOrEmpty()) item { MissingRows() }
                            items(snapshot?.alarms.orEmpty()) { a -> DetailCard(a.tag) {
                                Text("${a.packageName} · UID ${a.uid}"); DetailRow(R.string.adv_wakeup_alarms, a.wakeups.toString())
                            } }
                            items(snapshot?.jobs.orEmpty()) { j -> DetailCard(j.jobName) {
                                Text("${j.packageName} · UID ${j.uid}"); DetailRow(R.string.adv_jobs, j.count.toString())
                                DetailRow(R.string.adv_pooled_duration, duration(j.totalTimeMs)); DetailRow(R.string.adv_background_unpooled, duration(j.backgroundTimeMs))
                            } }
                            items(snapshot?.syncs.orEmpty()) { s -> DetailCard(s.authority) {
                                Text("${s.packageName} · UID ${s.uid}"); DetailRow(R.string.adv_syncs, s.count.toString())
                                DetailRow(R.string.adv_pooled_duration, duration(s.totalTimeMs)); DetailRow(R.string.adv_background_unpooled, duration(s.backgroundTimeMs))
                            } }
                        }
                        5 -> {
                            item { DetailCard(stringResource(R.string.adv_current_state)) {
                                DetailRow(R.string.adv_deep_doze, idle?.currentState); DetailRow(R.string.adv_light_doze, idle?.lightState)
                                DetailRow(R.string.adv_interactive, truth(power?.isScreenOn)); DetailRow(R.string.adv_battery_saver, truth(power?.lowPowerMode))
                                Text(stringResource(R.string.adv_current_note), style = MaterialTheme.typography.bodySmall)
                                power?.holdingWakeLocks?.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                                power?.suspendBlockers?.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            } }
                            item { DetailCard(stringResource(R.string.adv_exemptions)) {
                                if (idle?.whitelistedApps.isNullOrEmpty()) MissingRows()
                                idle?.whitelistedApps?.forEach { Text(it) }
                                idle?.tempWhitelistedApps?.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            } }
                            item { Text(stringResource(R.string.adv_cpu_note)) }
                            items(snapshot?.cpuFrequency.orEmpty()) { f -> DetailCard("${f.frequency} kHz") {
                                DetailRow(R.string.adv_reported_cpu, duration(f.timeMs))
                                DetailRow(R.string.adv_share_cpu, String.format(Locale.getDefault(), "%.1f%%", f.percentOfTotal * 100))
                            } }
                            items(snapshot?.processStats.orEmpty()) { p -> DetailCard(p.processName) {
                                Text("${p.packageName} · UID ${p.uid}"); DetailRow(R.string.adv_user_cpu, duration(p.userTimeMs)); DetailRow(R.string.adv_system_cpu, duration(p.systemTimeMs))
                                DetailRow(R.string.adv_foreground, duration(p.foregroundTimeMs)); DetailRow(R.string.adv_starts, p.starts.toString())
                            } }
                            items(snapshot?.sensors.orEmpty()) { s -> DetailCard(s.sensorName) {
                                Text("${s.packageName} · UID ${s.uid}"); DetailRow(R.string.adv_pooled_duration, duration(s.totalTimeMs)); DetailRow(R.string.adv_count, s.count.toString())
                            } }
                        }
                        6 -> item { KernelDetails(root, kernel, vm::refreshRootStats) }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
    if (showAccess) AlertDialog(onDismissRequest = { showAccess = false }, title = { Text(stringResource(R.string.adv_access_help)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.adv_access_instructions))
            Text(vm.adbCommands, style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onClick = {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("BatStats ADB", vm.adbCommands))
        }) { Text(stringResource(R.string.adv_copy_commands)) } }, dismissButton = { TextButton(onClick = { showAccess = false }) { Text(stringResource(R.string.adv_close)) } })
    if (showReset) AlertDialog(onDismissRequest = { showReset = false }, title = { Text(stringResource(R.string.adv_reset_android)) },
        text = { Text(stringResource(R.string.adv_reset_warning)) }, confirmButton = { TextButton(onClick = {
            showReset = false
            scope.launch { if (vm.resetStats()) vm.refresh(true) }
        }) { Text(stringResource(R.string.adv_reset_confirm)) } }, dismissButton = { TextButton(onClick = { showReset = false }) { Text(stringResource(R.string.adv_cancel)) } })
    selected?.let { app -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(app.packageName) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.adv_uid, app.uid, app.uid / BatteryStatsParser.PER_USER_RANGE))
            Text(app.packages.joinToString("\n").ifBlank { stringResource(R.string.adv_no_mapping) })
            Text(stringResource(R.string.adv_attribution_note))
            DetailRow(R.string.adv_charge_estimate, charge(app.powerMah))
            DetailRow(R.string.adv_screen_estimate, charge(app.screenPowerMah))
            DetailRow(R.string.adv_proportional_total, charge(app.proportionalSmearMah))
            Text(stringResource(R.string.adv_components_note), style = MaterialTheme.typography.bodySmall)
            DetailRow(R.string.adv_reported_cpu, duration(app.cpuTimeMs)); DetailRow(R.string.adv_aggregate_partial, duration(app.wakeLockTimeMs))
            DetailRow(R.string.adv_foreground, duration(app.foregroundTimeMs)); DetailRow(R.string.adv_fgs, duration(app.foregroundServiceTimeMs))
            DetailRow(R.string.adv_background, duration(app.backgroundTimeMs)); DetailRow(R.string.adv_cached, duration(app.cachedTimeMs))
            DetailRow(R.string.adv_gps, duration(app.gpsTimeMs)); DetailRow(R.string.adv_sensors, duration(app.sensorTimeMs))
            DetailRow(R.string.adv_camera, duration(app.cameraTimeMs)); DetailRow(R.string.adv_flashlight, duration(app.flashlightTimeMs))
            DetailRow(R.string.adv_audio, duration(app.audioTimeMs)); DetailRow(R.string.adv_video, duration(app.videoTimeMs))
            DetailRow(R.string.adv_bt_scan, duration(app.bluetoothScanTimeMs)); DetailRow(R.string.adv_bt_unoptimized, duration(app.bluetoothUnoptimizedScanTimeMs))
            DetailRow(R.string.adv_mobile_rx, bytes(app.mobileRxBytes)); DetailRow(R.string.adv_mobile_tx, bytes(app.mobileTxBytes))
            DetailRow(R.string.adv_wifi_rx, bytes(app.wifiRxBytes)); DetailRow(R.string.adv_wifi_tx, bytes(app.wifiTxBytes))
        } }, confirmButton = { TextButton(onClick = { selected = null }) { Text(stringResource(R.string.adv_close)) } }) }
}

@Composable private fun KernelDetails(root: Boolean, battery: KernelStats.Battery?, refresh: () -> Unit) {
    val errors by RootStatsCollector.errors.collectAsStateWithLifecycle()
    val collected by RootStatsCollector.collectedAt.collectAsStateWithLifecycle()
    var cpu by remember { mutableStateOf(emptyList<KernelStats.Cpu>()) }
    var thermal by remember { mutableStateOf(emptyList<KernelStats.Thermal>()) }
    var locks by remember { mutableStateOf(emptyList<KernelStats.Wakelock>()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(root, refreshKey) {
        if (root) { busy = true
            try { cpu = RootStatsCollector.getCpuInfo(); thermal = RootStatsCollector.getThermalZones(); locks = RootStatsCollector.getKernelWakelocks() }
            finally { busy = false }
        } else { cpu = emptyList(); thermal = emptyList(); locks = emptyList() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DetailCard(stringResource(R.string.adv_kernel)) {
            Text(stringResource(R.string.adv_kernel_note))
            if (!root) Text(stringResource(R.string.adv_root_needed))
            collected.forEach { (source, at) -> Text(stringResource(R.string.adv_kernel_collected, source, date(at)), style = MaterialTheme.typography.bodySmall) }
            errors.forEach { (source, error) -> Text("$source: $error", color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = { refresh(); refreshKey++ }, enabled = !busy) { Text(stringResource(R.string.adv_refresh)) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            DetailRow(R.string.adv_cycle_count, battery?.cycleCount?.toString())
            DetailRow(R.string.adv_design, battery?.chargeFullDesign?.let { charge(it / 1000.0) })
            DetailRow(R.string.adv_learned, battery?.chargeFull?.let { charge(it / 1000.0) })
            DetailRow(R.string.adv_capacity_ratio, battery?.batteryAge?.let { String.format(Locale.getDefault(), "%.0f%%", it) })
            DetailRow(R.string.adv_kernel_health, battery?.health)
            DetailRow(R.string.adv_technology, battery?.technology)
            DetailRow(R.string.adv_reported_status, battery?.status)
            DetailRow(R.string.adv_present_charge, battery?.chargeNow?.let { charge(it / 1000.0) })
            DetailRow(R.string.adv_kernel_current, battery?.currentNow?.let { "$it µA" })
            DetailRow(R.string.adv_kernel_voltage, battery?.voltageNow?.let { "$it µV" })
            DetailRow(R.string.adv_kernel_temperature, battery?.tempNow?.let { "${it / 10.0} °C" })
            DetailRow(R.string.adv_kernel_time_empty, battery?.timeToEmptyNow?.let { duration(it * 1000) })
            DetailRow(R.string.adv_kernel_time_full, battery?.timeToFullNow?.let { duration(it * 1000) })
            Text(stringResource(R.string.adv_health_note), style = MaterialTheme.typography.bodySmall)
        }
        cpu.forEach { c -> DetailCard(stringResource(R.string.adv_cpu_policy, c.cluster)) {
            DetailRow(R.string.adv_frequency, c.currentFreq?.let { "$it kHz" }); DetailValue(c.governor ?: stringResource(R.string.adv_unavailable), "${c.minFreq ?: "—"}–${c.maxFreq ?: "—"} kHz")
            c.timeInState.forEach { (frequency, ticks) -> DetailValue("$frequency kHz", stringResource(R.string.adv_clock_ticks, ticks)) }
        } }
        thermal.forEach { t -> DetailCard(t.name) { DetailValue(t.type ?: stringResource(R.string.adv_unavailable), t.tempMilliC?.let { "${it / 1000.0} °C" })
            t.tripPoints.forEach { DetailValue(it.type, "${it.tempMilliC / 1000.0} °C") } } }
        locks.forEach { w -> DetailCard(w.name) {
            DetailRow(R.string.adv_count, w.count?.toString()); DetailRow(R.string.adv_duration, duration(w.totalTimeMs))
        } }
        if (cpu.isEmpty() && thermal.isEmpty() && locks.isEmpty()) MissingRows()
    }
}
@Composable private fun DetailCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium); content()
    } }
}
@Composable private fun DetailRow(label: Int, value: String?) = DetailValue(stringResource(label), value)
@Composable private fun DetailValue(label: String, value: String?) {
    Column { Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: stringResource(R.string.adv_unavailable), style = MaterialTheme.typography.bodyLarge) }
}
@Composable private fun MissingRows() { Text(stringResource(R.string.adv_no_rows), color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun truth(value: Boolean?): String? = value?.let { stringResource(if (it) R.string.adv_yes else R.string.adv_no) }
private fun duration(ms: Long?): String? = ms?.let { app.batstats.battery.drain.formatDuration(it) }
private fun date(ms: Long?): String = ms?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "—"
private fun charge(mah: Double?): String? = mah?.let { String.format(Locale.getDefault(), "%.2f mAh", it) }
private fun bytes(value: Long?): String? = value?.let { when { it >= 1_048_576 -> String.format(Locale.getDefault(), "%.1f MiB", it / 1_048_576.0)
    it >= 1024 -> String.format(Locale.getDefault(), "%.1f KiB", it / 1024.0); else -> "$it B" } }
