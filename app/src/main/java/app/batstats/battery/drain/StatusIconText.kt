package app.batstats.battery.drain

import app.batstats.battery.data.BatteryRepository
import app.batstats.settings.StatusIconValue
import java.util.Locale
import kotlin.math.abs

/**
 * What the status-bar icon spells for the `status_icon_value` setting: level "78", current "612" (mA magnitude),
 * power "2.4" (W), temperature "31" (°C, or °F). Null — the static `ic_stat_battery` — for [StatusIconValue.STATIC],
 * a missing value, or text longer than [MAX_GLYPHS] (it would be unreadable at status-bar size).
 */
object StatusIconText {
    const val MAX_GLYPHS = 4

    fun of(value: StatusIconValue, reading: BatteryRepository.Realtime, fahrenheit: Boolean, locale: Locale): String? {
        val text = when (value) {
            StatusIconValue.LEVEL -> reading.level?.let { plain(it.toDouble(), 0, locale) }
            StatusIconValue.CURRENT_MA -> reading.currentMa?.let { plain(abs(it), 0, locale) }
            StatusIconValue.POWER_W -> reading.powerMw?.let { abs(it) / 1_000 }?.let { plain(it, if (it < 100) 1 else 0, locale) }
            StatusIconValue.TEMPERATURE -> reading.temperatureC?.toDouble()
                ?.let { plain(if (fahrenheit) it * 1.8 + 32 else it, 0, locale) }
            StatusIconValue.STATIC -> null
        }
        return text?.takeIf { it.codePointCount(0, it.length) in 1..MAX_GLYPHS }
    }

    private fun plain(value: Double, decimals: Int, locale: Locale): String? =
        value.takeIf(Double::isFinite)?.let { formatNumber(it, decimals, locale, grouping = false) }
}
