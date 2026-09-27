package app.batstats.settings

import android.content.Context
import app.batstats.R
import io.github.mlmgames.settings.core.SettingMeta

/** Generated metadata retains stable keys/values; Android resolves visible text in the current locale. */
object SettingsText {
    internal val titles = mapOf(
        "autoStartOnBoot" to R.string.auto_start_monitoring,
        "monitoringIntervalIndex" to R.string.monitoring_interval,
        "detailedStatsIntervalIndex" to R.string.settings_detailed_interval,
        "lowBatteryAlertEnabled" to R.string.low_battery_alert,
        "lowBatteryThreshold" to R.string.low_battery_threshold,
        "highBatteryAlertEnabled" to R.string.high_battery_alert,
        "highBatteryThreshold" to R.string.high_battery_threshold,
        "temperatureWarningEnabled" to R.string.temperature_warning,
        "temperatureThreshold" to R.string.settings_temperature_threshold,
        "dischargeAlertEnabled" to R.string.high_discharge_alert,
        "dischargeCurrentThreshold" to R.string.settings_discharge_threshold,
        "chargingCompleteAlert" to R.string.charging_complete_alert,
        "themeIndex" to R.string.theme,
        "dynamicColors" to R.string.dynamic_colors,
        "oledBlack" to R.string.settings_oled,
        "chartTimeRangeIndex" to R.string.chart_time_range,
        "showCurrentInMa" to R.string.show_current_ma,
        "temperatureUnitIndex" to R.string.temperature_unit,
        "dataRetentionIndex" to R.string.data_retention,
        "autoCleanupEnabled" to R.string.auto_cleanup
    )
    private val descriptions = mapOf(
        "monitoringIntervalIndex" to R.string.settings_sampling_help,
        "detailedStatsIntervalIndex" to R.string.settings_advanced_interval_help,
        "highBatteryAlertEnabled" to R.string.settings_high_alert_help,
        "temperatureThreshold" to R.string.settings_temperature_help,
        "dischargeCurrentThreshold" to R.string.settings_discharge_help,
        "dataRetentionIndex" to R.string.settings_retention_help,
        "autoStartOnBoot" to R.string.settings_boot_help
    )
    private val options = mapOf(
        "monitoringIntervalIndex" to listOf(R.string.option_5_seconds, R.string.option_10_seconds, R.string.option_30_seconds, R.string.option_1_minute, R.string.option_5_minutes),
        "detailedStatsIntervalIndex" to listOf(R.string.option_1_minute, R.string.option_5_minutes, R.string.option_15_minutes, R.string.option_30_minutes),
        "themeIndex" to listOf(R.string.option_system, R.string.option_light, R.string.option_dark),
        "chartTimeRangeIndex" to listOf(R.string.option_15_minutes, R.string.option_1_hour, R.string.option_6_hours, R.string.option_24_hours, R.string.option_7_days),
        "temperatureUnitIndex" to listOf(R.string.option_celsius, R.string.option_fahrenheit),
        "dataRetentionIndex" to listOf(R.string.option_1_week, R.string.option_1_month, R.string.option_3_months, R.string.option_6_months, R.string.option_1_year, R.string.option_forever)
    )
    fun resolve(context: Context, fieldName: String, meta: SettingMeta): SettingMeta = meta.copy(
        title = titles[fieldName]?.let(context::getString) ?: meta.title,
        description = descriptions[fieldName]?.let(context::getString) ?: meta.description,
        options = options[fieldName]?.map(context::getString) ?: meta.options
    )
}
