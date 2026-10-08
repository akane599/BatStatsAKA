package com.akane.voltwise.battery.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuPermissionTest {
    @Test fun denyAndDontAskAgainBlocksPermissionRequests() {
        assertTrue(
            "Deny and don't ask again (rationale=true) must be blocked",
            shizukuPermissionBlocked(running = true, preV11 = false, granted = false, rationale = true),
        )
    }

    @Test fun ordinaryDenialStillAllowsARequest() {
        assertFalse(shizukuPermissionBlocked(true, false, false, false))
    }

    @Test fun stoppedLegacyAndGrantedStatesAreNeverBlocked() {
        assertFalse(shizukuPermissionBlocked(false, false, false, true))
        assertFalse(shizukuPermissionBlocked(true, true, false, true))
        assertFalse(shizukuPermissionBlocked(true, false, true, true))
    }
}
