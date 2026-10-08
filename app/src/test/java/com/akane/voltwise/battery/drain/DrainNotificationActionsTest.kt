package com.akane.voltwise.battery.drain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrainNotificationActionsTest {
    @Test fun `stop and reset require authentication from Android 12`() {
        assertTrue("Stop and Reset must require unlock on API 31", actionsRequireAuth(31))
    }

    @Test fun `stop and reset keep requiring authentication on newer Android versions`() {
        assertTrue("Stop and Reset must require unlock on API 37", actionsRequireAuth(37))
    }

    @Test fun `older Android versions retain actions without authentication`() {
        assertFalse("API 26 has no action authentication support", actionsRequireAuth(26))
        assertFalse("API 30 has no action authentication support", actionsRequireAuth(30))
    }
}
