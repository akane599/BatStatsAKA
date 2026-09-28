package app.batstats.battery.drain

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class DrainFormatTest {
    private val turkish = Locale.forLanguageTag("tr-TR")
    private val spanish = Locale.forLanguageTag("es-ES")

    @Test fun smallValidConsumptionCannotBeRoundedToAFalseZero() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("0.12 mA", formatDrainRate(0.123))
            assertEquals("−0.12 mA", formatDrainRate(-0.123))
            assertEquals("0.001 mAh", formatCharge(0.001))
            assertEquals("0 mA", formatDrainRate(0.0))
            assertEquals("0.0 mAh", formatCharge(0.0))
            assertEquals("—", formatDrainRate(Double.NaN))
            assertEquals("—", formatCharge(Double.POSITIVE_INFINITY))
            assertEquals("—", formatCharge(null))
        } finally { Locale.setDefault(old) }
    }

    @Test fun ratesAndChargeAreGroupedInTheViewersLocaleWithTheTypographicMinus() {
        assertEquals("1,240 mA", formatDrainRate(1_240.4, Locale.US))
        assertEquals("1.240 mA", formatDrainRate(1_240.4, turkish))
        assertEquals("0,12 mA", formatDrainRate(0.123, turkish))
        assertEquals("−38 mA", formatDrainRate(-38.2, Locale.US))
        assertEquals("1,412.3 mAh", formatCharge(1_412.26, Locale.US))
        assertEquals("412,3 mAh", formatCharge(412.26, turkish))
    }

    @Test fun liveReadoutsCarrySignUnitsAndLocaleDecimals() {
        assertEquals("−612 mA", formatCurrent(-612.4, Locale.US))
        assertEquals("+1,240 mA", formatCurrent(1_240.0, Locale.US))
        assertEquals("No signed zero", "0 mA", formatCurrent(-0.3, Locale.US))
        assertEquals("—", formatCurrent(null))
        // Power is a magnitude: the current carries the direction.
        assertEquals("2.4 W", formatPower(-2_412.0, Locale.US))
        assertEquals("0.35 W", formatPower(350.0, Locale.US))
        assertEquals("2,4 W", formatPower(2_412.0, turkish))
        assertEquals("—", formatPower(Double.NaN))
        assertEquals("31.2 °C", formatTemperature(31.24, fahrenheit = false, Locale.US))
        assertEquals("88.2 °F", formatTemperature(31.24, fahrenheit = true, Locale.US))
        assertEquals("−5.0 °C", formatTemperature(-5.0, fahrenheit = false, Locale.US))
        assertEquals("31,2 °C", formatTemperature(31.24, fahrenheit = false, spanish))
        assertEquals("—", formatTemperature(null, fahrenheit = false))
        assertEquals("3.87 V", formatVoltage(3_871, Locale.US))
        assertEquals("3,87 V", formatVoltage(3_871, turkish))
        assertEquals("—", formatVoltage(null))
    }

    @Test fun numbersHaveNoSignedZeroAndSignedValuesMarkTheDirection() {
        assertEquals("0", formatNumber(-0.4, 0, Locale.US))
        assertEquals("−1,000", formatNumber(-1_000.0, 0, Locale.US))
        assertEquals("1000", formatNumber(1_000.0, 0, Locale.US, grouping = false))
        assertEquals("+12.5", formatSigned(12.5, 1, Locale.US))
        assertEquals("−12,5", formatSigned(-12.5, 1, turkish))
        assertEquals("0.0", formatSigned(0.01, 1, Locale.US))
    }
}
