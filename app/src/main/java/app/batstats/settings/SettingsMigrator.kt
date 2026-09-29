package app.batstats.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.mlmgames.settings.core.managers.MigrationManager
import io.github.mlmgames.settings.core.managers.MigrationResult
import kotlinx.coroutines.CompletableDeferred

/**
 * Runs the settings migration ([SettingsMigrations.steps] to [SettingsMigrations.CURRENT_VERSION])
 * once per process, from `BatteryApp`, and lets work that reads migrated values wait for it:
 * history retention must never see a v2 store, where "auto-cleanup off" is not yet Forever.
 */
class SettingsMigrator(dataStore: DataStore<Preferences>) {
    private val manager = MigrationManager(dataStore, SettingsMigrations.CURRENT_VERSION).apply {
        SettingsMigrations.steps.forEach { addMigration(it) }
    }
    private val migrated = CompletableDeferred<Boolean>()

    /** Migrates the store. [awaitMigrated] resumes when this ends, also if it throws. */
    suspend fun run(): MigrationResult {
        var reached = false
        try {
            return manager.migrate().also { reached = it is MigrationResult.Success || it is MigrationResult.NoMigrationNeeded }
        } finally {
            migrated.complete(reached)
        }
    }

    /** Suspends until [run] has ended; true when the store is at the current schema. */
    suspend fun awaitMigrated(): Boolean = migrated.await()
}
