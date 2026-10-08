package com.akane.voltwise.settings

import com.akane.voltwise.battery.data.LimitedHistoryInput
import io.github.mlmgames.settings.core.backup.ImportOptions
import io.github.mlmgames.settings.core.backup.ImportResult
import io.github.mlmgames.settings.core.backup.SettingsBackupManager
import kotlinx.serialization.json.*

/** Validate the whole file before the library edits preferences; checksum/app/schema checks still run there. */
object SettingsImportPolicy {
    const val MAX_BYTES = 64 * 1024
    fun validate(text: String) {
        require(text.length <= MAX_BYTES) { "Settings file is too large" }
        checkStructure(text, MAX_BYTES)
        val root = Json.parseToJsonElement(text).jsonObject
        val settings = requireNotNull(root["settings"]?.jsonObject) { "Missing settings map" }
        require(settings.size <= 256)
        for ((key, encoded) in settings) {
            require(key.length <= 256 && encoded is JsonPrimitive && encoded.isString)
            require(encoded.content.length <= 1024)
            checkStructure(encoded.content, 1024)
            val field = AppSettingsSchema.fields.find { it.keyName == key } ?: continue
            val value = field.decodeValue(encoded.content)
            require(value != null) { "Invalid setting value" }
            if (value is Number) {
                val number = value.toDouble()
                require(number.isFinite() && number >= 0)
                field.meta?.let { meta ->
                    if (meta.options.isNotEmpty()) require(number % 1 == 0.0 && number.toInt() in meta.options.indices)
                    if (meta.type == io.github.mlmgames.settings.core.types.Slider::class) require(number in meta.min.toDouble()..meta.max.toDouble())
                }
                if (field.name == "designCapacityMah") require(DesignCapacity.isValid(number.toInt())) { "Invalid design capacity" }
            }
        }
    }

    /**
     * [validate]s [text] and imports it through [manager]. An export from an older schema is
     * migrated first ([SettingsMigrations.upgradeExport]): the library checks the original file's
     * app, schema and checksum, then the migrated copy is applied without a second checksum check,
     * since the migrated map no longer matches it. A rejected original is imported unchanged only
     * to return the library's own error; the library writes nothing in that case.
     */
    suspend fun import(text: String, manager: SettingsBackupManager<AppSettings>): ImportResult {
        validate(text)
        val root = Json.parseToJsonElement(text).jsonObject
        val version = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull
        if (version == null || version >= SettingsMigrations.CURRENT_VERSION || !manager.validate(text).isValid) {
            return manager.import(text)
        }
        val settings = root.getValue("settings").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }
        val upgraded = JsonObject(root + mapOf(
            "schemaVersion" to JsonPrimitive(SettingsMigrations.CURRENT_VERSION),
            "settings" to JsonObject(SettingsMigrations.upgradeExport(settings, version).mapValues { JsonPrimitive(it.value) }),
        ))
        return manager.import(upgraded.toString(), ImportOptions(validateChecksum = false))
    }

    private fun checkStructure(text: String, limit: Int) {
        LimitedHistoryInput(text.toByteArray(Charsets.UTF_8).inputStream(), limit.toLong(), json = true).use { input ->
            val buffer = ByteArray(4096)
            while (input.read(buffer) != -1) { /* Validate nesting, including separately encoded field payloads. */ }
        }
    }
}
