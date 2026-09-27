package app.batstats.settings

import app.batstats.battery.data.LimitedHistoryInput
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
            }
        }
    }
    private fun checkStructure(text: String, limit: Int) {
        LimitedHistoryInput(text.toByteArray(Charsets.UTF_8).inputStream(), limit.toLong(), json = true).use { input ->
            val buffer = ByteArray(4096)
            while (input.read(buffer) != -1) { /* Validate nesting, including separately encoded field payloads. */ }
        }
    }
}
