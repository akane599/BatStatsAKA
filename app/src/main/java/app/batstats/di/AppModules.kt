package app.batstats.di

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.batstats.battery.apps.AppInfoRepository
import app.batstats.battery.apps.AppInfoSource
import app.batstats.battery.apps.AppStatsRepository
import app.batstats.battery.apps.AppStatsSource
import app.batstats.battery.apps.RoomSessionSnapshotStore
import app.batstats.battery.apps.SessionSnapshotCollector
import app.batstats.battery.apps.SessionSnapshotStore
import app.batstats.battery.apps.ShellRunnerStatsShell
import app.batstats.battery.data.DesignCapacitySource
import app.batstats.battery.diagnostics.DiagnosticCode
import app.batstats.battery.diagnostics.DiagnosticStore
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.CalibrationOverrides
import app.batstats.battery.data.CalibrationStore
import app.batstats.battery.data.ExportImportManager
import app.batstats.battery.data.HistoryMaintenance
import app.batstats.battery.data.HistoryRetention
import app.batstats.battery.data.db.BatteryDatabase
import app.batstats.battery.data.sampling.SamplerState
import app.batstats.battery.data.sampling.SamplingController
import app.batstats.battery.data.sampling.SharedPreferencesStore
import app.batstats.battery.drain.DrainNotificationManager
import app.batstats.battery.service.MonitoringControl
import app.batstats.battery.service.MonitoringController
import app.batstats.battery.service.SamplingDemand
import app.batstats.battery.shizuku.ShizukuBridge
import app.batstats.battery.util.ShellRunner
import app.batstats.settings.AppSettings
import app.batstats.settings.AppSettingsSchema
import app.batstats.settings.SettingsMigrations
import app.batstats.settings.SettingsMigrator
import app.batstats.settings.createSettingsDataStore
import app.batstats.settings.withDefaultsOnReadFailure
import app.batstats.viewmodel.AppDetailsViewModel
import app.batstats.viewmodel.AppsViewModel
import app.batstats.viewmodel.DataViewModel
import app.batstats.viewmodel.DefaultAppDetailsRepository
import app.batstats.viewmodel.DefaultAppsRepository
import app.batstats.viewmodel.DefaultDataRepository
import app.batstats.viewmodel.DefaultHealthRepository
import app.batstats.viewmodel.DefaultHistoryRepository
import app.batstats.viewmodel.DefaultNowRepository
import app.batstats.viewmodel.DefaultSessionDetailsRepository
import app.batstats.viewmodel.DefaultStatusRepository
import app.batstats.viewmodel.HealthViewModel
import app.batstats.viewmodel.HistoryViewModel
import app.batstats.viewmodel.KmpSettingsStore
import app.batstats.viewmodel.NowViewModel
import app.batstats.viewmodel.SessionDetailsViewModel
import app.batstats.viewmodel.SettingsViewModel
import app.batstats.viewmodel.StatusViewModel
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.core.backup.DeviceInfo
import io.github.mlmgames.settings.core.backup.SettingsBackupManager
import io.github.mlmgames.settings.core.managers.ResetManager
import io.github.mlmgames.settings.core.resources.AndroidStringResourceProvider
import io.github.mlmgames.settings.core.resources.StringResourceProvider
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

private const val SCHEMA_VERSION = SettingsMigrations.CURRENT_VERSION
private const val DATASTORE_NAME = "batstats_settings"
private const val RAW_SETTINGS_DATASTORE = "rawSettingsDataStore"

internal fun createAppScope(record: (DiagnosticCode) -> Unit): CoroutineScope = CoroutineScope(
    SupervisorJob() + CoroutineExceptionHandler { _, _ -> record(DiagnosticCode.APP_SCOPE_FAILED) },
)

