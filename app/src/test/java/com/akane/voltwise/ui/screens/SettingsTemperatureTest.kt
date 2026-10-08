package com.akane.voltwise.ui.screens

import com.akane.voltwise.viewmodel.SettingsThreshold
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Fahrenheit temperature slider: whole °F stops over the stored °C range. */
class SettingsTemperatureTest {
    private val celsius = SettingsThreshold.TEMPERATURE.range

    @Test fun theFahrenheitSliderStopsAtEveryWholeDegreeInsideTheCelsiusRange() {
        val fahrenheit = wholeFahrenheitRange(celsius)

        assertEquals(95f..131f, fahrenheit)
        // 36 one-degree stops between the ends: 95, 96, 97 … 131, never 104 → 105.8.
        assertEquals(35, sliderSteps(fahrenheit, 1f))
        // The °C slider is unchanged: 1 °C stops.
        assertEquals(19, sliderSteps(celsius, SettingsThreshold.TEMPERATURE.step))
    }

    @Test fun everyWholeFahrenheitStopRoundTripsThroughTheStoredCelsius() {
        for (degrees in 95..131) {
            val stored = fahrenheitToCelsius(degrees.toFloat())
            assertTrue("$degrees °F -> $stored °C", stored in celsius)
            assertEquals("$degrees °F", degrees, celsiusToFahrenheit(stored).roundToInt())
            // What the store keeps (only clamped) still reads back as the chosen °F.
            assertEquals("$degrees °F stored", degrees, celsiusToFahrenheit(SettingsThreshold.TEMPERATURE.stored(stored) as Float).roundToInt())
        }
    }

    @Test fun aWholeCelsiusValueOpensOnTheNearestWholeFahrenheit() {
        assertEquals(100, celsiusToFahrenheit(38f).roundToInt())
        assertEquals(113, celsiusToFahrenheit(45f).roundToInt())
    }
}
