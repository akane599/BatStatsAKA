package app.batstats.battery.drain

import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.BatterySample
import app.batstats.settings.StatusIconValue
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class StatusIconTextTest {
    private fun reading(level: Int? = 78, currentUa: Long? = -612_000, voltageMv: Int? = 3_900, temperatureDeciC: Int? = 312) =
        BatteryRepository.Realtime(BatterySample(timestamp = 0, levelPercent = level, status = 3, plugged = 0,
            currentNowUa = currentUa, chargeCounterUah = null, voltageMv = voltageMv, temperatureDeciC = temperatureDeciC,
            health = 2, screenOn = true))

    private fun text(value: StatusIconValue, reading: BatteryRepository.Realtime = reading(), fahrenheit: Boolean = false,
                     locale: Locale = Locale.US) = StatusIconText.of(value, reading, fahrenheit, locale)

    @Test fun eachSettingSpellsItsValueInAtMostFourGlyphs() {
        assertEquals("78", text(StatusIconValue.LEVEL))
        assertEquals("100", text(StatusIconValue.LEVEL, reading(level = 100)))
        assertEquals("612", text(StatusIconValue.CURRENT_MA))
        assertEquals("No grouping separator", "1240", text(StatusIconValue.CURRENT_MA, reading(currentUa = 1_240_000)))
        assertEquals("2.4", text(StatusIconValue.POWER_W))
        assertEquals("12.4", text(StatusIconValue.POWER_W, reading(currentUa = -3_100_000, voltageMv = 4_000)))
        assertEquals("120", text(StatusIconValue.POWER_W, reading(currentUa = -30_000_000, voltageMv = 4_000)))
        assertEquals("31", text(StatusIconValue.TEMPERATURE))
        assertEquals("88", text(StatusIconValue.TEMPERATURE, fahrenheit = true))
        assertEquals("−5", text(StatusIconValue.TEMPERATURE, reading(temperatureDeciC = -50)))
        assertEquals("2,4", text(StatusIconValue.POWER_W, locale = Locale.forLanguageTag("tr-TR")))
    }

    @Test fun staticMissingOrOverflowingValuesFallBackToTheStaticIcon() {
        assertNull(text(StatusIconValue.STATIC))
        assertNull(text(StatusIconValue.LEVEL, reading(level = null)))
        assertNull(text(StatusIconValue.CURRENT_MA, reading(currentUa = null)))
        assertNull(text(StatusIconValue.POWER_W, reading(voltageMv = null)))
        assertNull(text(StatusIconValue.TEMPERATURE, reading(temperatureDeciC = null)))
        assertNull("5 glyphs overflow", text(StatusIconValue.CURRENT_MA, reading(currentUa = -12_345_000)))
    }
}
