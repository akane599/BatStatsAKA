package app.batstats.settings

import io.github.mlmgames.settings.core.fields.EnumField

/** Checks and converts a UI write before it reaches `SettingsRepository.set`. */
object SettingsWrites {
    /**
     * [value] as [fieldName] stores it: a dropdown option index becomes the enum constant at that
     * index for enum settings. Throws [IllegalArgumentException] for an unknown field, an option
     * index out of range, or a design capacity that is not [DesignCapacity.isValid].
     */
    fun normalize(fieldName: String, value: Any): Any {
        val field = requireNotNull(AppSettingsSchema.fields.find { it.name == fieldName }) { "Unknown setting $fieldName" }
        if (field is EnumField<*, *> && value is Int) {
            return requireNotNull(field.fromUiDropdownIndex(value)) { "Option $value out of range for $fieldName" }
        }
        if (fieldName == "designCapacityMah") require(value is Int && DesignCapacity.isValid(value)) { "Invalid design capacity" }
        return value
    }
}
