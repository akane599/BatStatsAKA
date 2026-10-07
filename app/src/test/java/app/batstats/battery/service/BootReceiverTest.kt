package app.batstats.battery.service

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {
    @Test
    fun bootCompletedResumesMonitoring() {
        assertTrue(resumesMonitoring(Intent.ACTION_BOOT_COMPLETED))
    }

    @Test
    fun ownPackageReplacementResumesMonitoring() {
        assertTrue(resumesMonitoring(Intent.ACTION_MY_PACKAGE_REPLACED))
    }

    @Test
    fun otherPackageReplacementDoesNotResumeMonitoring() {
        assertFalse(resumesMonitoring(Intent.ACTION_PACKAGE_REPLACED))
    }

    @Test
    fun unrelatedActionDoesNotResumeMonitoring() {
        assertFalse(resumesMonitoring(Intent.ACTION_BATTERY_CHANGED))
    }

    @Test
    fun emptyActionDoesNotResumeMonitoring() {
        assertFalse(resumesMonitoring(""))
    }

    @Test
    fun missingActionDoesNotResumeMonitoring() {
        assertFalse(resumesMonitoring(null))
    }
}
