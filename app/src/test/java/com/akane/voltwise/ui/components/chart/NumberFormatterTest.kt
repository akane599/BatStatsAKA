package com.akane.voltwise.ui.components.chart

import java.text.NumberFormat
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class NumberFormatterTest {
    private val saved = Locale.getDefault()

    @Before fun setUp() = Locale.setDefault(Locale.US)

    @After fun tearDown() = Locale.setDefault(saved)

    @Test fun negativesUseTheTypographicMinusAndZeroHasNoSign() {
        assertEquals("−412 mA", NumberFormatter("mA", 0).format(-412.0))
        assertEquals("−1,000", NumberFormatter().format(-1_000.0))
        assertEquals("1.6 W", NumberFormatter("W").format(1.58))
        assertEquals("0", NumberFormatter(maxDecimals = 0).format(-0.2))
    }

    @Test fun percentFollowsTheLocaleWithoutASpace() {
        assertEquals("94%", NumberFormatter("%", maxDecimals = 0).format(94.0))
        assertEquals("4.5%", NumberFormatter("%").format(4.48))
        assertEquals("100%", NumberFormatter("%").format(100.0))
        assertEquals("−3%", NumberFormatter("%", maxDecimals = 0).format(-3.0))
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        assertEquals("%94", NumberFormatter("%", maxDecimals = 0).format(94.0))
        assertEquals("%4,5", NumberFormatter("%").format(4.48))
    }

    @Test fun tickFormatsUseTheSameSign() {
        val format = NumberFormat.getNumberInstance(Locale.US).apply { maximumFractionDigits = 1 }
        assertEquals("−500", format.formatWithMinus(-500.0))
        assertEquals("−0.5", format.formatWithMinus(-0.5))
        assertEquals("0", format.formatWithMinus(-0.01))
        assertEquals("250", format.formatWithMinus(250.0))
        assertEquals("−", MINUS_SIGN)
    }
}
