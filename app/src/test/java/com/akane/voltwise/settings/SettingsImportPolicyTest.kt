package com.akane.voltwise.settings

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
        listOf("temperatureUnitIndex" to 99, "dataRetentionIndex" to 6,
            "temperatureThreshold" to 100f, "lowBatteryThreshold" to -1).forEach { (name, value) ->
            assertThrows(IllegalArgumentException::class.java) { SettingsImportPolicy.validate(backup(name, value)) }
        }
        SettingsImportPolicy.validate(backup("dataRetentionIndex", RETENTION_FOREVER_INDEX))
        SettingsImportPolicy.validate(backup("temperatureThreshold", 45f))
        SettingsImportPolicy.validate(backup("lowBatteryAlertEnabled", false))
    }
    @Test fun designCapacityIsAutoOrWithinTheMilliampHourRange() {
        listOf(-4, 1, 999, 30_001).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { SettingsImportPolicy.validate(backup("designCapacityMah", value)) }
        }
        listOf(0, 1_000, 4_500, 30_000).forEach { SettingsImportPolicy.validate(backup("designCapacityMah", it)) }
    }
    @Test fun enumSettingsAcceptOnlyTheirConstantNames() {
        SettingsImportPolicy.validate(backup("statusIconValue", StatusIconValue.POWER_W))
        SettingsImportPolicy.validate(backup("currentUnitOverride", CurrentUnitOverride.MILLIAMPS))
        SettingsImportPolicy.validate(backup("currentSignOverride", CurrentSignOverride.INVERTED))
        listOf("status_icon_value" to "s:VOLTS", "current_unit_override" to "s:milliamps", "current_sign_override" to "i:1").forEach { (key, payload) ->
            val input = buildJsonObject { putJsonObject("settings") { put(key, payload) } }.toString()
            assertThrows(IllegalArgumentException::class.java) { SettingsImportPolicy.validate(input) }
        }
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
