package com.akane.voltwise.battery.measurement

/** How a device reports `BATTERY_PROPERTY_CURRENT_NOW`. The database always keeps the raw value. */
enum class CurrentUnit(val microampsPerUnit: Long) { MICROAMPS(1), MILLIAMPS(1_000) }

/** NORMAL: negative while discharging (Android contract). INVERTED: positive while discharging. */
enum class CurrentSign { NORMAL, INVERTED }

data class CurrentCalibration(
    val unit: CurrentUnit = CurrentUnit.MICROAMPS,
    val sign: CurrentSign = CurrentSign.NORMAL,
) {
    fun toMicroamps(raw: Long): Long = raw * unit.microampsPerUnit * if (sign == CurrentSign.INVERTED) -1 else 1

    companion object { val IDENTITY = CurrentCalibration() }
}

enum class CalibrationSource { DEFAULT, DETECTED, OVERRIDE }

/**
 * What realtime values, alerts, charts, notification and tile use. Overrides from Settings win over
 * detection; [noticePending] is true from when a detected correction is applied until the user
 * dismisses or undoes it.
 */
data class CalibrationState(
    val effective: CurrentCalibration = CurrentCalibration.IDENTITY,
    val detected: CurrentCalibration? = null,
    val source: CalibrationSource = CalibrationSource.DEFAULT,
    val agreeingWindows: Int = 0,
    val noticePending: Boolean = false,
)
