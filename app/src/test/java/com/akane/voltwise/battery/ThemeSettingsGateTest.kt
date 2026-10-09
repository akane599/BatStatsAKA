package com.akane.voltwise.battery

import org.junit.Assert.assertNull
import org.junit.Test

class ThemeSettingsGateTest {
    @Test
    fun noThemedContentBeforeFirstSettingsValue() {
        // A non-null start value would render MainTheme with defaults (grey surfaces, static accents) for the
        // frames before the store emits, then flash to an OLED or dynamic-colour user's stored theme.
        assertNull(themeSettingsBeforeFirstEmission)
    }
}
