package app.batstats.settings

import android.content.Context
import app.batstats.R
import io.github.mlmgames.settings.core.SettingMeta

/** Generated metadata retains stable keys/values; Android resolves visible text in the current locale. */
object SettingsText {
    internal val titles = mapOf(
        "autoStartOnBoot" to R.string.auto_start_monitoring,
        "statusIconValue" to R.string.settings_status_icon,
        "lowBatteryAlertEnabled" to R.string.low_battery_alert,
        "lowBatteryThreshold" to R.string.low_battery_threshold,
        "highBatteryAlertEnabled" to R.string.high_battery_alert,
        "highBatteryThreshold" to R.string.high_battery_threshold,
        "temperatureWarningEnabled" to R.string.temperature_warning,
        "temperatureThreshold" to R.string.settings_temperature_threshold,
        "dischargeAlertEnabled" to R.string.high_discharge_alert,
        "dischargeCurrentThreshold" to R.string.settings_discharge_threshold,
        "chargingCompleteAlert" to R.string.charging_complete_alert,
        "currentUnitOverride" to R.string.settings_current_unit,
        "currentSignOverride" to R.string.settings_current_sign,
        "designCapacityMah" to R.string.settings_design_capacity,
        "dynamicColors" to R.string.dynamic_colors,
        "oledBlack" to R.string.settings_oled,
        "temperatureUnitIndex" to R.string.temperature_unit,
        "dataRetentionIndex" to R.string.data_retention,
    )
    private val descriptions = mapOf(
        "statusIconValue" to R.string.settings_status_icon_help,
        "highBatteryAlertEnabled" to R.string.settings_high_alert_help,
        "temperatureThreshold" to R.string.settings_temperature_help,
        "dischargeCurrentThreshold" to R.string.settings_discharge_help,
        "currentUnitOverride" to R.string.settings_current_unit_help,
        "currentSignOverride" to R.string.settings_current_sign_help,
        "designCapacityMah" to R.string.settings_design_capacity_help,
        "dataRetentionIndex" to R.string.settings_retention_help,
        "autoStartOnBoot" to R.string.settings_boot_help,
    )
    private val options = mapOf(
        "statusIconValue" to listOf(R.string.option_status_level, R.string.option_status_current, R.string.option_status_power,
            R.string.option_status_temperature, R.string.option_status_static),
        "currentUnitOverride" to listOf(R.string.option_auto, R.string.option_microamps, R.string.option_milliamps),
        "currentSignOverride" to listOf(R.string.option_auto, R.string.option_sign_normal, R.string.option_sign_inverted),
        "temperatureUnitIndex" to listOf(R.string.option_celsius, R.string.option_fahrenheit),
        "dataRetentionIndex" to listOf(R.string.option_1_week, R.string.option_1_month, R.string.option_3_months, R.string.option_6_months, R.string.option_1_year, R.string.option_forever),
    )
    fun resolve(context: Context, fieldName: String, meta: SettingMeta): SettingMeta = meta.copy(
        title = titles[fieldName]?.let(context::getString) ?: meta.title,
        description = descriptions[fieldName]?.let(context::getString) ?: meta.description,
        options = options[fieldName]?.map(context::getString) ?: meta.options
    )
}
