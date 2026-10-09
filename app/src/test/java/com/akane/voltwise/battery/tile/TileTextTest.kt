package com.akane.voltwise.battery.tile

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class TileTextTest {
    private val offText = "Off"
    private val noReadingText = "—"
    private val unitMa = "mA"
    private val unitW = "W"

    private fun of(isMonitoring: Boolean, currentMa: Double?, powerMw: Double?, locale: Locale = Locale.US) = TileText.of(
        isMonitoring = isMonitoring,
        currentMa = currentMa,
        powerMw = powerMw,
        offText = offText,
        noReadingText = noReadingText,
        unitMa = unitMa,
        unitW = unitW,
        locale = locale,
    )

    @Test
    fun monitoringOffAlwaysShowsOffText() {
        assertEquals(offText, of(isMonitoring = false, currentMa = -612.0, powerMw = -2400.0))
        assertEquals(offText, of(isMonitoring = false, currentMa = null, powerMw = null))
    }

    @Test
    fun monitoringOnWithNoCaptureYetShowsNoReadingText() {
        assertEquals(noReadingText, of(isMonitoring = true, currentMa = null, powerMw = null))
        assertEquals(noReadingText, of(isMonitoring = true, currentMa = -612.0, powerMw = null))
    }

    @Test
    fun dischargingCurrentUsesTheTypographicMinusAndUnsignedPower() {
        assertEquals("−612 mA · 2.4 W", of(isMonitoring = true, currentMa = -612.0, powerMw = -2400.0))
    }

    @Test
    fun chargingCurrentIsExplicitlySigned() {
        assertEquals("+850 mA · 3.3 W", of(isMonitoring = true, currentMa = 850.0, powerMw = 3300.0))
    }

    @Test
    fun currentRoundingToZeroHasNoSign() {
        assertEquals("0 mA · 0.0 W", of(isMonitoring = true, currentMa = -0.2, powerMw = -0.04))
    }

    @Test
    fun currentIsRoundedToWholeMilliamps() {
        assertEquals("−1,235 mA · 4.6 W", of(isMonitoring = true, currentMa = -1234.6, powerMw = -4600.0))
    }

    @Test
    fun localeControlsGroupingAndDecimalSeparator() {
        assertEquals("+1.235 mA · 4,6 W", of(isMonitoring = true, currentMa = 1234.6, powerMw = 4600.0, locale = Locale.GERMANY))
    }
}
