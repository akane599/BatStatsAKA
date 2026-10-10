package com.akane.voltwise.battery.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.akane.voltwise.settings.AppSettingsSchema
import com.akane.voltwise.settings.SettingsMigrations
import com.akane.voltwise.settings.SettingsMigrator
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.core.managers.MigrationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
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
        val retention = HistoryRetention(migrator, store)

        val cutoff = async { retention.cutoff(now, previousWallMs = now) }
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
        val retention = HistoryRetention(migrator, store)

        val cutoff = async { retention.cutoff(now, previousWallMs = now) }
        delay(100)
        assertFalse(cutoff.isCompleted)
        assertTrue(migrator.run() is MigrationResult.Success)
        assertEquals(now - 30 * 86_400_000L, withTimeout(10_000) { cutoff.await() })
    }

    @Test fun forwardJumpCannotAgeOutRealHistoryEvenAfterRestart() = runBlocking {
        val store = store()
        store.edit { it[intPreferencesKey("data_retention_index")] = 2 }
        val migrator = SettingsMigrator(store)
        migrator.run()
        val retention = HistoryRetention(migrator, store)
        val future = now + 365 * 86_400_000L
        val cutoff = retention.cutoff(future, 1_000, "run-1", now)
        assertEquals("A future wall clock must not advance the purge cutoff", now - 90 * 86_400_000L, cutoff)
        assertTrue("Recent persisted history must survive the jump", now - 30 * 86_400_000L >= cutoff!!)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        val reopenedScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val reopened = com.akane.voltwise.settings.createSettingsDataStore(File(folder.root, "settings.preferences_pb"), reopenedScope)
            val restartedMigrator = SettingsMigrator(reopened)
            restartedMigrator.run()
            val restarted = HistoryRetention(restartedMigrator, reopened)
            assertEquals("The future sample must not become a trusted reference after reopening the file", cutoff,
                restarted.cutoff(future, 2_000, "run-2", future))
        } finally {
            reopenedScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
    }

    @Test fun liveJumpAdvancesOnlyMonotonicTimeAndClockCorrectionRemainsSafe() = runBlocking {
        val store = store()
        val migrator = SettingsMigrator(store)
        migrator.run()
        val retention = HistoryRetention(migrator, store)
        val initial = retention.cutoff(now, 1_000, "run", now)!!
        assertEquals(initial + 30_000, retention.cutoff(now + 365 * 86_400_000L, 31_000, "run"))
        assertEquals(initial + 60_000, retention.cutoff(now + 60_000, 61_000, "run"))
        assertEquals(initial - 30_000, retention.cutoff(now - 30_000, 91_000, "run"))
        assertEquals("A monotonic reset cannot advance the durable clock", initial - 30_000,
            retention.cutoff(now + 365 * 86_400_000L, 0, "run"))
    }

    @Test fun noReferencePausesFirstPurgeAndMissingOrInvalidStoredChoiceStaysPaused() = runBlocking {
        val store = store()
        val migrator = SettingsMigrator(store)
        migrator.run()
        val retention = HistoryRetention(migrator, store)
        assertNull("No history reference means no first purge", retention.cutoff(now, 1_000, "run"))
        assertEquals(now + 30_000 - 90 * 86_400_000L, retention.cutoff(now + 30_000, 31_000, "run"))
        store.edit { it.remove(intPreferencesKey("data_retention_index")) }
        assertNull(retention.cutoff(now + 60_000, 61_000, "run"))
        store.edit { it[intPreferencesKey("data_retention_index")] = 99 }
        assertNull(retention.cutoff(now + 90_000, 91_000, "run"))
        store.edit { it[intPreferencesKey("data_retention_index")] = 2 }
        assertEquals("An explicit choice resumes age maintenance", now + 120_000 - 90 * 86_400_000L,
            retention.cutoff(now + 120_000, 121_000, "run"))
    }

    @Test fun normalClockWithStoredThreeMonthsExpiresOnlyOlderRows() = runBlocking {
        val store = store()
        store.edit { it[intPreferencesKey("data_retention_index")] = 2 }
        val migrator = SettingsMigrator(store)
        migrator.run()
        val retention = HistoryRetention(migrator, store)
        val cutoff = retention.cutoff(now, 1_000, "run", now)!!
        assertEquals(now - 90 * 86_400_000L, cutoff)
        assertTrue("Rows older than three months are eligible for purge", now - 91 * 86_400_000L < cutoff)
        assertTrue("The cutoff boundary remains retained", now - 90 * 86_400_000L >= cutoff)
    }

    @Test fun corruptStorePausesAgeRetentionWhileFreshInstallKeepsNinetyDays() = runBlocking {
        val file = File(folder.root, "corrupt.preferences_pb")
        file.writeBytes(byteArrayOf(0x0a, 0x7f, 0x01))
        val corrupt = com.akane.voltwise.settings.createSettingsDataStore(file, scope)
        val migrator = SettingsMigrator(corrupt)
        migrator.run()
        val retention = HistoryRetention(migrator, corrupt)
        assertNull("Corruption recovery must not silently impose 90-day retention", retention.cutoff(now, previousWallMs = now))
        val fresh = store()
        val freshMigrator = SettingsMigrator(fresh)
        freshMigrator.run()
        val defaults = HistoryRetention(freshMigrator, fresh)
        assertEquals("A genuine fresh store must explicitly retain the 90-day default", now - 90 * 86_400_000L,
            defaults.cutoff(now, previousWallMs = now))
    }

    @Test fun migrationThatDoesNotReachTheCurrentSchemaPausesRetention() = runBlocking {
        val store = store()
        // A store from a newer app version: kmp-settings refuses to downgrade it.
        store.edit { it[intPreferencesKey(SettingsMigrations.VERSION_KEY)] = SettingsMigrations.CURRENT_VERSION + 1 }
        val migrator = SettingsMigrator(store)
        val retention = HistoryRetention(migrator, store)

        assertTrue(migrator.run() is MigrationResult.DowngradeDetected)
        assertFalse(migrator.awaitMigrated())
        assertTrue(runCatching { retention.cutoff(now, previousWallMs = now) }.exceptionOrNull() is IllegalStateException)
    }
}
