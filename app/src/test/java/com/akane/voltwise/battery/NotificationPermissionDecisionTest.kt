package com.akane.voltwise.battery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionDecisionTest {
    @Test
    fun firstFreshStartOnAndroid13RequestsPermission() {
        assertTrue(shouldRequestNotificationPermission(33, false, false, false, false))
    }

    @Test
    fun firstFreshStartOnNewerAndroidRequestsPermission() {
        assertTrue(shouldRequestNotificationPermission(37, false, false, false, false))
    }

    @Test
    fun olderAndroidDoesNotRequestPermission() {
        assertFalse(shouldRequestNotificationPermission(26, false, false, false, false))
        assertFalse(shouldRequestNotificationPermission(32, false, false, false, false))
    }

    @Test
    fun grantedPermissionDoesNotRequestAgain() {
        assertFalse(shouldRequestNotificationPermission(33, true, false, false, false))
    }

    @Test
    fun restoredActivityDoesNotStackARequest() {
        assertFalse(shouldRequestNotificationPermission(33, false, true, false, false))
    }

    @Test
    fun freshLaunchAfterDenialDoesNotRequestAgain() {
        assertFalse(shouldRequestNotificationPermission(33, false, false, true, true))
    }

    @Test
    fun askedBeforeWithoutRationaleDoesNotRequestAgain() {
        assertFalse(shouldRequestNotificationPermission(33, false, false, true, false))
    }

    @Test
    fun existingDenialWithoutStoredFlagDoesNotRequestAgain() {
        assertFalse(shouldRequestNotificationPermission(33, false, false, false, true))
    }
}
