package app.batstats.settings

import io.github.mlmgames.settings.core.SettingField
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SettingsImportPolicyTest {
    private fun backup(name: String, value: Any): String {
        @Suppress("UNCHECKED_CAST")
        val field = AppSettingsSchema.fields.first { it.name == name } as SettingField<AppSettings, Any>
        return buildJsonObject { putJsonObject("settings") { put(field.keyName, field.encodeValue(value)) } }.toString()
    }
    @Test fun rejectsOutOfRangeOptionsAndThresholdsBeforeImport() {
        listOf("monitoringIntervalIndex" to 99,
            "temperatureThreshold" to 100f, "lowBatteryThreshold" to -1, "totalSamplesCollected" to -4L).forEach { (name, value) ->
            assertThrows(IllegalArgumentException::class.java) { SettingsImportPolicy.validate(backup(name, value)) }
        }
        SettingsImportPolicy.validate(backup("monitoringIntervalIndex", 2))
        SettingsImportPolicy.validate(backup("temperatureThreshold", 45f))
        SettingsImportPolicy.validate(backup("lowBatteryAlertEnabled", false))
    }
    @Test fun invalidScalarAndNestedEncodedPayloadsCannotBypassValidation() {
        listOf("temperature_threshold" to "NaN", "low_battery_alert_enabled" to "not-a-boolean",
            "temperature_threshold" to ("[".repeat(40) + "0" + "]".repeat(40))).forEach { (key, payload) ->
            val input = buildJsonObject { putJsonObject("settings") { put(key, payload) } }.toString()
            assertThrows(IllegalArgumentException::class.java) { SettingsImportPolicy.validate(input) }
        }
    }
    @Test fun sizeAndNestingAreBoundedBeforeDeserialization() {
        assertThrows(IllegalArgumentException::class.java) { SettingsImportPolicy.validate(" ".repeat(SettingsImportPolicy.MAX_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { SettingsImportPolicy.validate("[".repeat(40) + "0" + "]".repeat(40)) }
        assertThrows(IllegalArgumentException::class.java) { SettingsImportPolicy.validate("{}") }
    }
}
