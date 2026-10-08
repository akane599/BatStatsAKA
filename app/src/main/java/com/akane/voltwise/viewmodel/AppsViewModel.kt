package com.akane.voltwise.viewmodel

import androidx.compose.runtime.Immutable
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akane.voltwise.battery.apps.AppInfo
import com.akane.voltwise.battery.apps.AppInfoSource
import com.akane.voltwise.battery.apps.AppLabel
import com.akane.voltwise.battery.apps.AppStatsRepository
import com.akane.voltwise.battery.apps.AppStatsResult
import com.akane.voltwise.battery.apps.UidIdentity
import com.akane.voltwise.battery.apps.identity
import com.akane.voltwise.battery.shizuku.ShizukuBridge
import com.akane.voltwise.battery.util.BatteryStatsParser
import com.akane.voltwise.battery.util.DumpOutput
import com.akane.voltwise.battery.util.ShellRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How the Apps list is ordered; each order is also the value its rows show. */
enum class AppSort { BATTERY, CPU, FOREGROUND, BACKGROUND, NETWORK }

/** The access facts a "no access" result is explained with, read when the result comes back. */
data class AccessSnapshot(
    val mode: ShellRunner.Mode,
    val lastError: String?,
    val shizuku: ShizukuState,
)

/** Why Android's per-app stats can't be read: the access banner's message. */
enum class AccessProblem {
    /** No Shizuku, root or ADB grant. */
    NOT_SET_UP,

    /** Shizuku runs but hasn't allowed BatStats yet; NoAccess.blocked distinguishes a permanent denial. */
    SHIZUKU_NOT_ALLOWED,

    /** ADB grants exist, but this Android version refuses the per-app dump to them (Shizuku or root needed). */
    ADB_NOT_ENOUGH,

    /** Android refused the dump to the current access mode. */
    REFUSED,
    ;

    companion object {
        /** A refusal counts only while a mode is in use: a forced probe that finds none leaves an older error behind. */
        fun of(access: AccessSnapshot): AccessProblem = when {
            access.shizuku.running && !access.shizuku.granted -> SHIZUKU_NOT_ALLOWED
            access.mode == ShellRunner.Mode.ADB && access.lastError == DumpOutput.REFUSED_CROSS_USER -> ADB_NOT_ENOUGH
            access.mode != ShellRunner.Mode.NONE && DumpOutput.isRefusal(access.lastError) -> REFUSED
            else -> NOT_SET_UP
        }
    }
}

/** Why a read with access failed. */
enum class ReadProblem {
    /** Android answered in a format the parser can't use. */
    FORMAT,

    /** The Shizuku helper failed; restarting Shizuku usually helps. */
    SHIZUKU,
    OTHER,
    ;

    companion object {
        fun of(message: String, mode: ShellRunner.Mode): ReadProblem = when {
            message == AppStatsRepository.FORMAT_UNAVAILABLE -> FORMAT
            mode == ShellRunner.Mode.SHIZUKU -> SHIZUKU
            else -> OTHER
        }
    }
}

/** The last read's problem; none while the last read was good. */
sealed interface StatsProblem {
    @Immutable
    data class NoAccess(val problem: AccessProblem, val blocked: Boolean = false) : StatsProblem
    @Immutable
    data class Failed(val problem: ReadProblem) : StatsProblem
}

/**
 * Android's per-app stats and installed-app info, as Apps and AppDetails read them: [DefaultAppsRepository] on
 * device, a fake in unit tests. Only [snapshot] can start a privileged dump.
 */
interface AppStatsReader {
    /** The last good dump, kept past its TTL; reading it never dumps. */
    val cached: StateFlow<BatteryStatsParser.FullSnapshot?>

    /** A change here can make the stats readable (Shizuku started or allowed) or not. */
    val shizuku: Flow<ShizukuState>

    /** Cached for 60 s unless [force] (which also re-probes access); never throws. */
    suspend fun snapshot(force: Boolean): AppStatsResult

    /** The access facts right now (mode, the shell's last error, Shizuku). */
    fun access(): AccessSnapshot

    suspend fun info(packageName: String): AppInfo
}

/** [AppStatsReader] plus what the list needs to tell apps from system components. */
interface AppsRepository : AppStatsReader {
    /** Packages with a launcher entry: a preinstalled package the user can open still counts as an app. */
    suspend fun launchablePackages(): Set<String>
}

