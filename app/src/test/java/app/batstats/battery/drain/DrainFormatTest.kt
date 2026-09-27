package app.batstats.battery.drain

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class DrainFormatTest {
    @Test fun smallValidConsumptionCannotBeRoundedToAFalseZero() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("0.12 mA", formatDrainRate(0.123))
            assertEquals("-0.12 mA", formatDrainRate(-0.123))
            assertEquals("0.001 mAh", formatCharge(0.001))
            assertEquals("0 mA", formatDrainRate(0.0))
            assertEquals("0.0 mAh", formatCharge(0.0))
            assertEquals("—", formatDrainRate(Double.NaN))
            assertEquals("—", formatCharge(Double.POSITIVE_INFINITY))
            assertEquals("—", formatCharge(null))
        } finally { Locale.setDefault(old) }
    }
}
