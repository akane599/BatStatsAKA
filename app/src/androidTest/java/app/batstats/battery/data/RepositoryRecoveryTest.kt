package app.batstats.battery.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.batstats.battery.data.db.BatteryDatabase
import app.batstats.battery.diagnostics.DiagnosticCode
import app.batstats.battery.diagnostics.DiagnosticStore
import app.batstats.settings.AppSettings
import app.batstats.settings.AppSettingsSchema
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Injected broadcasts/errors exercise recovery; they are not physical battery measurements. */
@RunWith(AndroidJUnit4::class)
class RepositoryRecoveryTest {
    internal class ReadingContext(base: Context) : ContextWrapper(base) {
        val directory = File(base.cacheDir, "battery-recovery-${UUID.randomUUID()}").apply { mkdirs() }
        @Volatile var missingBattery = false
        @Volatile var throwOnBattery = false
        @Volatile var rejectEvents = false
        @Volatile var registeredReceiver: BroadcastReceiver? = null
        override fun getNoBackupFilesDir(): File = directory
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {
            if (receiver != null) return super.registerReceiver(receiver, filter)
            if (throwOnBattery) throw SecurityException("Injected read failure")
            if (missingBattery) return null
            return Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 80).putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING)
                .putExtra(BatteryManager.EXTRA_PLUGGED, 0).putExtra(BatteryManager.EXTRA_VOLTAGE, 4000)
                .putExtra(BatteryManager.EXTRA_TEMPERATURE, 250).putExtra(BatteryManager.EXTRA_HEALTH, 2)
        }
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?, flags: Int): Intent? {
            if (receiver != null && rejectEvents) throw SecurityException("Injected subscription failure")
            // Script registration as well, so real sticky broadcasts cannot mask a failed poll.
            registeredReceiver = receiver
            return null
        }
        override fun unregisterReceiver(receiver: BroadcastReceiver?) { registeredReceiver = null }
    }

    internal class Fixture {
        val context = ReadingContext(ApplicationProvider.getApplicationContext())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val database = Room.inMemoryDatabaseBuilder(context, BatteryDatabase::class.java).build()
        private val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(context.directory, "test.preferences_pb")
        }
        val settings = SettingsRepository<AppSettings>(dataStore = dataStore, schema = AppSettingsSchema)
        val diagnostics = DiagnosticStore(context, scope)
        val repository = BatteryRepository(context, database, settings, scope, HistoryMaintenance(), diagnostics)
        suspend fun refresh() = withTimeout(60_000) {
            val result = CompletableDeferred<BatteryRepository.Realtime>()
            repository.refreshNow { result.complete(it) }
            result.await()
        }
        suspend fun close() {
            withContext(Dispatchers.Main.immediate) { repository.stopSampling() }
            scope.coroutineContext[Job]!!.cancelAndJoin()
            database.close()
            context.directory.deleteRecursively()
        }
    }

    @Test fun stoppedRefreshRecoversFromMissingReadingWithoutStartingMonitoring() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.context.missingBattery = true
            assertNull(fixture.refresh().sample)
            withTimeout(60_000) { fixture.repository.error.first { it?.contains("not supplied") == true } }
            fixture.context.missingBattery = false
            assertEquals(80, fixture.refresh().level)
            withTimeout(60_000) { fixture.repository.error.first { it == null } }
            assertFalse(fixture.repository.isMonitoringFlow.value)
            assertNull(fixture.repository.observation.value.startedAt)
            assertEquals(0, fixture.database.batteryDao().count())
        } finally { fixture.close() }
    }

    @Test fun failedSubscriptionKeepsSnapshotsButCannotInventObservedPeriods() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.context.rejectEvents = true
            fixture.repository.startSampling()
            withTimeout(60_000) { fixture.repository.error.first { it?.contains("State events") == true } }
            assertEquals(80, fixture.refresh().level)
            assertEquals(80, fixture.refresh().level)
            assertTrue(fixture.repository.error.value!!.contains("State events"))
            assertNull(fixture.repository.observation.value.startedAt)
            assertEquals(0L, fixture.repository.observation.value.screenOff.durationMs)
            assertEquals(0, fixture.database.batteryDao().count())
            assertEquals(0, fixture.database.sessionDao().filteredSessions(null, "", 10).first().size)
            // Restarting monitoring retries registration; an ordinary refresh must not hide its failure.
            fixture.repository.stopSampling()
            withTimeout(60_000) { fixture.repository.isMonitoringFlow.first { !it } }
            fixture.context.rejectEvents = false
            fixture.repository.startSampling()
            withTimeout(60_000) { fixture.repository.observation.first { it.startedAt != null } }
            withTimeout(60_000) { fixture.repository.error.first { it == null } }
        } finally { fixture.close() }
    }

    @Test fun automaticAndBroadcastReadExceptionsAreRecoverable() = runBlocking {
        val fixture = Fixture()
        try {
            // Force the first automatic poll to fail; the service's sampling coroutine must survive.
            fixture.settings.update { it.copy(monitoringIntervalIndex = 0) }
            fixture.context.throwOnBattery = true
            fixture.repository.startSampling()
            withTimeout(60_000) { fixture.diagnostics.events.first { events -> events.any { it.code == DiagnosticCode.BATTERY_READ_FAILED } } }
            assertTrue(fixture.repository.isMonitoringFlow.value)
            fixture.context.throwOnBattery = false
            // The next scheduled poll must recover without a manual refresh or service restart.
            withTimeout(60_000) { fixture.repository.observation.first { it.startedAt != null } }
            val previous = fixture.repository.observation.value.latest!!.elapsedMs
            fixture.context.throwOnBattery = true
            withContext(Dispatchers.Main) {
                fixture.context.registeredReceiver!!.onReceive(fixture.context, Intent(Intent.ACTION_SCREEN_OFF))
            }
            withTimeout(60_000) { fixture.repository.error.first { it?.contains("SecurityException") == true } }
            fixture.context.throwOnBattery = false
            fixture.refresh()
            withTimeout(60_000) { fixture.repository.observation.first { (it.latest?.elapsedMs ?: 0) > previous && it.gaps > 0 } }
            withTimeout(60_000) { fixture.repository.error.first { it == null } }
            assertTrue(fixture.repository.isMonitoringFlow.value)
        } finally { fixture.close() }
    }

    @Test fun failedStorageRestartCannotReuseThePreviousObservationAndOrdinaryReadsStayAvailable() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.repository.startSampling()
            withTimeout(60_000) { fixture.repository.observation.first { it.startedAt != null } }
            fixture.repository.stopSampling()
            withTimeout(60_000) { fixture.repository.isMonitoringFlow.first { !it } }
            fixture.database.close() // Deliberate storage failure, isolated from the app's normal database.
            fixture.repository.startSampling()
            withTimeout(60_000) { fixture.repository.isMonitoringFlow.first { it } }
            withTimeout(60_000) { fixture.repository.observation.first { it.startedAt == null } }
            withTimeout(60_000) { fixture.repository.error.first { it?.contains("History collection failed") == true } }
            fixture.context.missingBattery = true
            fixture.refresh()
            withTimeout(60_000) { fixture.repository.error.first { it?.contains("not supplied") == true } }
            fixture.context.missingBattery = false
            assertEquals(80, fixture.refresh().level)
            withTimeout(60_000) { fixture.repository.error.first { it != null && !it.contains("not supplied") } }
            assertTrue(fixture.repository.error.value!!.contains("History collection failed"))
            assertNull(fixture.repository.observation.value.latest)
        } finally { fixture.close() }
    }
}
