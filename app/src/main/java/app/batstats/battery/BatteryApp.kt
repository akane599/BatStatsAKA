package app.batstats.battery

import android.app.Application
import android.util.Log
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.BatteryDatabase
import app.batstats.battery.shizuku.ShizukuBridge
import app.batstats.di.appModule
import app.batstats.settings.AppSettings
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.core.managers.MigrationManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin

class BatteryApp : Application() {

    private val appScope: CoroutineScope by inject()
    private val migrationManager: MigrationManager by inject()
    private val shizukuBridge: ShizukuBridge by inject()

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@BatteryApp)
            modules(appModule)
        }

        shizukuBridge.warmUp()

        // Run migrations. This is a root coroutine: an uncaught failure here reaches
        // Android's default handler and kills the process during startup, before any
        // screen or reading exists. A failed migration must leave the stored values
        // untouched and the app usable, so it is reported, not fatal.
        appScope.launch {
            try {
                migrationManager.migrate()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("BatteryApp", "Settings migration failed (${t.javaClass.simpleName}); stored values are unchanged", t)
            }
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