/** [AppsRepository] over [AppStatsRepository], [AppInfoSource], [ShellRunner] and [ShizukuBridge]. */
class DefaultAppsRepository(
    private val context: Context,
    private val appStats: AppStatsRepository,
    private val appInfo: AppInfoSource,
    private val shell: ShellRunner,
    private val shizukuBridge: ShizukuBridge,
) : AppsRepository {
    override val cached = appStats.cached
    override val shizuku = combine(shizukuBridge.running, shizukuBridge.granted, shizukuBridge.blocked, ::ShizukuState).distinctUntilChanged()

    override suspend fun snapshot(force: Boolean) = appStats.snapshot(force)

    override fun access() = AccessSnapshot(
        mode = shell.access.value,
        lastError = shell.lastError.value,
        shizuku = ShizukuState(shizukuBridge.running.value, shizukuBridge.granted.value, shizukuBridge.blocked.value),
    )

    override suspend fun info(packageName: String) = appInfo.info(packageName)

    override suspend fun launchablePackages(): Set<String> = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val manager = context.packageManager
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            manager.queryIntentActivities(intent, 0)
        }
        activities.mapTo(HashSet()) { it.activityInfo.packageName }
    }
}

/** [AppStatsReader.info], with a failing lookup read as "unknown" (the label rule then says what the row is). */
internal suspend fun AppStatsReader.infoOrNull(packageName: String): AppInfo? = try {
    info(packageName)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/**
 * One screen's reads of Android's per-app stats: reads in flight (for the refresh indicator) and the last read's
 * problem. A read starts only from [start] (the screen became visible) or [load] (pull-to-refresh, Try again).
 * When Shizuku starts or allows BatStats while the screen is visible, it re-reads with a fresh access probe; while
 * the screen is away, the next [start] does. Nothing reads on a timer.
 */
internal class StatsLoader(private val scope: CoroutineScope, private val source: AppStatsReader) {
    private val inFlight = MutableStateFlow(0)
    private val _problem = MutableStateFlow<StatsProblem?>(null)
    private var started = false
    private var accessChanged = false
    private var requested = 0L
    private var applied = 0L

    val loading: Flow<Boolean> = inFlight.map { it > 0 }.distinctUntilChanged()
    val problem: StateFlow<StatsProblem?> = _problem.asStateFlow()

    init {
        scope.launch {
            source.shizuku.distinctUntilChanged().drop(1).collect {
                if (started) load(force = true) else accessChanged = true
            }
        }
    }

    fun start() {
        started = true
        val force = accessChanged
        accessChanged = false
        load(force)
    }

    fun stop() {
        started = false
    }

    /** A new read; a non-forced one is skipped while another runs (a forced one joins the dump in flight). */
    fun load(force: Boolean) {
        if (!force && inFlight.value > 0) return
        val id = ++requested
        inFlight.update { it + 1 }
        scope.launch {
            try {
                val result = source.snapshot(force)
                // An older read finishing late never overwrites a newer read's outcome.
                if (id >= applied) {
                    applied = id
                    _problem.value = problemOf(result)
                }
            } finally {
                inFlight.update { it - 1 }
            }
        }
    }

    private fun problemOf(result: AppStatsResult): StatsProblem? = when (result) {
        is AppStatsResult.Ready -> null
        AppStatsResult.NoAccess -> source.access().let { access ->
            StatsProblem.NoAccess(
                AccessProblem.of(access),
                blocked = access.shizuku.running && !access.shizuku.granted && access.shizuku.blocked,
            )
        }
        is AppStatsResult.Failed -> StatsProblem.Failed(ReadProblem.of(result.message, source.access().mode))
    }
}

/** "Android stats since …": the dump's window and device-wide times. Durations in ms; null when Android gave none. */
@Immutable
data class StatsSummary(
    val startedAtMs: Long?,
    val capturedAtMs: Long,
    val onBatteryMs: Long?,
    val screenOnMs: Long?,
    val screenOffMs: Long?,
    /** Battery percentage points used with the screen on / off. */
    val screenOnUsedPercent: Int?,
    val screenOffUsedPercent: Int?,
    val deepDozeMs: Long?,
    val lightDozeMs: Long?,
    val capacityMah: Double?,
) {
    companion object {
        fun of(snapshot: BatteryStatsParser.FullSnapshot) = StatsSummary(
            startedAtMs = snapshot.startedAt,
            capturedAtMs = snapshot.capturedAt,
            onBatteryMs = snapshot.batteryRealtimeMs,
            screenOnMs = snapshot.screenOnTimeMs,
            screenOffMs = snapshot.screenOffTimeMs,
            screenOnUsedPercent = snapshot.screenOnDischargePercent,
            screenOffUsedPercent = snapshot.screenOffDischargePercent,
            deepDozeMs = snapshot.doze?.deepIdleTimeMs,
            lightDozeMs = snapshot.doze?.lightIdleTimeMs,
            capacityMah = snapshot.estimatedCapacityMah,
        )
    }
}

/**
 * One app in the list: [value] is the current sort's metric (mAh, ms, or bytes; null when Android gave none) and
 * [share] its part of every app's total for that metric (0..1, hidden system apps included). [sharedBy] is the number
 * of packages sharing the uid when there are several (0 otherwise): the row's figures are theirs together, and
 * [packageName]/[label] are only its stable representative.
 */
@Immutable
data class AppListRow(
    val uid: Int,
    val packageName: String,
    val label: AppLabel,
    val value: Double?,
    val share: Float,
    val sharedBy: Int = 0,
)

/**
 * The Apps tab. [summary] is null until a good read (then the list shows); [problem] is the last read's (a banner,
 * also over stale data). [loading] drives the refresh indicator. [rows] are filtered by [query] and [showSystem] and
 * ordered by [sort]; [hiddenSystem] counts search matches hidden because they're system components.
 */
@Immutable
data class AppsUiState(
    val nowMs: Long = 0,
    val loading: Boolean = false,
    val problem: StatsProblem? = null,
    val summary: StatsSummary? = null,
    val rows: List<AppListRow> = emptyList(),
    val hiddenSystem: Int = 0,
    val sort: AppSort = AppSort.BATTERY,
    val query: String = "",
    val showSystem: Boolean = false,
)

sealed interface AppsEvent {
    data object Refresh : AppsEvent
    data class SetSort(val sort: AppSort) : AppsEvent
    data class SetQuery(val query: String) : AppsEvent
    data class SetShowSystem(val show: Boolean) : AppsEvent
    data class OpenApp(val uid: Int, val packageName: String) : AppsEvent
    data object OpenAccessSetup : AppsEvent
    data object AllowShizuku : AppsEvent
}

/**
 * Apps: Android's per-app stats since its last full charge, read on demand (the screen opening, pull-to-refresh)
 * through [AppsRepository.snapshot]. Labels and the system flag are resolved once per dump on [computeDispatcher];
 * sort, search and the system toggle live in [savedState], so they survive rotation and process death.
 * "System" means a system uid (a process, not an app) or a system package without a launcher entry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppsViewModel(
    private val source: AppsRepository,
    private val savedState: SavedStateHandle,
    private val clock: () -> Long = System::currentTimeMillis,
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val loader = StatsLoader(viewModelScope, source)

    /**
     * The search text for the field: Compose state written synchronously on each keystroke, so fast typing or an
     * IME never loses characters or moves the cursor (a flow collected in the UI lands a frame late). It is also
     * saved (process death), from where [queryFlow] feeds the filter.
     */
    var query: String by mutableStateOf(savedState[KEY_QUERY] ?: "")
        private set
    private val queryFlow: StateFlow<String> = savedState.getStateFlow(KEY_QUERY, "")
    private val sort = savedState.getStateFlow(KEY_SORT, AppSort.BATTERY.name)
        .map { name -> AppSort.entries.firstOrNull { it.name == name } ?: AppSort.BATTERY }
    private val showSystem = savedState.getStateFlow(KEY_SHOW_SYSTEM, false)

    private class Entry(val stats: BatteryStatsParser.AppPowerStats, val identity: UidIdentity, val label: AppLabel, val system: Boolean) {
        val packageName: String get() = identity.packageName
        val sharedBy: Int get() = (identity as? UidIdentity.Shared)?.memberCount ?: 0
    }
    private class Catalog(val summary: StatsSummary, val entries: List<Entry>)
    private data class Controls(val sort: AppSort, val query: String, val showSystem: Boolean)

    private val catalog: Flow<Catalog?> = source.cached.mapLatest { snapshot -> snapshot?.let { catalog(it) } }

    private val controls = combine(sort, queryFlow, showSystem, ::Controls)

    val state: StateFlow<AppsUiState> = combine(catalog, controls, loader.loading, loader.problem) { catalog, controls, loading, problem ->
        val base = AppsUiState(
            nowMs = clock(),
            loading = loading,
            problem = problem,
            sort = controls.sort,
            query = controls.query,
            showSystem = controls.showSystem,
        )
        if (catalog == null) base else list(base, catalog, controls)
    }.flowOn(computeDispatcher).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AppsUiState())

    fun onStart() = loader.start()

    fun onStop() = loader.stop()

    fun onEvent(event: AppsEvent) {
        when (event) {
            AppsEvent.Refresh -> loader.load(force = true)
            is AppsEvent.SetSort -> savedState[KEY_SORT] = event.sort.name
            is AppsEvent.SetQuery -> {
                query = event.query
                savedState[KEY_QUERY] = event.query
            }
            is AppsEvent.SetShowSystem -> savedState[KEY_SHOW_SYSTEM] = event.show
            // Navigation and Shizuku's permission prompt are the screen wrapper's.
            is AppsEvent.OpenApp, AppsEvent.OpenAccessSetup, AppsEvent.AllowShizuku -> Unit
        }
    }

    private suspend fun catalog(snapshot: BatteryStatsParser.FullSnapshot): Catalog {
        val launchable = try {
            source.launchablePackages()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptySet()
        }
        val entries = snapshot.apps.map { app ->
            // A2's rows name uid-only apps by their uid ("UID 10123"); the label rule turns those into words.
            // A shared uid is labelled by its stable representative; the row also says it's shared.
            val identity = app.identity()
            val packageName = identity.packageName
            val info = source.infoOrNull(packageName)
            val system = BatteryStatsParser.isSystemUid(app.uid) || (info?.isSystem == true && packageName !in launchable)
            Entry(app, identity, AppLabel.of(app.uid, packageName, info), system)
        }
        return Catalog(StatsSummary.of(snapshot), entries)
    }

    private fun list(base: AppsUiState, catalog: Catalog, controls: Controls): AppsUiState {
        val needle = controls.query.trim()
        val matches = catalog.entries.filter { needle.isEmpty() || it.matches(needle) }
        val shown = if (controls.showSystem) matches else matches.filterNot { it.system }
        val total = catalog.entries.sumOf { metric(it.stats, controls.sort)?.coerceAtLeast(0.0) ?: 0.0 }
        val rows = shown
            .map { entry ->
                val value = metric(entry.stats, controls.sort)
                val share = if (value != null && total > 0) (value.coerceAtLeast(0.0) / total).toFloat() else 0f
                AppListRow(entry.stats.uid, entry.packageName, entry.label, value, share, entry.sharedBy)
            }
            .sortedWith(
                compareByDescending<AppListRow, Double?>(nullsFirst<Double>()) { it.value }
                    .thenBy { (it.label as? AppLabel.Named)?.text?.lowercase() ?: it.packageName }
                    .thenBy { it.uid },
            )
        return base.copy(
            summary = catalog.summary,
            rows = rows,
            hiddenSystem = matches.size - shown.size,
        )
    }

    private fun Entry.matches(needle: String): Boolean =
        (label as? AppLabel.Named)?.text?.contains(needle, ignoreCase = true) == true ||
            packageName.contains(needle, ignoreCase = true)

    companion object {
        const val KEY_SORT = "apps.sort"
        const val KEY_QUERY = "apps.query"
        const val KEY_SHOW_SYSTEM = "apps.showSystem"
        private const val STOP_TIMEOUT_MS = 5_000L

        /** The value a row shows for [sort]: mAh, ms, or bytes (mobile + Wi-Fi, both directions). */
        fun metric(app: BatteryStatsParser.AppPowerStats, sort: AppSort): Double? = when (sort) {
            AppSort.BATTERY -> app.powerMah
            AppSort.CPU -> app.cpuTimeMs?.toDouble()
            AppSort.FOREGROUND -> app.foregroundTimeMs?.toDouble()
            AppSort.BACKGROUND -> app.backgroundTimeMs?.toDouble()
            AppSort.NETWORK -> listOfNotNull(app.mobileRxBytes, app.mobileTxBytes, app.wifiRxBytes, app.wifiTxBytes)
                .takeIf { it.isNotEmpty() }
                ?.sumOf { it.toDouble() }
        }
    }
}
