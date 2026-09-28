package app.batstats.battery.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import app.batstats.settings.AppSettingsSchema
import app.batstats.settings.SettingsMigrations
import app.batstats.settings.SettingsMigrator
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.core.managers.MigrationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The writer's retention cleanup starts with [HistoryRetention.cutoff]: it must wait for the
 * settings migration that `BatteryApp` runs, and then see the v3 retention.
 */
class HistoryRetentionTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @After fun close() = scope.cancel()

    private val now = 1_790_000_000_000L

    private fun store(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }

    /** A fresh v2 install (no recorded version) that keeps 1 month of history. */
    private suspend fun seedV2(store: DataStore<Preferences>, autoCleanup: Boolean) = store.edit {
        it[booleanPreferencesKey("auto_cleanup_enabled")] = autoCleanup
        it[intPreferencesKey("data_retention_index")] = 1
    }

    @Test fun cleanupRequestedBeforeTheMigrationRunsAfterItWithRetentionForever() = runBlocking {
        val store = store()
        seedV2(store, autoCleanup = false)
        val settings = SettingsRepository(store, AppSettingsSchema)
        val migrator = SettingsMigrator(store)
        val retention = HistoryRetention(migrator, settings.flow)

        val cutoff = async { retention.cutoff(now) }
        delay(300)
        assertFalse("Retention must not read settings before the migration has ended", cutoff.isCompleted)
        // Unmigrated, the store still says 1 month: a read at this point would purge.
        assertEquals(1, settings.flow.first().dataRetentionIndex)

        assertTrue(migrator.run() is MigrationResult.Success)
        assertNull("Auto-cleanup off is retention Forever once migrated", withTimeout(10_000) { cutoff.await() })
    }

    @Test fun autoCleanupOnKeepsItsRetentionAfterTheMigration() = runBlocking {
        val store = store()
        seedV2(store, autoCleanup = true)
        val migrator = SettingsMigrator(store)
        val retention = HistoryRetention(migrator, SettingsRepository(store, AppSettingsSchema).flow)

        val cutoff = async { retention.cutoff(now) }
        delay(100)
        assertFalse(cutoff.isCompleted)
        assertTrue(migrator.run() is MigrationResult.Success)
        assertEquals(now - 30 * 86_400_000L, withTimeout(10_000) { cutoff.await() })
    }

    @Test fun migrationThatDoesNotReachTheCurrentSchemaPausesRetention() = runBlocking {
        val store = store()
        // A store from a newer app version: kmp-settings refuses to downgrade it.
        store.edit { it[intPreferencesKey(SettingsMigrations.VERSION_KEY)] = SettingsMigrations.CURRENT_VERSION + 1 }
        val migrator = SettingsMigrator(store)
        val retention = HistoryRetention(migrator, SettingsRepository(store, AppSettingsSchema).flow)

        assertTrue(migrator.run() is MigrationResult.DowngradeDetected)
        assertFalse(migrator.awaitMigrated())
        assertTrue(runCatching { retention.cutoff(now) }.exceptionOrNull() is IllegalStateException)
    }
}
