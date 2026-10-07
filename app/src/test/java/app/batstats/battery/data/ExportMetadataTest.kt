package app.batstats.battery.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportMetadataTest {
    @Test
    fun currentNowUaDescriptionIdentifiesRawDeviceDependentCurrent() {
        val description = exportUnits().getValue("currentNowUa")

        assertFalse(description.contains("positive into battery", ignoreCase = true))
        assertFalse(description.contains("normalized", ignoreCase = true))
        assertTrue(description.contains("raw", ignoreCase = true))
        assertTrue(description.contains("device", ignoreCase = true))
        assertTrue(description.contains("unit and sign are device-dependent", ignoreCase = true))
        assertTrue(description.contains("calibration is not applied", ignoreCase = true))
    }
}
