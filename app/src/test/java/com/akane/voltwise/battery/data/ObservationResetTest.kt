package com.akane.voltwise.battery.data

import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.SessionType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationResetTest {
    @Test fun resetDoesNotApplyToChargeSession() {
        assertFalse("Reset must not close CHARGE or reset its ETA", resetApplies(open(SessionType.CHARGE)))
    }

    @Test fun resetDoesNotApplyToPluggedSession() {
        assertFalse("Reset must not close PLUGGED or reset observation state", resetApplies(open(SessionType.PLUGGED)))
    }

    @Test fun resetAppliesToDischargeSession() {
        assertTrue("Reset must close the current DISCHARGE window", resetApplies(open(SessionType.DISCHARGE)))
    }

    @Test fun resetDoesNotApplyToUnknownSession() {
        assertFalse("Only a DISCHARGE session can be reset", resetApplies(open(SessionType.UNKNOWN)))
    }

    @Test fun resetDoesNotApplyWithoutAnOpenSession() {
        assertFalse("Reset without an open session must be a no-op", resetApplies(null))
    }

    private fun open(type: SessionType) = ChargeSession(
        sessionId = "open-session",
        type = type,
        startTime = 1_000L,
        endTime = null,
        startLevel = 50,
        endLevel = null,
        deltaUah = null,
        avgCurrentUa = null,
        estCapacityMah = null,
    )
}
