package com.akane.voltwise.battery.service

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {
    @Test
    fun ownPackageReplacementDoesNotRestartStoppedMonitoring() {
        assertFalse(resumesMonitoring(Intent.ACTION_MY_PACKAGE_REPLACED, autoStart = true, monitoringWanted = false))
    }

    @Test
    fun bootCompletedStillStartsAfterUserStoppedMonitoring() {
        assertTrue(resumesMonitoring(Intent.ACTION_BOOT_COMPLETED, autoStart = true, monitoringWanted = false))
    }

    @Test
    fun autoStartDisabledDoesNotResumeEitherAction() {
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            for (wanted in listOf(false, true)) {
                assertFalse(resumesMonitoring(action, autoStart = false, monitoringWanted = wanted))
            }
        }
    }

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
