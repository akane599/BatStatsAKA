package app.batstats.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.core.managers.MigrationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class SettingsDataStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val reopenedScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After fun close(): Unit = runBlocking {
        scope.coroutineContext[Job]?.cancelAndJoin()
        reopenedScope.coroutineContext[Job]?.cancelAndJoin()
    }

    @Test fun corruptedStoreResetsToDefaultsAndRemainsWritableAfterRestart() = runBlocking {
        val file = File(folder.root, "batstats_settings.preferences_pb")
        file.writeBytes(byteArrayOf(0x0a, 0x7f, 0x01)) // Truncated length-delimited protobuf field.
        val store = createSettingsDataStore(file, scope)
        val recovered = try {
            store.data.first()
        } catch (failure: CorruptionException) {
            throw AssertionError("Corrupt settings must recover to empty preferences, not fail startup", failure)
        }
        assertEquals(emptyPreferences(), recovered)
        val repository = SettingsRepository(store, AppSettingsSchema)
        assertEquals(AppSettings(), repository.flow.first())
        val migrator = SettingsMigrator(store)
        assertTrue(migrator.run() is MigrationResult.Success)
        assertTrue(migrator.awaitMigrated())
        repository.set("lowBatteryThreshold", 15)
        scope.coroutineContext[Job]?.cancelAndJoin()

        val reopened = createSettingsDataStore(file, reopenedScope)
        assertEquals(15, SettingsRepository(reopened, AppSettingsSchema).flow.first().lowBatteryThreshold)
        assertEquals(SettingsMigrations.CURRENT_VERSION, reopened.data.first()[intPreferencesKey(SettingsMigrations.VERSION_KEY)])
    }

    @Test fun readIOExceptionFallsBackToEmptyPreferencesAndSchemaDefaults() = runBlocking {
        val file = folder.newFolder("batstats_settings.preferences_pb")
        val store = createSettingsDataStore(file, scope)
        val recovered = try {
            store.data.first()
        } catch (failure: IOException) {
            throw AssertionError("Settings read IOException must fall back to empty preferences", failure)
        }
        assertEquals(emptyPreferences(), recovered)
        assertEquals(AppSettings(), SettingsRepository(store, AppSettingsSchema).flow.first())
        assertTrue("Read fallback must not replace the unreadable path", file.isDirectory)
    }

    @Test fun updateIOExceptionStillPropagates() {
        val store = createSettingsDataStore(folder.newFolder("batstats_settings.preferences_pb"), scope)
        assertThrows(IOException::class.java) {
            runBlocking { store.updateData { emptyPreferences() } }
        }
    }

    @Test fun cancelledStoreDoesNotEmitFallbackPreferences() = runBlocking {
        val store = createSettingsDataStore(File(folder.root, "batstats_settings.preferences_pb"), scope)
        scope.coroutineContext[Job]?.cancelAndJoin()
        assertThrows(CancellationException::class.java) {
            runBlocking { store.data.first() }
        }
        Unit
    }

    @Test fun nonIOExceptionDoesNotEmitFallbackPreferences() {
        val store = createSettingsDataStore(File(folder.root, "invalid_extension"), scope)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.data.first() }
        }
    }

    @Test fun healthyStoreKeepsExistingSettings() = runBlocking {
        val file = File(folder.root, "batstats_settings.preferences_pb")
        val repository = SettingsRepository(createSettingsDataStore(file, scope), AppSettingsSchema)
        repository.set("lowBatteryThreshold", 10)
        repository.set("autoStartOnBoot", false)
        scope.coroutineContext[Job]?.cancelAndJoin()

        val reopened = SettingsRepository(createSettingsDataStore(file, reopenedScope), AppSettingsSchema)
        assertEquals(AppSettings(lowBatteryThreshold = 10, autoStartOnBoot = false), reopened.flow.first())
    }
}
