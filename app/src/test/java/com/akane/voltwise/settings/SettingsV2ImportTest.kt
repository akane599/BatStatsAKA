package com.akane.voltwise.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.core.backup.ExportResult
import io.github.mlmgames.settings.core.backup.ImportError
import io.github.mlmgames.settings.core.backup.ImportResult
import io.github.mlmgames.settings.core.backup.SettingsBackupManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Settings files through kmp-settings 0.8.3 plus [SettingsImportPolicy], as the Settings screen
 * imports them. The fixture is a genuine v2 export, written by the v2 schema through the library.
 */
class SettingsV2ImportTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @After fun close() = scope.cancel()

    private fun store(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "$name.preferences_pb") }

    private fun backups(store: DataStore<Preferences>) =
        SettingsBackupManager(store, AppSettingsSchema, "app.batstats", SettingsMigrations.CURRENT_VERSION)

    private val v2Export = checkNotNull(javaClass.getResource("/settings/v2-settings-export.json")).readText()

    private suspend fun storedKeys(store: DataStore<Preferences>) = store.data.first().asMap().keys.map { it.name }.toSet()

    @Test fun v2ExportImportsAsV3() = runBlocking {
        val store = store("target")
        val result = SettingsImportPolicy.import(v2Export, backups(store))

        assertTrue("$result", result is ImportResult.Success)
        result as ImportResult.Success
        assertEquals(0, result.skippedCount)
        assertEquals(emptyList<Pair<String, String>>(), result.errors)
        assertEquals(AppSettings(
            autoStartOnBoot = false,
            lowBatteryThreshold = 15,
            highBatteryAlertEnabled = true, highBatteryThreshold = 85,
            temperatureWarningEnabled = false, temperatureThreshold = 42f,
            dischargeAlertEnabled = true, dischargeCurrentThreshold = 700,
            chargingCompleteAlert = false,
            dynamicColors = true, oledBlack = true, temperatureUnitIndex = 1,
            // The file chose 1 month but had auto-cleanup off: v3 keeps the history forever.
            dataRetentionIndex = RETENTION_FOREVER_INDEX,
        ), SettingsRepository(store, AppSettingsSchema).flow.first())
        val keys = storedKeys(store)
        assertEquals(emptySet<String>(), keys intersect SettingsMigrations.V3_REMOVED_KEYS)
        assertTrue("Removed keys must not be quarantined: $keys", keys.none { it.startsWith(SettingsBackupManager.UNKNOWN_KEY_PREFIX) })
    }

    @Test fun corruptedV2ExportIsRejectedWithoutWriting() = runBlocking {
        val store = store("target")
        val tampered = v2Export.replace("\"low_battery_threshold\": \"i:15\"", "\"low_battery_threshold\": \"i:25\"")
        assertNotEquals(v2Export, tampered)
        val result = SettingsImportPolicy.import(tampered, backups(store))
        assertEquals(ImportError.CHECKSUM_MISMATCH, (result as ImportResult.Error).error)
        assertEquals(emptySet<String>(), storedKeys(store))
    }

    @Test fun v2ExportFromAnotherAppIsRejected() = runBlocking {
        val store = store("target")
        val result = SettingsImportPolicy.import(v2Export.replace("\"app.batstats\"", "\"other.app\""), backups(store))
        assertEquals(ImportError.APP_MISMATCH, (result as ImportResult.Error).error)
        assertEquals(emptySet<String>(), storedKeys(store))
    }

    @Test fun v3ExportCarriesTheOverridesAndRoundTrips() = runBlocking {
        val source = store("source")
        val chosen = AppSettings(statusIconValue = StatusIconValue.POWER_W, currentUnitOverride = CurrentUnitOverride.MILLIAMPS,
            currentSignOverride = CurrentSignOverride.INVERTED, designCapacityMah = 4_500, dataRetentionIndex = 0)
        SettingsRepository(source, AppSettingsSchema).update { chosen }
        val json = (backups(source).export() as ExportResult.Success).json

        val exported = Json.parseToJsonElement(json).jsonObject.getValue("settings").jsonObject.keys
        assertTrue(exported.containsAll(setOf("current_unit_override", "current_sign_override", "design_capacity_mah")))
        // Only schema keys: the detected calibration lives in its own non-exported preferences.
        assertEquals(emptySet<String>(), exported - AppSettingsSchema.fields.map { it.keyName }.toSet())

        val target = store("target")
        assertTrue(SettingsImportPolicy.import(json, backups(target)) is ImportResult.Success)
        assertEquals(chosen, SettingsRepository(target, AppSettingsSchema).flow.first())
    }
}