val appModule = module {
    // Resolve diagnostics only on failure: DiagnosticStore itself depends on this scope.
    single { createAppScope { code -> get<DiagnosticStore>().record(code) } }
    single { BatteryDatabase.get(androidContext()) }
    single<DataStore<Preferences>>(named(RAW_SETTINGS_DATASTORE)) {
        createSettingsDataStore(androidContext().preferencesDataStoreFile(DATASTORE_NAME))
    }
    // One underlying store; only normal settings reads substitute defaults on IOException.
    single<DataStore<Preferences>> { get<DataStore<Preferences>>(named(RAW_SETTINGS_DATASTORE)).withDefaultsOnReadFailure() }

    single { ShizukuBridge(androidContext()) }
    single { ShellRunner(androidContext(), get()) }
    single { DiagnosticStore(androidContext(), get()) }
    // The one batterystats reader (on demand only; concurrent callers share a dump) and installed-app info.
    single { AppStatsRepository(ShellRunnerStatsShell(get()), get(), get<DiagnosticStore>()::record) } bind AppStatsSource::class
    single { AppInfoRepository(androidContext()) } bind AppInfoSource::class
    single<SessionSnapshotStore> { RoomSessionSnapshotStore(get()) }
    // Started and stopped by BatteryMonitorService.
    single { SessionSnapshotCollector(get(), get(), get<BatteryRepository>().powerTransitions) }

    single<SettingsRepository<AppSettings>> {
        SettingsRepository(dataStore = get(), schema = AppSettingsSchema)
    }

    single<StringResourceProvider> { AndroidStringResourceProvider(androidContext()) }
    single { ResetManager(get(), AppSettingsSchema) }
    // BatteryApp runs it at start; history retention waits for it (HistoryRetention).
    single { SettingsMigrator(get()) }

    single {
        val app = androidApplication()
        val appVersion = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) { "1.0.0" }

        SettingsBackupManager(
            dataStore = get(named(RAW_SETTINGS_DATASTORE)),
            schema = AppSettingsSchema,
            appId = "app.batstats",
            schemaVersion = SCHEMA_VERSION,
            deviceInfoProvider = { DeviceInfo("Android", Build.VERSION.RELEASE, appVersion) }
        )
    }

    // One design capacity for Now's Health card and the Health screen: sysfs is read once (root), then cached.
    single {
        DesignCapacitySource(get<SettingsRepository<AppSettings>>().flow, DesignCapacitySource::readRootChargeFullDesignUah, get())
    }

    single { HistoryMaintenance() }
    single { HistoryRetention(get(), get<SettingsRepository<AppSettings>>().flow) }
    single { ExportImportManager(androidContext(), get(), get()) }
    // One sampler thread per process; screens, the tile and details hold it as SamplingDemand.
    single { SamplingController(androidContext(), get()) } bind SamplingDemand::class
    single {
        val preferences = androidContext().getSharedPreferences(CalibrationStore.PREFS_NAME, Context.MODE_PRIVATE)
        val overrides = get<SettingsRepository<AppSettings>>().flow.map { settings ->
            CalibrationOverrides(settings.currentUnitOverride.unit, settings.currentSignOverride.sign)
        }
        CalibrationStore(SharedPreferencesStore(preferences), overrides, get())
    }
    single {
        val samplerPreferences = androidContext().getSharedPreferences(SamplerState.PREFS_NAME, Context.MODE_PRIVATE)
        BatteryRepository(get(), get(), get(), get(), get(), get(), get(), get(), SharedPreferencesStore(samplerPreferences))
    }
    single<MonitoringControl> { MonitoringController(androidContext(), get()) }

    single { DrainNotificationManager(androidContext(), get()) }

    viewModel {
        NowViewModel(
            DefaultNowRepository(get(), get(), get(), get(), get(), get(), get()), get(), get(),
            savedStateHandle = get(),
        )
    }
    viewModel { SettingsViewModel(KmpSettingsStore(get()), get()) }
    // The second get() is the nav entry's SavedStateHandle (mode, range, chip and selected day survive process death).
    viewModel { HistoryViewModel(DefaultHistoryRepository(get(), get()), get()) }
    viewModel { DataViewModel(DefaultDataRepository(androidContext(), get(), get(), get(), get()), get()) }
    viewModel { StatusViewModel(DefaultStatusRepository(androidContext(), get(), get(), get(), get(), get(), get())) }
    viewModel { HealthViewModel(DefaultHealthRepository(androidContext(), get(), get(), KmpSettingsStore(get()))) }
    // Apps' second get() is the nav entry's SavedStateHandle (sort, query and "show system" survive process death).
    viewModel { AppsViewModel(DefaultAppsRepository(androidContext(), get(), get(), get(), get()), get()) }
    viewModel { (uid: Int, packageName: String) ->
        AppDetailsViewModel(DefaultAppDetailsRepository(DefaultAppsRepository(androidContext(), get(), get(), get(), get()), get()), uid, packageName)
    }

    viewModel { (sessionId: String) ->
        SessionDetailsViewModel(DefaultSessionDetailsRepository(get(), get(), get(), get(), get(), get(), get()), get(), sessionId)
    }
}
