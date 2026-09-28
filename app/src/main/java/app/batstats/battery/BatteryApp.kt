package app.batstats.battery

import android.app.Application
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.BatteryDatabase
import app.batstats.battery.shizuku.ShizukuBridge
import app.batstats.di.appModule
import app.batstats.settings.AppSettings
import app.batstats.settings.SettingsMigrator
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin

class BatteryApp : Application() {

    private val appScope: CoroutineScope by inject()
    private val settingsMigrator: SettingsMigrator by inject()
    private val shizukuBridge: ShizukuBridge by inject()

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@BatteryApp)
            modules(appModule)
        }

        shizukuBridge.warmUp()

        // Settings migration; history retention cleanup waits until it has ended.
        appScope.launch {
            settingsMigrator.run()
        }
    }
}

/**
 * Legacy accessor for components. Prefer using Koin injection directly.
 */
object BatteryGraph : KoinComponent {
    val db: BatteryDatabase by inject()
    val repo: BatteryRepository by inject()
    val settings: SettingsRepository<AppSettings> by inject()
}